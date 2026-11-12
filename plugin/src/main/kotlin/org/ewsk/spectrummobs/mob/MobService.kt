package org.ewsk.spectrummobs.mob

import org.bukkit.ChatColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EntityEquipment
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.core.EquipmentDefinition
import org.ewsk.spectrummobs.core.MobDefinition
import org.ewsk.spectrummobs.core.MobPhase
import org.ewsk.spectrummobs.integration.MobIntegrationRegistry
import org.ewsk.spectrummobs.integration.MobSpawnContext
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n
import java.util.UUID

sealed interface MobSpawnResult {
    data class Spawned(
        val entity: Entity,
        val definition: MobDefinition,
        val instanceId: UUID
    ) : MobSpawnResult

    data class Failed(val reason: String) : MobSpawnResult
}

sealed interface MobKillResult {
    data class Killed(
        val entity: Entity,
        val definitionId: String
    ) : MobKillResult

    data class Failed(val reason: String) : MobKillResult
}

class MobService(
    private val repository: MobConfigRepository,
    private val onSpawn: (Entity) -> Unit = {}
) {
    private val phaseTracker = MobPhaseTracker()

    fun spawn(id: String, location: Location, summonOwner: Entity? = null): MobSpawnResult {
        val definition = repository.current().find(id)
            ?: return MobSpawnResult.Failed("Mob definition not found: $id")
        val world = location.world ?: return MobSpawnResult.Failed("The location has no world")
        val entityType = resolveEntityType(definition.entityType)
            ?: return MobSpawnResult.Failed("Unknown entity type: ${definition.entityType}")
        if (!entityType.isSpawnable) {
            return MobSpawnResult.Failed("Entity type ${definition.entityType} cannot be spawned")
        }

        val entity = runCatching { world.spawnEntity(location, entityType) }.getOrElse {
            return MobSpawnResult.Failed("Entity spawn failed: ${it.message ?: it.javaClass.simpleName}")
        }
        val instanceId = UUID.randomUUID()
        MobIdentity.mark(entity, definition.id.toString(), instanceId)
        summonOwner?.let { MobIdentity.markSummonOwner(entity, it.uniqueId) }
        // SpectrumMobs 管理的实体不能按原版远距离刷怪规则自动消失。
        entity.isPersistent = true
        preventNaturalRemoval(entity)
        applyDefinition(entity, definition)
        phaseTracker.initialize(entity, definition)?.let { applyPhase(entity, definition, it) }
        definition.model?.let { MobIdentity.markModel(entity, it) }
        reportIntegrationFailures(
            MobIntegrationRegistry.notifySpawn(MobSpawnContext(entity, definition))
        )
        onSpawn(entity)
        return MobSpawnResult.Spawned(entity, definition, instanceId)
    }

    fun summon(owner: Entity, id: String, location: Location): MobSpawnResult {
        val ownerDefinition = findDefinition(owner)
        val maxActive = ownerDefinition?.summons?.maxActive ?: 16
        val active = summonedBy(owner.uniqueId).count { it.isValid }
        if (active >= maxActive) {
            return MobSpawnResult.Failed("Summon limit reached for ${MobIdentity.definitionId(owner) ?: owner.uniqueId}: $maxActive")
        }
        return spawn(id, location, owner)
    }

    fun summonedBy(ownerId: UUID): List<Entity> = Bukkit.getWorlds()
        .flatMap { it.entities }
        .filter { it.isValid && MobIdentity.summonOwner(it) == ownerId }

    fun cleanupSummons(owner: Entity): Int {
        val cleanup = findDefinition(owner)?.summons?.cleanupOnDeath ?: true
        if (!cleanup) return 0
        val summons = summonedBy(owner.uniqueId)
        summons.forEach(Entity::remove)
        return summons.size
    }

    fun cleanupSummonsInWorld(worldId: UUID): Int {
        val owners = Bukkit.getWorld(worldId)?.entities.orEmpty()
            .filter(MobIdentity::isManaged)
        return owners.sumOf(::cleanupSummons)
    }

    fun findDefinition(entity: Entity): MobDefinition? =
        MobIdentity.definitionId(entity)?.let(repository.current()::find)

    fun kill(id: String, uuid: String): MobKillResult {
        val definition = repository.current().find(id)
            ?: return MobKillResult.Failed("Mob definition not found: $id")
        val entityId = runCatching { UUID.fromString(uuid) }.getOrNull()
            ?: return MobKillResult.Failed("Invalid entity UUID: $uuid")
        val entity = Bukkit.getEntity(entityId)
            ?: return MobKillResult.Failed("Entity not found or not loaded: $uuid")
        val actualDefinition = MobIdentity.definitionId(entity)
        if (!actualDefinition.equals(definition.id.toString(), ignoreCase = true)) {
            return MobKillResult.Failed("Entity $uuid is not a managed mob of type ${definition.id}")
        }
        cleanupSummons(entity)
        entity.remove()
        return MobKillResult.Killed(entity, definition.id.toString())
    }

    fun killAll(): List<MobKillResult.Killed> = Bukkit.getWorlds()
        .flatMap { it.entities }
        .filter(MobIdentity::isManaged)
        .mapNotNull { entity ->
            val definitionId = MobIdentity.definitionId(entity) ?: return@mapNotNull null
            cleanupSummons(entity)
            entity.remove()
            MobKillResult.Killed(entity, definitionId)
        }

    fun updatePhase(entity: Entity, projectedHealth: Double): MobPhaseTransition? {
        val definition = findDefinition(entity) ?: return null
        val transition = phaseTracker.observe(entity, definition, projectedHealth) ?: return null
        applyPhase(entity, definition, transition.current)
        return transition
    }

    private fun applyDefinition(entity: Entity, definition: MobDefinition) {
        val living = entity as? LivingEntity ?: return
        val display = definition.display
        living.customName = ChatColor.translateAlternateColorCodes('&', display.name)
        living.isCustomNameVisible = display.nameVisible

        setAttribute(living, Attribute.GENERIC_MAX_HEALTH, definition.attributes.maxHealth)?.let {
            living.health = definition.attributes.maxHealth.coerceIn(0.0, it)
        }
        definition.attributes.attackDamage?.let { setAttribute(living, Attribute.GENERIC_ATTACK_DAMAGE, it) }
        definition.attributes.movementSpeed?.let { setAttribute(living, Attribute.GENERIC_MOVEMENT_SPEED, it) }
        definition.attributes.followRange?.let { setAttribute(living, Attribute.GENERIC_FOLLOW_RANGE, it) }
        definition.attributes.knockbackResistance?.let {
            setAttribute(living, Attribute.GENERIC_KNOCKBACK_RESISTANCE, it)
        }
        applyEquipment(living.equipment, definition.equipment)
    }

    fun applyPhase(entity: Entity, definition: MobDefinition, phase: MobPhase) {
        val living = entity as? LivingEntity ?: return
        val name = phase.displayName ?: definition.display.name
        living.customName = ChatColor.translateAlternateColorCodes('&', name)
        living.isCustomNameVisible = definition.display.nameVisible
        definition.attributes.attackDamage?.let { setAttribute(living, Attribute.GENERIC_ATTACK_DAMAGE, it) }
        definition.attributes.movementSpeed?.let { setAttribute(living, Attribute.GENERIC_MOVEMENT_SPEED, it) }
        definition.attributes.knockbackResistance?.let {
            setAttribute(living, Attribute.GENERIC_KNOCKBACK_RESISTANCE, it)
        }
        phase.attributes.attackDamage?.let { setAttribute(living, Attribute.GENERIC_ATTACK_DAMAGE, it) }
        phase.attributes.movementSpeed?.let { setAttribute(living, Attribute.GENERIC_MOVEMENT_SPEED, it) }
        phase.attributes.knockbackResistance?.let {
            setAttribute(living, Attribute.GENERIC_KNOCKBACK_RESISTANCE, it)
        }
    }

    private fun setAttribute(
        entity: LivingEntity,
        attribute: Attribute,
        value: Double
    ): Double? {
        val instance = entity.getAttribute(attribute) ?: return null
        instance.baseValue = value
        return instance.value
    }

    private fun applyEquipment(equipment: EntityEquipment?, definitions: Map<String, EquipmentDefinition>) {
        if (equipment == null) return
        definitions.forEach { (slot, definition) ->
            val material = Material.matchMaterial(definition.material) ?: return@forEach
            val item = org.bukkit.inventory.ItemStack(material)
            when (slot.lowercase()) {
                "main-hand", "mainhand" -> {
                    equipment.setItemInMainHand(item)
                    equipment.setItemInMainHandDropChance(definition.dropChance.toFloat())
                }
                "off-hand", "offhand" -> {
                    equipment.setItemInOffHand(item)
                    equipment.setItemInOffHandDropChance(definition.dropChance.toFloat())
                }
                "head", "helmet" -> {
                    equipment.helmet = item
                    equipment.helmetDropChance = definition.dropChance.toFloat()
                }
                "chest", "chestplate" -> {
                    equipment.chestplate = item
                    equipment.chestplateDropChance = definition.dropChance.toFloat()
                }
                "legs", "leggings" -> {
                    equipment.leggings = item
                    equipment.leggingsDropChance = definition.dropChance.toFloat()
                }
                "feet", "boots" -> {
                    equipment.boots = item
                    equipment.bootsDropChance = definition.dropChance.toFloat()
                }
            }
        }
    }

    private fun resolveEntityType(raw: String): EntityType? {
        val normalized = raw.trim()
            .substringAfter(':')
            .replace('-', '_')
            .replace('.', '_')
            .uppercase()
        return runCatching { EntityType.valueOf(normalized) }.getOrNull()
    }

    private fun preventNaturalRemoval(entity: Entity) {
        // v12004 的映射没有把 getRemoveWhenFarAway 暴露成 Kotlin 属性，使用 Bukkit 接口方法反射兼容 mapped/universal 两套类。
        runCatching {
            entity.javaClass.getMethod("setRemoveWhenFarAway", Boolean::class.javaPrimitiveType)
                .invoke(entity, false)
        }
    }

    private fun reportIntegrationFailures(failures: List<org.ewsk.spectrummobs.integration.MobIntegrationFailure>) {
        failures.forEach { failure ->
            SpectrumMobsI18n.log(
                "error-integration-failed",
                failure.integrationId,
                failure.error.message ?: failure.error.javaClass.simpleName
            )
        }
    }
}
