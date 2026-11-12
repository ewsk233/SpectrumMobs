package org.ewsk.spectrummobs.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MobPhaseSelectorTest {
    @Test
    fun selectsPhaseByDescendingHealthThreshold() {
        val normal = MobPhase("normal", 1.0)
        val enraged = MobPhase("enraged", 0.5)
        val critical = MobPhase("critical", 0.2)
        val phases = listOf(normal, enraged, critical)

        assertEquals("normal", MobPhaseSelector.select(phases, 0.8)?.id)
        assertEquals("enraged", MobPhaseSelector.select(phases, 0.45)?.id)
        assertEquals("critical", MobPhaseSelector.select(phases, 0.1)?.id)
        assertEquals("enraged", MobPhaseSelector.advance(enraged, phases, 0.8)?.id)
        assertEquals("critical", MobPhaseSelector.advance(enraged, phases, 0.1)?.id)
    }
}
