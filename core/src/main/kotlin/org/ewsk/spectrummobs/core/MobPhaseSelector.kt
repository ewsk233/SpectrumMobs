package org.ewsk.spectrummobs.core

/** 根据预计生命值比例选择最具体的 Boss 阶段。 */
object MobPhaseSelector {
    fun select(phases: List<MobPhase>, healthRatio: Double): MobPhase? {
        if (phases.isEmpty()) return null
        val ratio = healthRatio.coerceIn(0.0, 1.0)
        return phases.sortedBy { it.healthThreshold }
            .firstOrNull { ratio <= it.healthThreshold }
            ?: phases.maxByOrNull { it.healthThreshold }
    }

    fun advance(current: MobPhase?, phases: List<MobPhase>, healthRatio: Double): MobPhase? {
        val selected = select(phases, healthRatio) ?: return current
        return if (current == null || selected.healthThreshold < current.healthThreshold) selected else current
    }
}
