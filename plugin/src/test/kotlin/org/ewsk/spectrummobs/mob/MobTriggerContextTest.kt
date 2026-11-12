package org.ewsk.spectrummobs.mob

import org.ewsk.spectrummobs.script.RuntimeValue
import kotlin.test.Test
import kotlin.test.assertEquals

class MobTriggerContextTest {
    @Test
    fun allTriggerKindsExposeTheSameEventShape() {
        val target = RuntimeValue.EntityValue("target-1", RuntimeValue.EntityKind.PLAYER)
        listOf("attack", "damaged", "interval", "phase_enter", "phase_exit", "manual").forEach { eventName ->
            val context = MobTriggerContext(
                eventName = eventName,
                damage = 12.5,
                target = target,
                cause = "phase",
                hand = "unknown",
                phase = "enraged",
                interval = "heartbeat",
                extraVariables = mapOf("custom" to RuntimeValue.TextValue("value"))
            )

            val variables = context.variables()
            val event = variables.getValue("event") as RuntimeValue.EventValue

            assertEquals(eventName, event.name)
            assertEquals(12.5, event.damage)
            assertEquals(target, event.attacker)
            assertEquals("phase", event.cause)
            assertEquals("unknown", event.hand)
            assertEquals(target, variables["target"])
            assertEquals(RuntimeValue.TextValue("enraged"), variables["phase"])
            assertEquals(RuntimeValue.TextValue("heartbeat"), variables["interval"])
            assertEquals(RuntimeValue.TextValue("value"), variables["custom"])
        }
    }
}
