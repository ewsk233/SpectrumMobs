package org.ewsk.spectrummobs.core

data class MobDefinition(
    val id: ResourceId,
    val entityType: String,
    val display: MobDisplay = MobDisplay("Mob"),
    val attributes: MobAttributes = MobAttributes(),
    val equipment: Map<String, EquipmentDefinition> = emptyMap(),
    val events: Map<String, List<ResourceId>> = emptyMap(),
    val intervals: List<MobIntervalTrigger> = emptyList(),
    val boss: MobBossDefinition? = null,
    val drops: List<MobDrop> = emptyList(),
    val model: MobModel? = null,
    val bossBar: MobBossBarDefinition? = null,
    val summons: MobSummonDefinition? = null,
    val eventArguments: Map<String, List<Map<String, Any?>>> = emptyMap(),
    val behavior: MobBehaviorDefinition? = null,
    val damageRules: Map<String, ResourceId> = emptyMap(),
    /** 目标计算器规则，例如 threat -> spectrum:combat.choose_target。 */
    val targetRules: Map<String, ResourceId> = emptyMap(),
    val movement: MobMovementDefinition? = null
)

data class MobDisplay(
    val name: String,
    val nameVisible: Boolean = false
)

data class MobAttributes(
    val maxHealth: Double = 20.0,
    val attackDamage: Double? = null,
    val movementSpeed: Double? = null,
    val followRange: Double? = null,
    val knockbackResistance: Double? = null
)

data class EquipmentDefinition(
    val material: String,
    val dropChance: Double = 0.0
)

data class MobBossDefinition(
    val enabled: Boolean = true,
    val phases: List<MobPhase> = emptyList()
)

data class MobBossBarDefinition(
    val enabled: Boolean = true,
    val title: String,
    val color: String = "RED",
    val style: String = "SOLID",
    val range: Double = 32.0,
    val updateIntervalTicks: Long = 1L
)

data class MobSummonDefinition(
    val maxActive: Int = 6,
    val cleanupOnDeath: Boolean = true
)

data class MobPhase(
    val id: String,
    /** 生命值低于或等于此比例时进入阶段，范围为 0.0 到 1.0。 */
    val healthThreshold: Double,
    val displayName: String? = null,
    val attributes: MobPhaseAttributes = MobPhaseAttributes(),
    val onEnter: List<ResourceId> = emptyList(),
    val onExit: List<ResourceId> = emptyList(),
    val bossbarTitle: String? = null
)

data class MobPhaseAttributes(
    val attackDamage: Double? = null,
    val movementSpeed: Double? = null,
    val knockbackResistance: Double? = null
)

data class MobDrop(
    val material: String,
    val amount: Int = 1,
    val chance: Double = 1.0,
    val rule: ResourceId? = null
)

data class MobBehaviorDefinition(
    val skill: ResourceId,
    val periodTicks: Long = 10L
)

data class MobMovementDefinition(
    val controller: String = "none",
    val hoverHeight: Double = 3.0,
    val targetDistance: Double = 8.0,
    val speed: Double = 0.25,
    val updatePeriodTicks: Long = 1L
)

data class MobModel(
    val provider: String,
    val id: String,
    val options: Map<String, String> = emptyMap()
)

data class MobIntervalTrigger(
    val id: String,
    val periodTicks: Long,
    val skills: List<ResourceId>,
    val skillArguments: List<Map<String, Any?>> = emptyList()
)
