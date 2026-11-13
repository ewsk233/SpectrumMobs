package org.ewsk.spectrummobs.mob

import org.bukkit.Material
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.ewsk.spectrummobs.SpectrumMobs
import org.ewsk.spectrummobs.core.MobDropRoller
import org.ewsk.spectrummobs.integration.MobDeathContext
import org.ewsk.spectrummobs.integration.MobIntegrationRegistry
import org.ewsk.spectrummobs.i18n.SpectrumMobsI18n
import org.ewsk.spectrummobs.script.RuntimeValue
import taboolib.common.platform.event.SubscribeEvent

object MobDeathListener {
    @SubscribeEvent
    fun onEntityDeath(event: EntityDeathEvent) {
        SpectrumMobs.skillRuntime.summons.onDeath(event.entity)
        if (MobIdentity.isSummonCleanup(event.entity)) return
        val definition = SpectrumMobs.mobService.findDefinition(event.entity) ?: return
        val killer = event.entity.killer
        val variables = mapOf(
            "caster" to SpectrumMobs.skillRuntime.runtimeEntity(event.entity),
            "target" to (killer?.let(SpectrumMobs.skillRuntime::runtimeEntity) ?: RuntimeValue.NullValue),
            "event" to RuntimeValue.EventValue(
                name = "death",
                attacker = killer?.let(SpectrumMobs.skillRuntime::runtimeEntity)
            )
        )
        val adjustedDrops = definition.drops.map { drop ->
            val rule = drop.rule ?: return@map drop
            val multiplier = runCatching {
                SpectrumMobs.skillRuntime.evaluateRule(
                    rule.toString(),
                    variables,
                    mapOf(
                        "amount" to RuntimeValue.NumberValue(drop.amount.toDouble()),
                        "chance" to RuntimeValue.NumberValue(drop.chance)
                    )
                ) ?: throw IllegalArgumentException("Rule not found: $rule")
            }.onFailure {
                SpectrumMobsI18n.log("error-rule-evaluation", rule, it.message ?: it.javaClass.simpleName)
            }.getOrNull() as? RuntimeValue.NumberValue
            val value = multiplier?.value ?: 1.0
            if (!value.isFinite() || value < 0.0) {
                SpectrumMobsI18n.log("error-rule-evaluation", rule, "Rule must return a finite non-negative drop chance multiplier")
                drop
            } else {
                drop.copy(chance = (drop.chance * value).coerceIn(0.0, 1.0))
            }
        }
        MobDropRoller.roll(adjustedDrops).forEach { drop ->
            val material = Material.matchMaterial(drop.material)
            if (material == null || !material.isItem) {
                SpectrumMobsI18n.log("error-drop-material", definition.id, drop.material)
                return@forEach
            }
            var remaining = drop.amount
            while (remaining > 0) {
                val amount = remaining.coerceAtMost(material.maxStackSize)
                event.drops += ItemStack(material, amount)
                remaining -= amount
            }
        }
        SpectrumMobs.reportIntegrationFailures(
            MobIntegrationRegistry.notifyDeath(MobDeathContext(event.entity, definition))
        )
        SpectrumMobs.mobTriggers.trigger("death", event.entity, event.entity.killer)
        MobIdentity.summonOwner(event.entity)?.let { ownerId ->
            val owner = org.bukkit.Bukkit.getEntity(ownerId)
            if (owner != null && owner.isValid && !owner.isDead) {
                SpectrumMobs.mobTriggers.trigger("summon_death", owner, event.entity)
            }
        }
    }
}
