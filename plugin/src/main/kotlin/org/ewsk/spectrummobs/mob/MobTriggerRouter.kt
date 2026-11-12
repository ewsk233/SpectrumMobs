package org.ewsk.spectrummobs.mob

import org.bukkit.entity.Entity
import org.ewsk.spectrummobs.config.MobConfigRepository
import org.ewsk.spectrummobs.core.ResourceId
import org.ewsk.spectrummobs.runtime.SkillCastResult
import org.ewsk.spectrummobs.runtime.SkillRuntimeService
import org.ewsk.spectrummobs.script.RuntimeValue
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n

class MobTriggerRouter(
    private val repository: MobConfigRepository,
    private val runtime: SkillRuntimeService,
    private val traceSkills: Boolean = false
) {
    fun trigger(
        eventName: String,
        caster: Entity,
        target: Entity? = null,
        damage: Double = 0.0,
        extraVariables: Map<String, RuntimeValue> = emptyMap(),
        cause: String = "unknown",
        hand: String = "unknown"
    ): List<SkillCastResult> {
        val definitionId = MobIdentity.definitionId(caster) ?: return emptyList()
        val definition = repository.current().find(definitionId) ?: return emptyList()
        val context = MobTriggerContext(
            eventName = eventName,
            damage = damage,
            target = target?.let(runtime::runtimeEntity),
            cause = cause,
            hand = hand,
            phase = MobIdentity.phaseId(caster),
            extraVariables = extraVariables
        )
        val skills = definition.events.entries
            .filter { it.key.equals(eventName, ignoreCase = true) }
            .flatMap { it.value }
        val arguments = definition.eventArguments.entries.firstOrNull { it.key.equals(eventName, ignoreCase = true) }?.value.orEmpty()
        return triggerSkills(skills, caster, context) { index ->
            arguments.getOrNull(index).orEmpty().mapValues { (_, value) -> toRuntimeValue(value) }
        }
    }

    fun triggerPhase(
        transition: MobPhaseTransition,
        caster: Entity,
        target: Entity? = null,
        damage: Double = 0.0
    ): List<SkillCastResult> {
        runtime.cancelForPhaseChange(caster, transition.previous.id)
        val results = mutableListOf<SkillCastResult>()
        results += triggerSkills(
            transition.previous.onExit,
            caster,
            MobTriggerContext(
                eventName = "phase_exit",
                damage = damage,
                target = target?.let(runtime::runtimeEntity),
                cause = "phase",
                phase = transition.previous.id
            )
        )
        results += triggerSkills(
            transition.current.onEnter,
            caster,
            MobTriggerContext(
                eventName = "phase_enter",
                damage = damage,
                target = target?.let(runtime::runtimeEntity),
                cause = "phase",
                phase = transition.current.id
            )
        )
        return results
    }

    fun triggerSkills(
        skills: List<ResourceId>,
        caster: Entity,
        context: MobTriggerContext,
        arguments: (Int) -> Map<String, RuntimeValue> = { emptyMap() }
    ): List<SkillCastResult> {
        if (traceSkills) {
            SpectrumMobsI18n.log(
                "debug-trigger",
                context.eventName,
                caster.uniqueId,
                context.target?.id ?: "-",
                skills.size
            )
        }
        val variables = context.variables()
        return skills.mapIndexed { index, id -> runtime.cast(id.toString(), caster, variables, arguments(index)) }
    }

    internal fun toRuntimeValue(value: Any?): RuntimeValue = when (value) {
        null -> RuntimeValue.NullValue
        is Boolean -> RuntimeValue.BooleanValue(value)
        is Number -> RuntimeValue.NumberValue(value.toDouble())
        is List<*> -> RuntimeValue.ListValue(value.map(::toRuntimeValue))
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
}
