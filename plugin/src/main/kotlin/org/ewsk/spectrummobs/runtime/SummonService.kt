package org.ewsk.spectrummobs.runtime

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.ewsk.spectrummobs.core.OperationCode
import org.ewsk.spectrummobs.mob.MobIdentity
import org.ewsk.spectrummobs.mob.MobSpawnResult
import org.ewsk.spectrummobs.script.RuntimeContext
import org.ewsk.spectrummobs.script.RuntimeValue
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

data class SummonRequest(
    val owner: Entity, val id: String, val location: Location,
    val batchId: UUID, val executionId: UUID, val tag: String?
)

/** 批次只管理战斗实体和资源所有权，不管理场地、房间或副本。 */
class SummonService(
    private val spawn: (SummonRequest) -> MobSpawnResult,
    private val resolve: (UUID) -> Entity? = Bukkit::getEntity,
    private val owned: (UUID) -> List<Entity> = { owner ->
        Bukkit.getWorlds().flatMap { it.entities }.filter { MobIdentity.summonOwner(it) == owner }
    }
) {
    private enum class End { ACTIVE, DEATH, REMOVED }
    private class Batch(
        val id: UUID, val owner: UUID, val execution: UUID, val world: UUID,
        val requested: Int, val expires: Long?, val context: RuntimeContext
    ) {
        val members = linkedMapOf<UUID, End>()
        var building = true
        var stopped = false
        var complete = false
        val key get() = "summon:$id"
    }

    private val batches = linkedMapOf<UUID, Batch>()

    fun summon(
        context: RuntimeContext, owner: Entity, id: String, center: Location,
        count: Int, tag: String?, lifetime: Long
    ): RuntimeValue.OperationResultValue {
        require(count in 1..64) { "Summon count must be an integer between 1 and 64" }
        require(lifetime >= 0) { "Summon lifetime must be non-negative" }
        val world = center.world ?: throw IllegalArgumentException("Summon location has no world")
        val expires = if (lifetime == 0L) null else context.currentTick + lifetime.coerceAtMost(Long.MAX_VALUE - context.currentTick)
        val batch = Batch(UUID.randomUUID(), owner.uniqueId, context.executionId, world.uid, count, expires, context)
        batches[batch.id] = batch
        context.effects.register(context.executionId, batch.key) { cleanupMembers(batch) }
        var code = OperationCode.SUCCESS
        var reason = ""
        var created = 0
        try {
            for (index in 0 until count) {
                if (batch.stopped) {
                    code = OperationCode.PARENT_INACTIVE
                    reason = "Summon execution was cancelled during entity creation"
                    break
                }
                val angle = if (count == 1) 0.0 else Math.PI * 2 * index / count
                val location = center.clone().add(cos(angle) * 1.5, 0.0, sin(angle) * 1.5)
                when (val result = spawn(SummonRequest(owner, id, location, batch.id, context.executionId, tag))) {
                    is MobSpawnResult.Spawned -> {
                        val entity = result.entity
                        created++
                        batch.members.putIfAbsent(entity.uniqueId, End.ACTIVE)
                        if (batch.stopped) {
                            MobIdentity.markSummonCleanup(entity)
                            entity.remove()
                            batch.members[entity.uniqueId] = End.REMOVED
                            code = OperationCode.PARENT_INACTIVE
                            reason = "Summon execution was cancelled during entity creation"
                            break
                        }
                    }
                    is MobSpawnResult.Failed -> { code = result.code; reason = result.reason; break }
                }
            }
        } catch (error: Exception) {
            cleanup(batch)
            throw error
        } finally {
            batch.building = false
        }
        batch.complete = created == count && code == OperationCode.SUCCESS
        if (code != OperationCode.SUCCESS && !code.expectedRejection) cleanup(batch)
        releaseIfFinished(batch)
        return RuntimeValue.OperationResultValue(batch.id.toString(), created, count, code, reason, batch)
    }

    fun targets(owner: Entity, result: RuntimeValue.OperationResultValue? = null, tag: String? = null): List<Entity> {
        val batch = result?.let { batch(owner, it) }
        val candidates = if (batch == null) owned(owner.uniqueId) else batch.members
            .filterValues { it == End.ACTIVE }.keys.mapNotNull(resolve)
        return candidates.filter { it.isValid && !it.isDead && !MobIdentity.isSummonCleanup(it) &&
            (tag == null || tag in it.scoreboardTags) }
    }

    fun defeated(owner: Entity, result: RuntimeValue.OperationResultValue): Boolean {
        val batch = batch(owner, result)
        // 不以范围查询为空作为胜利依据：部分生成、卸载、移除和超时都不等于击破。
        return batch.complete && batch.members.size == batch.requested &&
            batch.members.values.all { it == End.DEATH }
    }

    fun cleanup(owner: Entity, result: RuntimeValue.OperationResultValue) = cleanup(batch(owner, result))

    fun onDeath(entity: Entity) = end(entity, if (MobIdentity.isSummonCleanup(entity)) End.REMOVED else End.DEATH)
    fun onRemoved(entity: Entity) = end(entity, End.REMOVED)

    fun tick(tick: Long) {
        batches.values.toList().forEach { batch ->
            val owner = resolve(batch.owner)
            if (owner == null || !owner.isValid || owner.isDead || (batch.expires != null && tick >= batch.expires)) {
                cleanup(batch)
            } else {
                batch.members.filterValues { it == End.ACTIVE }.keys.toList().forEach { id ->
                    val entity = resolve(id)
                    if (entity == null || !entity.isValid || entity.isDead || MobIdentity.isSummonCleanup(entity)) {
                        // 只有显式死亡事件能够记为 DEATH；轮询不能猜测消失的原因。
                        batch.members[id] = End.REMOVED
                    }
                }
                releaseIfFinished(batch)
            }
        }
    }

    fun cleanupWorld(world: UUID) = batches.values.filter { batch ->
        batch.world == world || batch.members.filterValues { it == End.ACTIVE }.keys.any { resolve(it)?.world?.uid == world }
    }.toList().forEach(::cleanup)

    /** 旧执行无法恢复；加载孤儿临时召唤物时按清理处理。 */
    fun cleanupOrphan(entity: Entity) {
        val id = MobIdentity.summonBatch(entity) ?: return
        if (batches[id]?.members?.get(entity.uniqueId) != End.ACTIVE) {
            MobIdentity.markSummonCleanup(entity)
            entity.remove()
        }
    }

    private fun batch(owner: Entity, result: RuntimeValue.OperationResultValue): Batch {
        val batch = result.origin as? Batch ?: throw IllegalArgumentException("OperationResult is not a summon batch")
        require(batch.owner == owner.uniqueId) { "Summon batch does not belong to owner ${owner.uniqueId}" }
        return batch
    }

    private fun end(entity: Entity, end: End) {
        val id = MobIdentity.summonBatch(entity) ?: return
        val batch = batches[id] ?: return
        if (MobIdentity.summonOwner(entity) != batch.owner) return
        // 死亡后还会收到移除事件；保留首次终止原因。
        if (batch.members[entity.uniqueId] == End.ACTIVE || (batch.building && entity.uniqueId !in batch.members)) {
            batch.members[entity.uniqueId] = end
        }
        releaseIfFinished(batch)
    }

    private fun cleanup(batch: Batch) {
        if (!batch.context.effects.dispose(batch.execution, batch.key)) cleanupMembers(batch)
    }

    private fun cleanupMembers(batch: Batch) {
        if (batch.stopped) return
        batch.stopped = true
        val errors = mutableListOf<Exception>()
        batch.members.filterValues { it == End.ACTIVE }.keys.toList().forEach { id ->
            batch.members[id] = End.REMOVED
            try {
                resolve(id)?.let { entity ->
                    MobIdentity.markSummonCleanup(entity)
                    entity.remove()
                }
            } catch (error: Exception) {
                errors += error
            }
        }
        batches.remove(batch.id)
        batch.context.effects.release(batch.execution, batch.key)
        if (errors.isNotEmpty()) {
            throw IllegalStateException("Unable to clean ${errors.size} summon entities: ${errors.first().message}", errors.first())
        }
    }

    private fun releaseIfFinished(batch: Batch) {
        if (batch.building || batch.members.values.any { it == End.ACTIVE }) return
        batches.remove(batch.id)
        batch.context.effects.release(batch.execution, batch.key)
    }
}
