package org.ewsk.spectrummobs.mob

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.ewsk.spectrummobs.core.MobDefinition
import org.ewsk.spectrummobs.core.MobPhase
import org.ewsk.spectrummobs.core.MobPhaseSelector

data class MobPhaseTransition(
    val previous: MobPhase,
    val current: MobPhase
)

class MobPhaseTracker {
    fun initialize(entity: Entity, definition: MobDefinition): MobPhase? {
        val boss = definition.boss ?: return null
        if (!boss.enabled || boss.phases.isEmpty()) return null
        val phase = MobPhaseSelector.select(boss.phases, 1.0) ?: return null
        MobIdentity.setPhaseId(entity, phase.id)
        return phase
    }

    fun observe(entity: Entity, definition: MobDefinition, projectedHealth: Double): MobPhaseTransition? {
        val boss = definition.boss ?: return null
        if (!boss.enabled || boss.phases.isEmpty()) return null
        val maximum = (entity as? LivingEntity)
            ?.getAttribute(Attribute.GENERIC_MAX_HEALTH)
            ?.value
            ?: return null
        if (maximum <= 0.0) return null
        val previous = MobIdentity.phaseId(entity)?.let { id -> boss.phases.firstOrNull { it.id == id } }
        val next = MobPhaseSelector.advance(
            previous,
            boss.phases,
            (projectedHealth / maximum).coerceIn(0.0, 1.0)
        ) ?: return null
        if (previous == null) {
            MobIdentity.setPhaseId(entity, next.id)
            return null
        }
        if (previous.id == next.id) return null
        MobIdentity.setPhaseId(entity, next.id)
        return MobPhaseTransition(previous, next)
    }
}
