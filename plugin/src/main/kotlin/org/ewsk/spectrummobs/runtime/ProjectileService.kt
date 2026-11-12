package org.ewsk.spectrummobs.runtime

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.ewsk.spectrummobs.script.RuntimeValue
import java.util.UUID

/**
 * 受控的方块投射物服务。投射物只由显式脚本动作创建，并由 Bukkit 主线程 tick 驱动。
 */
class ProjectileService(
    private val maxActiveProjectiles: Int = 2048
) {
    private data class ActiveProjectile(
        val entityId: UUID,
        val ownerId: UUID?,
        val worldId: UUID,
        val speed: Double,
        val damage: Double,
        val hitRadius: Double,
        val placeOnHit: Boolean,
        val expireAt: Long,
        var lastLocation: Location,
        val executionId: UUID?,
        val homingTargetId: UUID?,
        val turnRate: Double,
        val onRemoved: (UUID) -> Unit
    )

    private val projectiles = linkedMapOf<UUID, ActiveProjectile>()
    private val projectileKey = NamespacedKey("spectrummobs", "projectile")

    init {
        require(maxActiveProjectiles > 0) { "Maximum active projectiles must be greater than 0" }
    }

    fun launch(
        owner: Entity?,
        from: RuntimeValue.PositionValue,
        to: RuntimeValue.PositionValue,
        materialName: String,
        speed: Double,
        damage: Double,
        lifetimeTicks: Long,
        hitRadius: Double,
        placeOnHit: Boolean = false,
        currentTick: Long,
        executionId: UUID? = null,
        homingTargetId: UUID? = null,
        turnRate: Double = 0.0,
        onRemoved: (UUID) -> Unit = {}
    ): UUID {
        require(projectiles.size < maxActiveProjectiles) {
            "Maximum active projectiles reached: $maxActiveProjectiles"
        }
        require(speed.isFinite() && speed > 0.0) { "Projectile speed must be finite and greater than 0" }
        require(damage.isFinite() && damage >= 0.0) { "Projectile damage must be finite and non-negative" }
        require(lifetimeTicks > 0L) { "Projectile lifetime must be greater than 0" }
        require(hitRadius.isFinite() && hitRadius > 0.0) {
            "Projectile hit radius must be finite and greater than 0"
        }
        require(from.world != null && from.world == to.world) {
            "Projectile origin and destination must be in the same world"
        }

        val world = Bukkit.getWorld(from.world!!)
            ?: throw IllegalArgumentException("Projectile world not found: ${from.world}")
        val start = Location(world, from.x, from.y, from.z)
        val destination = Vector(to.x, to.y, to.z)
        val direction = destination.subtract(start.toVector())
        require(direction.lengthSquared() > 1.0e-8) { "Projectile origin and destination must differ" }

        val material = Material.matchMaterial(materialName.trim().uppercase())
            ?: throw IllegalArgumentException("Projectile material not found: $materialName")
        require(material.isBlock) { "Projectile material must be a block: $materialName" }

        val falling = world.spawnFallingBlock(start, material.createBlockData())
        falling.setGravity(false)
        falling.setDropItem(false)
        falling.setHurtEntities(false)
        falling.velocity = direction.normalize().multiply(speed)
        falling.persistentDataContainer.set(projectileKey, PersistentDataType.BYTE, 1)

        val entityId = falling.uniqueId
        projectiles[entityId] = ActiveProjectile(
            entityId = entityId,
            ownerId = owner?.uniqueId,
            worldId = world.uid,
            speed = speed,
            damage = damage,
            hitRadius = hitRadius,
            placeOnHit = placeOnHit,
            expireAt = if (Long.MAX_VALUE - currentTick < lifetimeTicks) Long.MAX_VALUE else currentTick + lifetimeTicks,
            lastLocation = start.clone(),
            executionId = executionId,
            homingTargetId = homingTargetId,
            turnRate = turnRate.coerceIn(0.0, 1.0),
            onRemoved = onRemoved
        )
        return entityId
    }

    fun launchForward(
        owner: Entity,
        materialName: String,
        speed: Double,
        damage: Double,
        lifetimeTicks: Long,
        hitRadius: Double,
        placeOnHit: Boolean = false,
        currentTick: Long,
        executionId: UUID? = null,
        onRemoved: (UUID) -> Unit = {}
    ): UUID {
        val location = (owner as? LivingEntity)?.eyeLocation ?: owner.location
        val direction = location.direction.normalize()
        require(direction.lengthSquared() > 1.0e-8) { "Projectile caster direction is invalid" }
        val destination = location.clone().add(direction)
        return launch(
            owner = owner,
            from = RuntimeValue.PositionValue(
                location.x,
                location.y,
                location.z,
                location.world?.name
            ),
            to = RuntimeValue.PositionValue(
                destination.x,
                destination.y,
                destination.z,
                destination.world?.name
            ),
            materialName = materialName,
            speed = speed,
            damage = damage,
            lifetimeTicks = lifetimeTicks,
            hitRadius = hitRadius,
            placeOnHit = placeOnHit,
            currentTick = currentTick,
            executionId = executionId,
            onRemoved = onRemoved
        )
    }

    fun launchHoming(
        owner: Entity,
        target: Entity,
        materialName: String,
        speed: Double,
        turnRate: Double,
        damage: Double,
        lifetimeTicks: Long,
        hitRadius: Double,
        placeOnHit: Boolean = false,
        currentTick: Long,
        executionId: UUID? = null,
        onRemoved: (UUID) -> Unit = {}
    ): UUID {
        val from = (owner as? LivingEntity)?.eyeLocation ?: owner.location
        val to = target.location.clone().add(0.0, target.height * 0.5, 0.0)
        return launch(
            owner = owner,
            from = RuntimeValue.PositionValue(from.x, from.y, from.z, from.world?.name),
            to = RuntimeValue.PositionValue(to.x, to.y, to.z, to.world?.name),
            materialName = materialName,
            speed = speed,
            damage = damage,
            lifetimeTicks = lifetimeTicks,
            hitRadius = hitRadius,
            placeOnHit = placeOnHit,
            currentTick = currentTick,
            executionId = executionId,
            homingTargetId = target.uniqueId,
            turnRate = turnRate,
            onRemoved = onRemoved
        )
    }

    fun tick(currentTick: Long) {
        projectiles.values.toList().forEach { projectile ->
            if (projectiles[projectile.entityId] !== projectile) return@forEach
            if (currentTick >= projectile.expireAt) {
                remove(projectile.entityId)
                return@forEach
            }

            val entity = Bukkit.getEntity(projectile.entityId) as? FallingBlock
            if (entity == null || !entity.isValid || entity.world.uid != projectile.worldId) {
                remove(projectile.entityId)
                return@forEach
            }

            val current = entity.location
            projectile.homingTargetId?.let(Bukkit::getEntity)?.takeIf { it.isValid && !it.isDead }?.let { target ->
                val desired = target.location.clone().add(0.0, target.height * 0.5, 0.0).toVector().subtract(current.toVector())
                if (desired.lengthSquared() > 1.0e-8) {
                    val currentDirection = entity.velocity.clone().normalize()
                    val blended = currentDirection.multiply(1.0 - projectile.turnRate)
                        .add(desired.normalize().multiply(projectile.turnRate))
                    if (blended.lengthSquared() > 1.0e-8) entity.velocity = blended.normalize().multiply(projectile.speed)
                }
            }
            val previous = projectile.lastLocation
            val delta = current.toVector().subtract(previous.toVector())
            val distance = delta.length()
            val direction = if (distance > 1.0e-8) delta.clone().normalize() else null
            val entityHit = findEntityHit(entity, previous, direction, distance, projectile)
            val blockHit = if (direction != null) {
                entity.world.rayTraceBlocks(
                    previous,
                    direction,
                    distance,
                    FluidCollisionMode.NEVER,
                    true
                )
            } else {
                null
            }

            if (entityHit != null && isBeforeBlock(previous, entityHit.location, blockHit)) {
                val owner = projectile.ownerId?.let(Bukkit::getEntity)
                if (owner != null) entityHit.damage(projectile.damage, owner) else entityHit.damage(projectile.damage)
                remove(projectile.entityId)
            } else if (blockHit != null) {
                if (projectile.placeOnHit) placeBlock(entity, blockHit)
                remove(projectile.entityId)
            } else {
                projectile.lastLocation = current.clone()
            }
        }
    }

    fun removeByOwner(ownerId: UUID) {
        projectiles.values.filter { it.ownerId == ownerId }.map { it.entityId }.forEach(::remove)
    }

    fun removeByWorld(worldId: UUID) {
        projectiles.values.filter { it.worldId == worldId }.map { it.entityId }.forEach(::remove)
    }

    fun cleanupOrphans() {
        Bukkit.getWorlds()
            .flatMap(World::getEntities)
            .filter { it.persistentDataContainer.has(projectileKey, PersistentDataType.BYTE) }
            .forEach(Entity::remove)
    }

    fun clear() {
        projectiles.keys.toList().forEach(::remove)
    }

    private fun findEntityHit(
        projectile: FallingBlock,
        start: Location,
        direction: Vector?,
        distance: Double,
        active: ActiveProjectile
    ): LivingEntity? {
        val hit = if (direction != null) {
            projectile.world.rayTraceEntities(
                start,
                direction,
                distance + active.hitRadius,
                active.hitRadius
            ) { candidate ->
                candidate is LivingEntity &&
                    candidate.uniqueId != active.ownerId &&
                    candidate.uniqueId != projectile.uniqueId &&
                    candidate.isValid
            }?.hitEntity
        } else {
            projectile.world.getNearbyEntities(
                projectile.location,
                active.hitRadius,
                active.hitRadius,
                active.hitRadius
            ).firstOrNull { candidate ->
                candidate is LivingEntity &&
                    candidate.uniqueId != active.ownerId &&
                    candidate.uniqueId != projectile.uniqueId &&
                    candidate.isValid
            }
        }
        return hit as? LivingEntity
    }

    private fun isBeforeBlock(start: Location, entityLocation: Location, blockHit: org.bukkit.util.RayTraceResult?): Boolean {
        if (blockHit == null) return true
        val hitPosition = blockHit.hitPosition ?: return true
        return start.toVector().distanceSquared(entityLocation.toVector()) <=
            start.toVector().distanceSquared(hitPosition)
    }

    private fun placeBlock(projectile: FallingBlock, hit: org.bukkit.util.RayTraceResult) {
        val hitBlock = hit.hitBlock ?: return
        val face = hit.hitBlockFace ?: BlockFace.UP
        val target = hitBlock.getRelative(face)
        if (!target.type.isAir && !target.isPassable) return
        target.blockData = projectile.blockData.clone()
    }

    fun remove(entityId: UUID) {
        val projectile = projectiles.remove(entityId) ?: return
        try { Bukkit.getEntity(entityId)?.remove() } finally { projectile.onRemoved(entityId) }
    }
}
