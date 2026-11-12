package org.ewsk.spectrummobs.mob

import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.entity.LivingEntity
import org.ewsk.spectrummobs.SpectrumMobs
import org.ewsk.spectrummobs.integration.MobIntegrationRegistry
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n
import org.ewsk.spectrummobs.script.RuntimeValue
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

object MobDamageListener {
    @SubscribeEvent(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun applyDamageRules(event: EntityDamageEvent) {
        val byEntity = event as? EntityDamageByEntityEvent
        val attacker = byEntity?.damager
        val victimDefinition = MobIdentity.definitionId(event.entity)
            ?.let(SpectrumMobs.mobConfigs.current()::find)
        victimDefinition?.damageRules?.get("incoming")?.let { rule ->
            applyRule(rule.toString(), event, event.entity, attacker, "damaged")
        }
        if (attacker != null) {
            val attackerDefinition = MobIdentity.definitionId(attacker)
                ?.let(SpectrumMobs.mobConfigs.current()::find)
            attackerDefinition?.damageRules?.get("outgoing")?.let { rule ->
                applyRule(rule.toString(), event, attacker, event.entity, "attack")
            }
        }
    }

    private fun applyRule(
        ruleId: String,
        event: EntityDamageEvent,
        caster: org.bukkit.entity.Entity,
        target: org.bukkit.entity.Entity?,
        eventName: String
    ) {
        val casterValue = SpectrumMobs.skillRuntime.runtimeEntity(caster)
        val targetValue = target?.let(SpectrumMobs.skillRuntime::runtimeEntity) ?: RuntimeValue.NullValue
        val eventValue = RuntimeValue.EventValue(
            name = eventName,
            damage = event.damage,
            attacker = if (event is EntityDamageByEntityEvent) {
                event.damager.let(SpectrumMobs.skillRuntime::runtimeEntity)
            } else null,
            cause = event.cause.name
        )
        val result = runCatching {
            SpectrumMobs.skillRuntime.evaluateRule(
                ruleId,
                variables = mapOf("caster" to casterValue, "target" to targetValue, "event" to eventValue),
                arguments = mapOf(
                    "amount" to RuntimeValue.NumberValue(event.damage),
                    "cause" to RuntimeValue.TextValue(event.cause.name)
                )
            ) ?: throw IllegalArgumentException("Rule not found: $ruleId")
        }.onFailure {
            SpectrumMobsI18n.log("error-rule-evaluation", ruleId, it.message ?: it.javaClass.simpleName)
        }.getOrNull() as? RuntimeValue.NumberValue ?: return
        val multiplier = result.value
        if (!multiplier.isFinite() || multiplier < 0.0) {
            SpectrumMobsI18n.log("error-rule-evaluation", ruleId, "Rule must return a finite non-negative damage multiplier")
            return
        }
        event.damage = (event.damage * multiplier).coerceIn(0.0, Double.MAX_VALUE)
    }

    @SubscribeEvent(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageEvent) {
        val definitionId = MobIdentity.definitionId(event.entity) ?: return
        val definition = SpectrumMobs.mobConfigs.current().find(definitionId) ?: return
        val target = (event as? EntityDamageByEntityEvent)?.damager
        if (target is LivingEntity && event.finalDamage.isFinite() && event.finalDamage > 0.0) {
            SpectrumMobs.mobThreats.add(event.entity, target, event.finalDamage, SpectrumMobs.currentRuntimeTick())
        }
        val transition = (event.entity as? LivingEntity)?.let { living ->
            SpectrumMobs.mobService.updatePhase(
                living,
                (living.health - event.finalDamage).coerceAtLeast(0.0)
            )
        }
        if (transition != null) {
            SpectrumMobs.reportIntegrationFailures(
                MobIntegrationRegistry.notifyPhaseChange(
                    org.ewsk.spectrummobs.integration.MobPhaseChangeContext(
                        event.entity,
                        definition,
                        transition.previous,
                        transition.current
                    )
                )
            )
            SpectrumMobs.mobTriggers.triggerPhase(transition, event.entity, target, event.finalDamage)
        }
        SpectrumMobs.mobTriggers.trigger(
            "damaged",
            event.entity,
            target,
            event.finalDamage,
            cause = event.cause.name
        )
    }

    @SubscribeEvent(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityAttack(event: EntityDamageByEntityEvent) {
        val caster = event.damager
        val definitionId = MobIdentity.definitionId(caster) ?: return
        if (SpectrumMobs.mobConfigs.current().find(definitionId) == null) return
        if (event.entity is LivingEntity && event.finalDamage.isFinite() && event.finalDamage > 0.0) {
            SpectrumMobs.mobThreats.add(caster, event.entity, event.finalDamage, SpectrumMobs.currentRuntimeTick())
        }
        SpectrumMobs.mobTriggers.trigger(
            "attack",
            caster,
            event.entity,
            event.finalDamage,
            cause = event.cause.name
        )
    }
}
