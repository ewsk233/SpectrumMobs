package org.ewsk.spectrummobs.mob

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.core.MobIntervalTrigger
import java.util.UUID

data class IntervalTriggerFire(
    val entityId: UUID,
    val interval: MobIntervalTrigger
)

/** 与 Bukkit 无关的 interval 计时器。每个实体拥有独立的下一次触发 tick。 */
class MobIntervalSchedule {
    private data class Entry(
        val entityId: UUID,
        val worldKey: String,
        val nextTicks: MutableMap<String, Long>
    )

    private val entries = linkedMapOf<UUID, Entry>()

    fun register(entityId: UUID, worldKey: String, intervals: List<MobIntervalTrigger>, currentTick: Long) {
        val next = intervals.associate { it.id to currentTick + it.periodTicks }.toMutableMap()
        if (next.isEmpty()) {
            entries.remove(entityId)
        } else {
            entries[entityId] = Entry(entityId, worldKey, next)
        }
    }

    fun unregister(entityId: UUID) {
        entries.remove(entityId)
    }

    fun unregisterByWorld(worldKey: String) {
        entries.entries.removeIf { it.value.worldKey == worldKey }
    }

    fun tick(
        currentTick: Long,
        activeIntervals: (UUID) -> List<MobIntervalTrigger>?
    ): List<IntervalTriggerFire> {
        val fires = mutableListOf<IntervalTriggerFire>()
        entries.values.toList().forEach { entry ->
            val intervals = activeIntervals(entry.entityId)
            if (intervals == null) {
                entries.remove(entry.entityId)
                return@forEach
            }
            val byId = intervals.associateBy { it.id }
            if (byId.isEmpty()) {
                entries.remove(entry.entityId)
                return@forEach
            }
            entry.nextTicks.keys.retainAll(byId.keys)
            byId.values.forEach { interval ->
                val next = entry.nextTicks[interval.id] ?: (currentTick + interval.periodTicks)
                if (currentTick >= next) {
                    fires += IntervalTriggerFire(entry.entityId, interval)
                    // 一次 tick 最多触发一次，避免服务器卡顿后补发大量技能实例。
                    entry.nextTicks[interval.id] = currentTick + interval.periodTicks
                } else {
                    entry.nextTicks[interval.id] = next
                }
            }
        }
        return fires
    }
}

/** 按实体 UUID 保存 interval 状态，并把实际实体操作留在插件适配层。 */
class MobIntervalService(
    private val repository: MobConfigRepository,
    private val router: MobTriggerRouter
) {
    private val schedule = MobIntervalSchedule()

    fun register(entity: Entity, currentTick: Long) {
        val definition = MobIdentity.definitionId(entity)?.let(repository.current()::find) ?: return
        schedule.register(entity.uniqueId, entity.world.uid.toString(), activeIntervals(definition), currentTick)
    }

    fun registerLoaded(currentTick: Long) {
        Bukkit.getWorlds().forEach { world ->
            world.entities.filter { MobIdentity.isManaged(it) }.forEach { register(it, currentTick) }
        }
    }

    fun unregister(entity: Entity) {
        schedule.unregister(entity.uniqueId)
    }

    fun unregisterByWorld(worldKey: String) {
        schedule.unregisterByWorld(worldKey)
    }

    fun tick(currentTick: Long) {
        val fires = schedule.tick(currentTick) { entityId ->
            val entity = Bukkit.getEntity(entityId)
            if (entity == null || !entity.isValid) {
                null
            } else {
                MobIdentity.definitionId(entity)?.let(repository.current()::find)?.let(::activeIntervals)
            }
        }
        fires.forEach { fire ->
            val entity = Bukkit.getEntity(fire.entityId) ?: return@forEach
            router.triggerSkills(
                skills = fire.interval.skills,
                caster = entity,
                context = MobTriggerContext(
                    eventName = if (fire.interval.id == BEHAVIOR_ID) "behavior" else "interval",
                    interval = fire.interval.id,
                    cause = "interval",
                    phase = MobIdentity.phaseId(entity)
                ),
                arguments = { index ->
                    fire.interval.skillArguments.getOrNull(index).orEmpty().mapValues { (_, value) -> router.toRuntimeValue(value) }
                }
            )
        }
    }

    private fun activeIntervals(definition: org.ewsk.spectrummobs.core.MobDefinition): List<MobIntervalTrigger> =
        definition.intervals + definition.behavior?.let { listOf(MobIntervalTrigger(BEHAVIOR_ID, it.periodTicks, listOf(it.skill))) }.orEmpty()

    private companion object { const val BEHAVIOR_ID = "__spectrum_behavior" }
}
