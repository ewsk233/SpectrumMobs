package org.ewsk.spectrummobs.mob

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.util.Vector
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.core.MobDefinition
import kotlin.math.sin
import java.util.UUID

/** 通用实体移动控制器注册表。控制器只处理运动，不包含 Boss 名称或技能逻辑。 */
fun interface MobMovementController {
    fun tick(entity: Entity, definition: MobDefinition, target: Entity?, currentTick: Long)
}

class MobMovementService(
    private val repository: MobConfigRepository,
    private val targetResolver: (Entity) -> Entity?
) {
    private val controllers = linkedMapOf<String, MobMovementController>()
    private val active = linkedMapOf<UUID, String>()
    private val previousAware = linkedMapOf<UUID, Boolean>()

    init {
        registerController("hover", MobMovementController { entity, definition, target, tick ->
            val movement = definition.movement ?: return@MobMovementController
            val mob = entity as? Mob ?: return@MobMovementController
            if (target == null || !target.isValid || target.isDead) {
                mob.velocity = Vector(0.0, sin(tick / 10.0) * 0.025, 0.0)
                return@MobMovementController
            }
            val current = entity.location
            val targetLocation = target.location
            var horizontal = current.toVector().subtract(targetLocation.toVector()).setY(0.0)
            if (horizontal.lengthSquared() < 1.0e-6) horizontal = Vector(1.0, 0.0, 0.0)
            val desired = targetLocation.clone().add(horizontal.normalize().multiply(movement.targetDistance))
            desired.y = targetLocation.y + movement.hoverHeight + sin(tick / 8.0) * 0.35
            val delta = desired.toVector().subtract(current.toVector())
            val distance = delta.length()
            val velocity = if (distance < 1.0e-6) Vector() else delta.normalize().multiply(movement.speed.coerceAtMost(distance))
            mob.velocity = velocity
        })
    }

    fun registerController(id: String, controller: MobMovementController) {
        require(id.matches(Regex("[a-z0-9_.-]+"))) { "Invalid movement controller ID: $id" }
        controllers[id.lowercase()] = controller
    }

    fun register(entity: Entity) {
        val definition = MobIdentity.definitionId(entity)?.let(repository.current()::find) ?: return
        val movement = definition.movement ?: return
        controllers[movement.controller] ?: return
        active[entity.uniqueId] = movement.controller
        val mob = entity as? Mob
        if (mob != null && entity.uniqueId !in previousAware) {
            previousAware[entity.uniqueId] = mob.isAware
            mob.isAware = false
        }
    }

    fun registerLoaded() {
        Bukkit.getWorlds().flatMap { it.entities }.filter(MobIdentity::isManaged).forEach(::register)
    }

    fun unregister(entity: Entity) {
        active.remove(entity.uniqueId)
        val previous = previousAware.remove(entity.uniqueId)
        if (previous != null) (entity as? Mob)?.isAware = previous
    }

    fun unregisterByWorld(worldId: UUID) {
        active.keys.toList().mapNotNull(Bukkit::getEntity).filter { it.world.uid == worldId }.forEach(::unregister)
        previousAware.keys.removeIf { Bukkit.getEntity(it)?.world?.uid == worldId }
    }

    fun tick(currentTick: Long) {
        active.keys.toList().forEach { id ->
            val entity = Bukkit.getEntity(id)
            if (entity == null || !entity.isValid || entity.isDead) {
                active.remove(id)
                previousAware.remove(id)
                return@forEach
            }
            val definition = MobIdentity.definitionId(entity)?.let(repository.current()::find)
            val movement = definition?.movement
            val controller = movement?.let { controllers[it.controller] }
            if (definition == null || movement == null || controller == null) {
                unregister(entity)
                return@forEach
            }
            if (currentTick % movement.updatePeriodTicks != 0L) return@forEach
            controller.tick(entity, definition, targetResolver(entity), currentTick)
        }
    }

    fun clear() {
        active.keys.toList().mapNotNull(Bukkit::getEntity).forEach(::unregister)
        active.clear()
        previousAware.clear()
    }
}
