package org.ewsk.spectrummobs.runtime

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.ewsk.spectrummobs.command.CommandDispatcher
import org.ewsk.spectrummobs.command.CommandPolicy
import org.ewsk.spectrummobs.script.ScriptApiBindings
import org.ewsk.spectrummobs.script.RuntimeEntityResolver
import org.ewsk.spectrummobs.script.RuntimeTargeting
import org.ewsk.spectrummobs.script.StandardScriptActions
import org.ewsk.spectrummobs.script.RuntimeEvaluator
import org.ewsk.spectrummobs.script.ScriptType
import org.ewsk.spectrummobs.script.RuntimeArgument
import org.ewsk.spectrummobs.script.RuntimeEvent
import org.ewsk.spectrummobs.script.RuntimeLimits
import org.ewsk.spectrummobs.script.RuntimeValue
import org.ewsk.spectrummobs.script.SkillScheduler
import org.ewsk.spectrummobs.script.SkillStartResult
import org.ewsk.spectrummobs.script.RuntimeContext
import org.ewsk.spectrummobs.script.SkillExecutionInfo
import org.ewsk.spectrummobs.mob.MobIdentity
import org.ewsk.spectrummobs.core.ConfigurationDiagnostic
import org.ewsk.spectrummobs.core.DiagnosticLevel
import org.ewsk.spectrummobs.core.MobDefinition
import org.ewsk.spectrummobs.skill.SkillFileRepository
import org.ewsk.spectrummobs.skill.SkillReloadResult
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.ThreadLocalRandom
import java.util.UUID
import org.ewsk.spectrummobs.core.ResourceId

sealed interface SkillCastResult {
    data class Started(val executionId: String) : SkillCastResult
    data class Missing(val id: String) : SkillCastResult
    data class Rejected(val reason: String) : SkillCastResult
}

data class RuntimeLifecyclePolicy(
    val cancelOnCasterDeath: Boolean = true,
    val cancelOnCasterDespawn: Boolean = true,
    val cancelOnWorldUnload: Boolean = true
)

class SkillRuntimeService(
    val repository: SkillFileRepository,
    limits: RuntimeLimits = RuntimeLimits(),
    val lifecyclePolicy: RuntimeLifecyclePolicy = RuntimeLifecyclePolicy(),
    commandPolicy: CommandPolicy = CommandPolicy(),
    private val traceSkills: Boolean = false,
    private val projectileService: ProjectileService,
    private val summonMob: (Entity, String, Location) -> Boolean,
    private val summonMobEntity: ((Entity, String, Location) -> Entity?)? = null,
    private val threatTop: (Entity) -> Entity? = { null },
    private val threatList: (Entity) -> List<Entity> = { emptyList() },
    private val threatAmount: (Entity, Entity) -> Double = { _, _ -> 0.0 },
    private val threatAdd: (Entity, Entity, Double) -> Unit = { _, _, _ -> },
    private val threatSet: (Entity, Entity, Double) -> Unit = { _, _, _ -> },
    private val threatRemove: (Entity, Entity) -> Unit = { _, _ -> },
    private val threatClear: (Entity) -> Unit = {},
    private val extendApi: ScriptApiBindings.Builder.() -> Unit = {}
) {
    private data class BossBarKey(val executionId: UUID, val playerId: UUID)
    private data class ActiveBossBar(
        val bar: BossBar,
        var expireAt: Long?,
        val healthSourceId: UUID?
    )

    private val runtimeLimits = limits
    private val scheduler = SkillScheduler(limits)
    private val commandDispatcher = CommandDispatcher(commandPolicy)
    private val bossbars = linkedMapOf<BossBarKey, ActiveBossBar>()
    @Volatile
    var scriptApi: ScriptApiBindings = createActions()
        private set
    private val actions get() = scriptApi.actions

    fun reload(): SkillReloadResult = repository.reload(scriptApi.signatures)

    /** 只能追加 API；已开始的技能继续持有原来的不可变绑定快照。 */
    fun registerApi(configure: ScriptApiBindings.Builder.() -> Unit) {
        check(Bukkit.isPrimaryThread()) { "Script API registration must run on the server thread" }
        scriptApi = scriptApi.toBuilder().apply(configure).build()
    }

    fun skillIds(): List<String> = repository.current().skills.keys.map { it.toString() }.sorted()

    fun cast(
        id: String,
        caster: Entity,
        variables: Map<String, RuntimeValue> = emptyMap(),
        arguments: Map<String, RuntimeValue> = emptyMap(),
        parent: RuntimeContext? = null,
        linkedToParent: Boolean = true
    ): SkillCastResult {
        val skill = repository.current().find(id) ?: return SkillCastResult.Missing(id).also {
            traceCast(id, caster, it)
        }
        val contextVariables = variables.toMutableMap()
        contextVariables["caster"] = toRuntimeEntity(caster)
        if ("phase" !in contextVariables) contextVariables["phase"] = RuntimeValue.TextValue(MobIdentity.phaseId(caster) ?: "default")
        val result = when (val result = scheduler.start(
            skill = skill,
            casterKey = caster.uniqueId.toString(),
            variables = contextVariables,
            actions = actions,
            worldKey = caster.world?.uid?.toString(),
            entityResolver = RuntimeEntityResolver(::resolveRuntimeEntity),
            arguments = arguments,
            ruleFunctions = ResourceId.parse(id)?.let { repository.current().rulesBySkill[it] }.orEmpty(),
            parent = parent,
            linkedToParent = linkedToParent
        )) {
            is SkillStartResult.Started -> SkillCastResult.Started(result.execution.id.toString())
            is SkillStartResult.Rejected -> SkillCastResult.Rejected(result.reason)
        }
        traceCast(id, caster, result)
        return result
    }

    fun tick(tick: Long): List<RuntimeEvent> {
        val events = scheduler.tick(tick)
        cleanupBossbars(tick)
        if (traceSkills) {
            events.forEach { event ->
                val detail = when (event) {
                    is RuntimeEvent.Cancelled -> event.reason
                    is RuntimeEvent.Failed -> event.message
                    is RuntimeEvent.EffectCleanupFailed -> "${event.effectKey}: ${event.message}"
                    else -> "-"
                }
                SpectrumMobsI18n.log("debug-skill-event", event.executionId, event::class.simpleName ?: "unknown", detail)
            }
        }
        return events
    }

    fun cancelByCaster(caster: Entity, reason: String): Int =
        scheduler.cancelByCaster(caster.uniqueId.toString(), reason).size

    fun cancelByWorld(worldKey: String, reason: String): Int =
        scheduler.cancelByWorld(worldKey, reason).size

    fun cancelAll(reason: String): Int = scheduler.cancelAll(reason).size

    fun activeSkills(): List<SkillExecutionInfo> = scheduler.executionInfos()

    fun cancelExecution(id: UUID, reason: String): Int = scheduler.cancelExecution(id, reason).size

    fun cancelForPhaseChange(caster: Entity, previousPhase: String): Int =
        scheduler.cancelForPhaseChange(caster.uniqueId.toString(), previousPhase).size

    fun clearBossBars() {
        bossbars.keys.toList().forEach(::removeBossbar)
    }

    fun clearBossBarsForSource(entityId: UUID) {
        bossbars.filterValues { it.healthSourceId == entityId }.keys.toList().forEach(::removeBossbar)
    }

    fun runtimeEntity(entity: Entity): RuntimeValue.EntityValue = toRuntimeEntity(entity)

    /** 以与技能相同的 Typed IR 和运行时预算执行一个纯规则函数。 */
    fun evaluateRule(
        id: String,
        variables: Map<String, RuntimeValue>,
        arguments: Map<String, RuntimeValue> = emptyMap()
    ): RuntimeValue? {
        val ruleName = ResourceId.parse(id)?.toString() ?: id.trim().lowercase()
        val rule = repository.current().rules[ruleName] ?: return null
        val caster = (variables["caster"] as? RuntimeValue.EntityValue)?.origin as? Entity
        val casterKey = caster?.uniqueId?.toString() ?: "rule"
        val context = RuntimeContext(
            variables = variables,
            actions = actions,
            casterKey = casterKey,
            maxCallDepth = runtimeLimits.maxCallDepth,
            maxQueryTargets = runtimeLimits.maxQueryTargets,
            worldKey = caster?.world?.uid?.toString(),
            maxStateEntries = runtimeLimits.maxStateEntries,
            entityResolver = RuntimeEntityResolver(::resolveRuntimeEntity),
            ruleFunctions = repository.current().rules,
            maxRuleSteps = runtimeLimits.maxStepsPerResume
        )
        val namedArguments = arguments.map { (name, value) -> RuntimeArgument(name, value) }
        return RuntimeEvaluator().evaluateRule(rule.qualifiedName, namedArguments, context)
    }

    /** 在怪物快照替换前校验技能绑定、规则引用和规则函数契约。 */
    fun validateMobDefinition(definition: MobDefinition): List<ConfigurationDiagnostic> {
        val diagnostics = mutableListOf<ConfigurationDiagnostic>()
        val source = definition.id.toString()
        fun validateSkill(id: ResourceId, arguments: Map<String, Any?>, path: String) {
            val skill = repository.current().skills[id]
            if (skill == null) {
                diagnostics += ConfigurationDiagnostic(source, path, "Referenced skill does not exist: $id", DiagnosticLevel.ERROR)
                return
            }
            val declared = skill.parameters.associateBy { it.name }
            arguments.keys.filterNot { it in declared }.forEach { name ->
                diagnostics += ConfigurationDiagnostic(source, "$path.args.$name", "Unknown skill argument for $id", DiagnosticLevel.ERROR)
            }
            arguments.forEach { (name, raw) ->
                val parameter = declared[name] ?: return@forEach
                val value = configRuntimeValue(raw)
                if (!matchesConfigType(value, parameter.type)) {
                    diagnostics += ConfigurationDiagnostic(
                        source,
                        "$path.args.$name",
                        "Argument type does not match ${parameter.type.displayName} for $id.$name",
                        DiagnosticLevel.ERROR
                    )
                }
            }
            skill.parameters.filter { it.defaultValue == null && it.name !in arguments }.forEach { parameter ->
                diagnostics += ConfigurationDiagnostic(
                    source,
                    "$path.args",
                    "Required skill argument is missing: $id.${parameter.name}",
                    DiagnosticLevel.ERROR
                )
            }
        }

        definition.events.forEach { (event, skills) ->
            val arguments = definition.eventArguments.entries
                .firstOrNull { it.key.equals(event, ignoreCase = true) }
                ?.value
                .orEmpty()
            skills.forEachIndexed { index, id ->
                validateSkill(id, arguments.getOrNull(index).orEmpty(), "events.$event[$index]")
            }
        }
        definition.intervals.forEach { interval ->
            interval.skills.forEachIndexed { index, id ->
                validateSkill(id, interval.skillArguments.getOrNull(index).orEmpty(), "intervals.${interval.id}.skills[$index]")
            }
        }
        definition.behavior?.let { validateSkill(it.skill, emptyMap(), "behavior.skill") }
        definition.boss?.phases.orEmpty().forEach { phase ->
            phase.onEnter.forEachIndexed { index, id -> validateSkill(id, emptyMap(), "boss.phases.${phase.id}.on-enter[$index]") }
            phase.onExit.forEachIndexed { index, id -> validateSkill(id, emptyMap(), "boss.phases.${phase.id}.on-exit[$index]") }
        }

        definition.targetRules.forEach { (name, id) ->
            validateRule(id, "target-rules.$name", ScriptType.Entity, mapOf("candidates" to ScriptType.ListType(ScriptType.Entity)), diagnostics, source)
        }
        definition.damageRules.forEach { (name, id) ->
            validateRule(id, "damage-rules.$name", ScriptType.Number, mapOf("amount" to ScriptType.Number, "cause" to ScriptType.Text), diagnostics, source)
        }
        definition.drops.forEachIndexed { index, drop ->
            drop.rule?.let { id ->
                validateRule(id, "drops[$index].rule", ScriptType.Number, mapOf("amount" to ScriptType.Number, "chance" to ScriptType.Number), diagnostics, source)
            }
        }
        return diagnostics
    }

    private fun validateRule(
        id: ResourceId,
        path: String,
        returnType: org.ewsk.spectrummobs.script.ScriptType,
        parameters: Map<String, org.ewsk.spectrummobs.script.ScriptType>,
        diagnostics: MutableList<ConfigurationDiagnostic>,
        source: String
    ) {
        val rule = repository.current().rules[id.toString()]
        if (rule == null) {
            diagnostics += ConfigurationDiagnostic(source, path, "Referenced rule does not exist: $id", DiagnosticLevel.ERROR)
            return
        }
        if (rule.returnType != returnType) {
            diagnostics += ConfigurationDiagnostic(source, path, "Rule $id must return ${returnType.displayName}", DiagnosticLevel.ERROR)
        }
        parameters.forEach { (name, type) ->
            val parameter = rule.parameters.firstOrNull { it.name == name }
            if (parameter == null || parameter.type != type) {
                diagnostics += ConfigurationDiagnostic(source, path, "Rule $id must declare $name: ${type.displayName}", DiagnosticLevel.ERROR)
            }
        }
    }

    private fun configRuntimeValue(value: Any?): RuntimeValue = when (value) {
        null -> RuntimeValue.NullValue
        is Boolean -> RuntimeValue.BooleanValue(value)
        is Number -> RuntimeValue.NumberValue(value.toDouble())
        is List<*> -> RuntimeValue.ListValue(value.map(::configRuntimeValue))
        is String -> when {
            value.matches(Regex("[0-9]+(?:\\.[0-9]+)?%")) -> RuntimeValue.PercentageValue(value.dropLast(1).toDouble())
            value.matches(Regex("[0-9]+(?:\\.[0-9]+)?[tsm]")) -> {
                val amount = value.dropLast(1).toDouble()
                val ticks = amount * when (value.last()) { 't' -> 1.0; 's' -> 20.0; else -> 1200.0 }
                RuntimeValue.DurationValue(ticks.toLong())
            }
            value.toDoubleOrNull() != null -> RuntimeValue.NumberValue(value.toDouble())
            else -> RuntimeValue.TextValue(value)
        }
        else -> RuntimeValue.TextValue(value.toString())
    }

    private fun matchesConfigType(value: RuntimeValue, type: org.ewsk.spectrummobs.script.ScriptType): Boolean = when (type) {
        org.ewsk.spectrummobs.script.ScriptType.Number -> value is RuntimeValue.NumberValue
        org.ewsk.spectrummobs.script.ScriptType.Numeric -> value is RuntimeValue.NumberValue || value is RuntimeValue.PercentageValue
        org.ewsk.spectrummobs.script.ScriptType.Text -> value is RuntimeValue.TextValue
        org.ewsk.spectrummobs.script.ScriptType.Boolean -> value is RuntimeValue.BooleanValue
        org.ewsk.spectrummobs.script.ScriptType.Duration -> value is RuntimeValue.DurationValue
        org.ewsk.spectrummobs.script.ScriptType.Percentage -> value is RuntimeValue.PercentageValue
        org.ewsk.spectrummobs.script.ScriptType.Entity, org.ewsk.spectrummobs.script.ScriptType.Player -> value == RuntimeValue.NullValue
        is org.ewsk.spectrummobs.script.ScriptType.ListType -> value is RuntimeValue.ListValue && value.values.all { matchesConfigType(it, type.elementType) }
        is org.ewsk.spectrummobs.script.ScriptType.TypeParameter -> matchesConfigType(value, type.bound)
        else -> false
    }

    private fun traceCast(id: String, caster: Entity, result: SkillCastResult) {
        if (!traceSkills) return
        val outcome = when (result) {
            is SkillCastResult.Started -> "started:${result.executionId}"
            is SkillCastResult.Missing -> "missing"
            is SkillCastResult.Rejected -> "rejected:${result.reason}"
        }
        SpectrumMobsI18n.log("debug-skill-cast", id, caster.uniqueId, outcome)
    }

    private fun createActions(): ScriptApiBindings = ScriptApiBindings.Builder()
        .apply { StandardScriptActions.bind(this) }
        .bind("targeter.players.within") { context, arguments -> nearbyEntities(context, arguments, playersOnly = true) }
        .bind("targeter.entities.within") { context, arguments -> nearbyEntities(context, arguments) }
        .bind("targeter.entities.nearest") { context, arguments ->
            (nearbyEntities(context, arguments, excludeCaster = true) as RuntimeValue.ListValue).values.firstOrNull() ?: RuntimeValue.NullValue
        }
        .bind("targeter.entities.tagged") { context, arguments -> nearbyTaggedEntities(context, arguments) }
        .bind("targeter.threat.top") { context, _ ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
            owner?.let(threatTop)?.let(::toRuntimeEntity) ?: RuntimeValue.NullValue
        }
        .bind("targeter.threat.list") { context, _ ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
            RuntimeValue.ListValue(owner?.let(threatList).orEmpty().map(::toRuntimeEntity))
        }
        .bind("condition.threat.above") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
            val target = (argument(arguments, "entity", 0) as? RuntimeValue.EntityValue)?.origin as? Entity
            val amount = argument(arguments, "amount", 1).asNumber()
            RuntimeValue.BooleanValue(owner != null && target != null && threatAmount(owner, target) > amount)
        }
        .bind("condition.threat.isTop") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
            val target = (argument(arguments, "entity", 0) as? RuntimeValue.EntityValue)?.origin as? Entity
            RuntimeValue.BooleanValue(owner != null && target != null && threatTop(owner)?.uniqueId == target.uniqueId)
        }
        .bind("condition.hasTag") { _, arguments ->
            val entity = (argument(arguments, "entity", 0) as? RuntimeValue.EntityValue)?.origin as? Entity
            val tag = argument(arguments, "tag", 1).asText()
            RuntimeValue.BooleanValue(entity?.scoreboardTags?.contains(tag) == true)
        }
        .bind("mechanic.projectile.launch") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Projectile caster is not a valid entity")
            val projectileId = projectileService.launch(
                owner = owner,
                from = argument(arguments, "from", 0).asPosition(),
                to = argument(arguments, "to", 1).asPosition(),
                materialName = argument(arguments, "material", 2).asText(),
                speed = argument(arguments, "speed", 3).asNumber(),
                damage = argument(arguments, "damage", 4).asNumber(),
                lifetimeTicks = argument(arguments, "lifetime", 5).asDurationTicks(),
                hitRadius = argument(arguments, "hitRadius", 6).asNumber(),
                placeOnHit = optionalArgument(arguments, "placeOnHit", 7)?.asBoolean() ?: false,
                currentTick = context.currentTick,
                executionId = context.executionId,
                onRemoved = { id -> context.effects.release(context.executionId, "projectile:$id") }
            )
            context.effects.register(context.executionId, "projectile:$projectileId") { projectileService.remove(projectileId) }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.projectile.forward") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Projectile caster is not a valid entity")
            val projectileId = projectileService.launchForward(
                owner = owner,
                materialName = argument(arguments, "material", 0).asText(),
                speed = argument(arguments, "speed", 1).asNumber(),
                damage = argument(arguments, "damage", 2).asNumber(),
                lifetimeTicks = argument(arguments, "lifetime", 3).asDurationTicks(),
                hitRadius = argument(arguments, "hitRadius", 4).asNumber(),
                placeOnHit = optionalArgument(arguments, "placeOnHit", 5)?.asBoolean() ?: false,
                currentTick = context.currentTick,
                executionId = context.executionId,
                onRemoved = { id -> context.effects.release(context.executionId, "projectile:$id") }
            )
            context.effects.register(context.executionId, "projectile:$projectileId") { projectileService.remove(projectileId) }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.projectile.homing") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Projectile caster is not a valid entity")
            val target = argument(arguments, "target", 0).asEntity()
            val projectileId = projectileService.launchHoming(
                owner = owner,
                target = target,
                materialName = argument(arguments, "material", 1).asText(),
                speed = argument(arguments, "speed", 2).asNumber(),
                turnRate = argument(arguments, "turnRate", 3).asNumber(),
                damage = argument(arguments, "damage", 4).asNumber(),
                lifetimeTicks = argument(arguments, "lifetime", 5).asDurationTicks(),
                hitRadius = argument(arguments, "hitRadius", 6).asNumber(),
                placeOnHit = optionalArgument(arguments, "placeOnHit", 7)?.asBoolean() ?: false,
                currentTick = context.currentTick,
                executionId = context.executionId,
                onRemoved = { id -> context.effects.release(context.executionId, "projectile:$id") }
            )
            context.effects.register(context.executionId, "projectile:$projectileId") { projectileService.remove(projectileId) }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.mob.summon") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Summon caster is not a valid entity")
            val id = argument(arguments, "id", 0).asText()
            val center = argument(arguments, "at", 1).asPosition()
            val count = (optionalArgument(arguments, "count", 2)?.asNumber()?.toInt() ?: 1).coerceIn(1, 16)
            val tag = optionalArgument(arguments, "tag", 3)?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            val ignoreRejected = optionalArgument(arguments, "ignoreRejected", 4)?.asBoolean() ?: false
            val world = center.world?.let(Bukkit::getWorld)
                ?: throw IllegalStateException("World not found: ${center.world}")
            var spawned = 0
            repeat(count) { index ->
                val angle = if (count == 1) 0.0 else Math.PI * 2.0 * index / count
                val location = Location(
                    world,
                    center.x + cos(angle) * 1.5,
                    center.y,
                    center.z + sin(angle) * 1.5
                )
                val summoned = summonMobEntity?.invoke(owner, id, location)
                if (summoned != null) {
                    tag?.let(summoned::addScoreboardTag)
                    spawned++
                } else if (summonMob(owner, id, location)) {
                    spawned++
                }
            }
            if (spawned == 0 && !ignoreRejected) throw IllegalArgumentException("Unable to summon mob: $id")
            RuntimeValue.NumberValue(spawned.toDouble())
        }
        .bind("mechanic.threat.add") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Threat owner is not a valid entity")
            val target = argument(arguments, "entity", 0).asEntity()
            threatAdd(owner, target, argument(arguments, "amount", 1).asNumber())
            RuntimeValue.UnitValue
        }
        .bind("mechanic.threat.set") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Threat owner is not a valid entity")
            val target = argument(arguments, "entity", 0).asEntity()
            threatSet(owner, target, argument(arguments, "amount", 1).asNumber())
            RuntimeValue.UnitValue
        }
        .bind("mechanic.threat.remove") { context, arguments ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Threat owner is not a valid entity")
            threatRemove(owner, argument(arguments, "entity", 0).asEntity())
            RuntimeValue.UnitValue
        }
        .bind("mechanic.threat.clear") { context, _ ->
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
                ?: throw IllegalArgumentException("Threat owner is not a valid entity")
            threatClear(owner)
            RuntimeValue.UnitValue
        }
        .bind("mechanic.particles.circle") { _, arguments ->
            val center = argument(arguments, "at", 0).asPosition()
            val particle = argument(arguments, "particle", 1).asParticle()
            val radius = argument(arguments, "radius", 2).asNumber()
            val count = argument(arguments, "count", 3).asNumber().toInt().coerceAtLeast(1)
            val world = center.world?.let(Bukkit::getWorld)
                ?: throw IllegalStateException("World not found: ${center.world}")
            repeat(count) { index ->
                val angle = Math.PI * 2.0 * index / count
                world.spawnParticle(
                    particle,
                    center.x + cos(angle) * radius,
                    center.y,
                    center.z + sin(angle) * radius,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0
                )
            }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.particles.line") { _, arguments ->
            val from = argument(arguments, "from", 0).asPosition()
            val to = argument(arguments, "to", 1).asPosition()
            val particle = argument(arguments, "particle", 2).asParticle()
            val step = argument(arguments, "step", 3).asNumber()
            require(step.isFinite() && step > 0.0) { "Particle line step must be finite and greater than 0" }
            require(from.world != null && from.world == to.world) { "Particle line points must be in the same world" }
            val world = from.world?.let(Bukkit::getWorld) ?: throw IllegalStateException("World not found: ${from.world}")
            val start = Vector(from.x, from.y, from.z)
            val delta = Vector(to.x, to.y, to.z).subtract(start)
            val length = delta.length()
            val direction = if (length > 1.0e-8) delta.clone().normalize() else Vector()
            val points = (length / step).toInt().coerceAtMost(512)
            repeat(points + 1) { index ->
                val point = start.clone().add(direction.clone().multiply((index * step).coerceAtMost(length)))
                world.spawnParticle(particle, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
            }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.ray.damage") { context, arguments ->
            val from = argument(arguments, "from", 0).asPosition()
            val to = argument(arguments, "to", 1).asPosition()
            val amount = argument(arguments, "damage", 2).asNumber()
            val radius = argument(arguments, "radius", 3).asNumber()
            require(amount.isFinite() && amount >= 0.0) { "Ray damage must be finite and non-negative" }
            require(radius.isFinite() && radius >= 0.0) { "Ray radius must be finite and non-negative" }
            require(from.world != null && from.world == to.world) { "Ray points must be in the same world" }
            val world = from.world?.let(Bukkit::getWorld) ?: throw IllegalStateException("World not found: ${from.world}")
            val start = Vector(from.x, from.y, from.z)
            val end = Vector(to.x, to.y, to.z)
            val owner = (context.caster as? RuntimeValue.EntityValue)?.origin as? Entity
            val minX = minOf(start.x, end.x) - radius
            val minY = minOf(start.y, end.y) - radius
            val minZ = minOf(start.z, end.z) - radius
            val maxX = maxOf(start.x, end.x) + radius
            val maxY = maxOf(start.y, end.y) + radius
            val maxZ = maxOf(start.z, end.z) + radius
            val candidates = world.getNearbyEntities(
                Location(world, (minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5),
                (maxX - minX) * 0.5,
                (maxY - minY) * 0.5,
                (maxZ - minZ) * 0.5
            )
            candidates.asSequence()
                .filterIsInstance<LivingEntity>()
                .filter { isAvailable(it) && it.uniqueId != owner?.uniqueId }
                .filter { distanceSquaredToSegment(it.location.toVector(), start, end) <= radius * radius }
                .forEach { it.damage(amount, owner) }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.damage") { _, arguments ->
            val target = argument(arguments, "target", 0).asEntity()
            val amount = argument(arguments, "amount", 1).asNumber()
            (target as? LivingEntity)?.damage(amount)
                ?: throw IllegalArgumentException("Target entity cannot receive damage")
            RuntimeValue.UnitValue
        }
        .bind("mechanic.ignite") { _, arguments ->
            val target = argument(arguments, "target", 0).asEntity()
            val duration = argument(arguments, "duration", 1).asDurationTicks()
            target.fireTicks = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.setHealth") { _, arguments ->
            val entity = argument(arguments, "entity", 0).asEntity()
            val living = entity as? LivingEntity ?: throw IllegalArgumentException("Target entity cannot have its health set")
            val amount = argument(arguments, "amount", 1).asNumber()
            val max = living.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: amount
            living.health = amount.coerceIn(0.0, max)
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.teleport") { _, arguments ->
            val entity = argument(arguments, "entity", 0).asEntity()
            val position = argument(arguments, "position", 1).asPosition()
            val world = position.world?.let(Bukkit::getWorld)
                ?: throw IllegalArgumentException("Teleport destination world not found: ${position.world}")
            check(entity.teleport(Location(world, position.x, position.y, position.z))) { "Entity teleport failed" }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.velocity") { _, arguments ->
            val entity = argument(arguments, "entity", 0).asEntity()
            val x = argument(arguments, "x", 1).asNumber()
            val y = argument(arguments, "y", 2).asNumber()
            val z = argument(arguments, "z", 3).asNumber()
            entity.velocity = Vector(x, y, z)
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.remove") { _, arguments ->
            argument(arguments, "entity", 0).asEntity().remove()
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.addTag") { _, arguments ->
            argument(arguments, "entity", 0).asEntity().addScoreboardTag(argument(arguments, "tag", 1).asText())
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.removeTag") { _, arguments ->
            argument(arguments, "entity", 0).asEntity().removeScoreboardTag(argument(arguments, "tag", 1).asText())
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.pull") { _, arguments ->
            val entity = argument(arguments, "entity", 0).asEntity()
            val center = argument(arguments, "center", 1).asPosition()
            val strength = argument(arguments, "strength", 2).asNumber()
            require(strength.isFinite() && strength >= 0.0) { "Pull strength must be finite and non-negative" }
            val world = center.world?.let(Bukkit::getWorld) ?: throw IllegalStateException("World not found: ${center.world}")
            require(world.uid == entity.world.uid) { "Pull center and entity must be in the same world" }
            val direction = Vector(center.x, center.y, center.z).subtract(entity.location.toVector())
            if (direction.lengthSquared() > 1.0e-8) entity.velocity = entity.velocity.add(direction.normalize().multiply(strength))
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.push") { _, arguments ->
            val entity = argument(arguments, "entity", 0).asEntity()
            val center = argument(arguments, "center", 1).asPosition()
            val strength = argument(arguments, "strength", 2).asNumber()
            require(strength.isFinite() && strength >= 0.0) { "Push strength must be finite and non-negative" }
            val world = center.world?.let(Bukkit::getWorld) ?: throw IllegalStateException("World not found: ${center.world}")
            require(world.uid == entity.world.uid) { "Push center and entity must be in the same world" }
            val direction = entity.location.toVector().subtract(Vector(center.x, center.y, center.z))
            if (direction.lengthSquared() > 1.0e-8) entity.velocity = entity.velocity.add(direction.normalize().multiply(strength))
            RuntimeValue.UnitValue
        }
        .bind("mechanic.entity.potion") { _, arguments ->
            val entity = argument(arguments, "entity", 0).asEntity()
            val living = entity as? LivingEntity ?: throw IllegalArgumentException("Target entity cannot receive potion effects")
            val effect = potionType(argument(arguments, "effect", 1).asText())
            val duration = argument(arguments, "duration", 2).asDurationTicks().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val amplifier = argument(arguments, "amplifier", 3).asNumber().toInt().coerceAtLeast(0)
            living.addPotionEffect(PotionEffect(effect, duration, amplifier))
            RuntimeValue.UnitValue
        }
        .bind("condition.hasPotion") { _, arguments ->
            val entity = (argument(arguments, "entity", 0) as? RuntimeValue.EntityValue)?.origin as? Entity
            val living = entity?.takeIf(::isAvailable) as? LivingEntity ?: return@bind RuntimeValue.BooleanValue(false)
            RuntimeValue.BooleanValue(living.hasPotionEffect(potionType(argument(arguments, "effect", 1).asText())))
        }
        .bind("mechanic.bossbar.show") { context, arguments ->
            val player = argument(arguments, "player", 0).asPlayer()
            val title = legacy(argument(arguments, "title", 1).asText())
            val progressValue = argument(arguments, "progress", 2)
            val progress = progressValue.asProgress()
            val duration = argument(arguments, "duration", 3).asDurationTicks().coerceAtLeast(0L)
            val healthSourceId = if (progressValue is RuntimeValue.PercentageValue) {
                (context.caster as? RuntimeValue.EntityValue)?.origin
                    ?.let { it as? LivingEntity }
                    ?.uniqueId
            } else {
                null
            }
            val key = BossBarKey(context.executionId, player.uniqueId)
            val active = bossbars[key]
            val bar = active?.bar ?: Bukkit.createBossBar(title, BarColor.BLUE, BarStyle.SOLID)
            bar.setTitle(title)
            bar.progress = progress
            bar.addPlayer(player)
            bossbars[key] = ActiveBossBar(
                bar = bar,
                expireAt = if (duration > 0L) {
                    if (Long.MAX_VALUE - context.currentTick < duration) Long.MAX_VALUE else context.currentTick + duration
                } else null,
                healthSourceId = healthSourceId
            )
            if (active == null) context.effects.register(context.executionId, "bossbar:${player.uniqueId}") { removeBossbar(key) }
            RuntimeValue.UnitValue
        }
        .bind("mechanic.scoreboard.set") { _, arguments ->
            val player = argument(arguments, "player", 0).asPlayer()
            val objectiveId = argument(arguments, "objective", 1).asText()
            val score = argument(arguments, "score", 2).asNumber().toInt()
            val scoreboard = player.scoreboard
            val objective = scoreboard.getObjective(objectiveId)
                ?: scoreboard.registerNewObjective(objectiveId, "dummy", objectiveId)
            objective.getScore(player.name).score = score
            RuntimeValue.UnitValue
        }
        .bind("mechanic.message.send") { _, arguments ->
            val player = argument(arguments, "player", 0).asPlayer()
            player.sendMessage(legacy(SpectrumMobsI18n.skillText(player, argument(arguments, "message", 1).asText())))
            RuntimeValue.UnitValue
        }
        .bind("mechanic.title.show") { _, arguments ->
            val player = argument(arguments, "player", 0).asPlayer()
            val title = legacy(SpectrumMobsI18n.skillText(player, argument(arguments, "title", 1).asText()))
            val subtitle = legacy(SpectrumMobsI18n.skillText(player, argument(arguments, "subtitle", 2).asText()))
            val fadeIn = argument(arguments, "fadeIn", 3).asDurationTicks().asIntTicks()
            val stay = argument(arguments, "stay", 4).asDurationTicks().asIntTicks()
            val fadeOut = argument(arguments, "fadeOut", 5).asDurationTicks().asIntTicks()
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
            RuntimeValue.UnitValue
        }
        .bind("mechanic.actionbar.show") { _, arguments ->
            val player = argument(arguments, "player", 0).asPlayer()
            taboolib.platform.type.BukkitPlayer(player)
                .sendActionBar(legacy(SpectrumMobsI18n.skillText(player, argument(arguments, "message", 1).asText())))
            RuntimeValue.UnitValue
        }
        .bind("mechanic.sound.play") { _, arguments ->
            val player = argument(arguments, "player", 0).asPlayer()
            val sound = argument(arguments, "sound", 1).asSound()
            val volume = argument(arguments, "volume", 2).asNumber().coerceAtLeast(0.0).toFloat()
            val pitch = argument(arguments, "pitch", 3).asNumber().coerceIn(0.0, 2.0).toFloat()
            player.playSound(player.location, sound, volume, pitch)
            RuntimeValue.UnitValue
        }
        .bind("random.chance") { _, arguments ->
            val chance = argument(arguments, "chance", 0).asPercentage().coerceIn(0.0, 100.0)
            RuntimeValue.BooleanValue(ThreadLocalRandom.current().nextDouble(0.0, 100.0) < chance)
        }
        .bind("random.number") { _, arguments ->
            val minimum = argument(arguments, "minimum", 0).asNumber()
            val maximum = argument(arguments, "maximum", 1).asNumber()
            require(minimum.isFinite() && maximum.isFinite()) { "random.number bounds must be finite" }
            require(minimum <= maximum) { "random.number minimum cannot exceed maximum" }
            val value = if (minimum == maximum) minimum else ThreadLocalRandom.current().nextDouble(minimum, maximum)
            RuntimeValue.NumberValue(value)
        }
        .bind("state.get") { context, arguments ->
            val key = argument(arguments, "key", 0).asText()
            RuntimeValue.NumberValue((context.stateValue(key) as? RuntimeValue.NumberValue)?.value ?: 0.0)
        }
        .bind("state.set") { context, arguments ->
            val key = argument(arguments, "key", 0).asText()
            val value = argument(arguments, "value", 1).asNumber()
            context.setStateValue(key, RuntimeValue.NumberValue(value))
            RuntimeValue.UnitValue
        }
        .bind("state.increment") { context, arguments ->
            val key = argument(arguments, "key", 0).asText()
            val amount = argument(arguments, "amount", 1).asNumber()
            val value = ((context.stateValue(key) as? RuntimeValue.NumberValue)?.value ?: 0.0) + amount
            context.setStateValue(key, RuntimeValue.NumberValue(value))
            RuntimeValue.NumberValue(value)
        }
        .bind("skills.cast") { context, arguments ->
            val id = argument(arguments, "id", 0).asText()
            val caster = argument(arguments, "caster", 1).asEntity()
            val target = argument(arguments, "target", 2).asEntity()
            val linked = optionalArgument(arguments, "linked", 3)?.asBoolean() ?: true
            val ignoreRejected = optionalArgument(arguments, "ignoreRejected", 4)?.asBoolean() ?: false
            val skillArguments = arguments.filter { it.name !in setOf("id", "caster", "target", "linked", "ignoreRejected") }
                .filter { it.name != null }
                .associate { it.name!! to it.value }
            val inherited = mapOf(
                "target" to toRuntimeEntity(target), "event" to context.event,
                "phase" to RuntimeValue.TextValue(MobIdentity.phaseId(caster) ?: "default"),
                "interval" to (context.variables["interval"] ?: RuntimeValue.TextValue("default"))
            )
            when (val result = cast(id, caster, inherited, skillArguments, parent = context, linkedToParent = linked)) {
                is SkillCastResult.Started -> RuntimeValue.TextValue(result.executionId)
                is SkillCastResult.Missing -> throw IllegalArgumentException("Skill not found: ${result.id}")
                is SkillCastResult.Rejected -> if (ignoreRejected) RuntimeValue.TextValue("") else throw IllegalArgumentException("Skill $id was rejected: ${result.reason}")
            }
        }
        .bind("command.raw") { _, arguments ->
            commandDispatcher.raw(argument(arguments, "command", 0).asText())
            RuntimeValue.UnitValue
        }
        .bind("command.run") { _, arguments ->
            commandDispatcher.run(
                argument(arguments, "id", 0).asText(),
                argument(arguments, "target", 1).asEntity()
            )
            RuntimeValue.UnitValue
        }
        .bind("variables.get") { context, arguments ->
            val scope = argument(arguments, "scope", 0).asText()
            val key = argument(arguments, "key", 1).asText()
            RuntimeValue.NumberValue((context.scopedValue(scope, key) as? RuntimeValue.NumberValue)?.value ?: 0.0)
        }
        .bind("variables.set") { context, arguments ->
            val scope = argument(arguments, "scope", 0).asText()
            val key = argument(arguments, "key", 1).asText()
            context.setScopedValue(scope, key, RuntimeValue.NumberValue(argument(arguments, "value", 2).asNumber()))
            RuntimeValue.UnitValue
        }
        .bind("variables.increment") { context, arguments ->
            val scope = argument(arguments, "scope", 0).asText()
            val key = argument(arguments, "key", 1).asText()
            val amount = argument(arguments, "amount", 2).asNumber()
            val value = ((context.scopedValue(scope, key) as? RuntimeValue.NumberValue)?.value ?: 0.0) + amount
            context.setScopedValue(scope, key, RuntimeValue.NumberValue(value))
            RuntimeValue.NumberValue(value)
        }
        .bind("variables.has") { context, arguments ->
            RuntimeValue.BooleanValue(
                context.hasScopedValue(
                    argument(arguments, "scope", 0).asText(),
                    argument(arguments, "key", 1).asText()
                )
            )
        }
        .apply(extendApi)
        .build()

    private fun argument(arguments: List<RuntimeArgument>, name: String, index: Int): RuntimeValue =
        arguments.firstOrNull { it.name == name }?.value ?: arguments.filter { it.name == null }.getOrNull(index)?.value
            ?: throw IllegalArgumentException("Missing runtime argument: $name")

    private fun optionalArgument(arguments: List<RuntimeArgument>, name: String, index: Int = -1): RuntimeValue? =
        arguments.firstOrNull { it.name == name }?.value
            ?: arguments.filter { it.name == null }.getOrNull(index)?.value

    private fun nearbyEntities(
        context: org.ewsk.spectrummobs.script.RuntimeContext,
        arguments: List<RuntimeArgument>,
        playersOnly: Boolean = false,
        excludeCaster: Boolean = false
    ): RuntimeValue {
        val center = argument(arguments, "center", 0).asPosition()
        val radius = argument(arguments, "radius", 1).asNumber()
        require(radius.isFinite() && radius >= 0.0) { "Target query radius must be finite and non-negative" }
        val world = center.world?.let(Bukkit::getWorld)
            ?: throw IllegalStateException("World not found: ${center.world}")
        val origin = Location(world, center.x, center.y, center.z)
        val candidates = world.getNearbyEntities(origin, radius, radius, radius).asSequence()
            .filter { isAvailable(it) && (!playersOnly || it is Player) }
            .filter { !excludeCaster || it.uniqueId.toString() != context.casterKey }
            .map(::toRuntimeEntity)
        return RuntimeValue.ListValue(RuntimeTargeting.within(candidates, center, radius, context.maxQueryTargets))
    }

    private fun nearbyTaggedEntities(
        context: org.ewsk.spectrummobs.script.RuntimeContext,
        arguments: List<RuntimeArgument>
    ): RuntimeValue {
        val center = argument(arguments, "center", 0).asPosition()
        val radius = argument(arguments, "radius", 1).asNumber()
        val tag = argument(arguments, "tag", 2).asText()
        require(radius.isFinite() && radius >= 0.0) { "Target query radius must be finite and non-negative" }
        val world = center.world?.let(Bukkit::getWorld) ?: throw IllegalStateException("World not found: ${center.world}")
        val origin = Location(world, center.x, center.y, center.z)
        val candidates = world.getNearbyEntities(origin, radius, radius, radius).asSequence()
            .filter { isAvailable(it) && it.scoreboardTags.contains(tag) }
            .map(::toRuntimeEntity)
        return RuntimeValue.ListValue(RuntimeTargeting.within(candidates, center, radius, context.maxQueryTargets))
    }

    private fun distanceSquaredToSegment(point: Vector, start: Vector, end: Vector): Double {
        val segment = end.clone().subtract(start)
        val lengthSquared = segment.lengthSquared()
        if (lengthSquared <= 1.0e-8) return point.distanceSquared(start)
        val projection = point.clone().subtract(start).dot(segment) / lengthSquared
        val clamped = projection.coerceIn(0.0, 1.0)
        val closest = start.clone().add(segment.multiply(clamped))
        return point.distanceSquared(closest)
    }

    private fun isAvailable(entity: Entity): Boolean = entity.isValid && !entity.isDead && (entity !is Player || entity.isOnline)

    private fun resolveRuntimeEntity(value: RuntimeValue.EntityValue): RuntimeValue.EntityValue? {
        val entity = value.origin as? Entity ?: return null
        return if (isAvailable(entity)) toRuntimeEntity(entity) else null
    }

    private fun toRuntimeEntity(entity: Entity): RuntimeValue.EntityValue {
        val living = entity as? LivingEntity
        val location = entity.location
        val worldName = location.world?.name
            ?: throw IllegalArgumentException("Entity has no world")
        return RuntimeValue.EntityValue(
            id = entity.uniqueId.toString(),
            kind = if (entity is Player) RuntimeValue.EntityKind.PLAYER else RuntimeValue.EntityKind.MOB,
            currentHealth = living?.health,
            maxHealth = living?.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value,
            position = RuntimeValue.PositionValue(location.x, location.y, location.z, worldName),
            origin = entity
        )
    }

    private fun RuntimeValue.asEntity(): Entity =
        ((this as? RuntimeValue.EntityValue)?.origin as? Entity)?.takeIf(::isAvailable)
            ?: throw IllegalArgumentException("Runtime value is not a valid entity")

    private fun RuntimeValue.asPlayer(): Player =
        ((this as? RuntimeValue.EntityValue)?.origin as? Player)?.takeIf(::isAvailable)
            ?: throw IllegalArgumentException("Runtime value is not a valid player")

    private fun RuntimeValue.asText(): String =
        (this as? RuntimeValue.TextValue)?.value
            ?: throw IllegalArgumentException("Runtime value is not text")

    private fun RuntimeValue.asBoolean(): Boolean =
        (this as? RuntimeValue.BooleanValue)?.value
            ?: throw IllegalArgumentException("Runtime value is not Boolean")

    private fun RuntimeValue.asPosition(): RuntimeValue.PositionValue =
        this as? RuntimeValue.PositionValue
            ?: throw IllegalArgumentException("Runtime value is not a position")

    private fun RuntimeValue.asNumber(): Double =
        when (this) {
            is RuntimeValue.NumberValue -> value
            is RuntimeValue.PercentageValue -> value
            else -> throw IllegalArgumentException("Runtime value is not a number")
        }

    private fun RuntimeValue.asParticle(): Particle =
        Particle.valueOf((this as? RuntimeValue.ParticleValue)?.name?.uppercase() ?: "")

    private fun RuntimeValue.asDurationTicks(): Long =
        (this as? RuntimeValue.DurationValue)?.ticks
            ?: throw IllegalArgumentException("Runtime value is not a duration")

    private fun RuntimeValue.asPercentage(): Double =
        (this as? RuntimeValue.PercentageValue)?.value
            ?: throw IllegalArgumentException("Runtime value is not a percentage")

    private fun RuntimeValue.asProgress(): Double = when (this) {
        is RuntimeValue.NumberValue -> value
        is RuntimeValue.PercentageValue -> value / 100.0
        else -> throw IllegalArgumentException("Runtime value is not a progress value")
    }.coerceIn(0.0, 1.0)

    private fun cleanupBossbars(currentTick: Long) {
        bossbars.entries.toList().forEach { (key, active) ->
            active.healthSourceId?.let { sourceId ->
                val source = Bukkit.getEntity(sourceId) as? LivingEntity
                val maximum = source?.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value
                if (source != null && maximum != null && maximum > 0.0) {
                    active.bar.progress = (source.health / maximum).coerceIn(0.0, 1.0)
                }
            }
            val expired = active.expireAt?.let { currentTick >= it } == true
            val offline = Bukkit.getPlayer(key.playerId) == null
            if (expired || offline) removeBossbar(key)
        }
    }

    private fun removeBossbar(key: BossBarKey) {
        val active = bossbars.remove(key) ?: return
        try { active.bar.removeAll() } finally { scheduler.effects.release(key.executionId, "bossbar:${key.playerId}") }
    }

    private fun RuntimeValue.asSound(): Sound {
        val raw = asText()
            .substringAfter(':')
            .replace('.', '_')
            .replace('-', '_')
            .uppercase()
        return runCatching { Sound.valueOf(raw) }
            .getOrElse { throw IllegalArgumentException("Sound not found: $raw") }
    }

    private fun Long.asIntTicks(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun legacy(text: String): String = ChatColor.translateAlternateColorCodes('&', text)

    private fun potionType(raw: String): PotionEffectType =
        PotionEffectType.getByName(raw.substringAfter(':').replace('-', '_').uppercase())
            ?: throw IllegalArgumentException("Potion effect not found: $raw")
}
