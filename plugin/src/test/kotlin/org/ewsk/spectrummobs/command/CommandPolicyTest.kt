package org.ewsk.spectrummobs.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPolicyTest {
    @Test
    fun loadsRawSwitchAndRegisteredTemplate() {
        val result = CommandPolicyLoader.fromValues(
            mapOf(
                "commands" to mapOf(
                    "allow-raw" to true,
                    "max-raw-length" to 128,
                    "definitions" to mapOf(
                        "reward_player" to mapOf(
                            "executor" to "console",
                            "template" to "give {target.name} diamond 1"
                        )
                    )
                )
            )
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.joinToString())
        assertTrue(result.policy.allowRaw)
        assertEquals(128, result.policy.maxRawLength)
        assertEquals("give {target.name} diamond 1", result.policy.definitions["reward_player"]?.template)
    }
}
