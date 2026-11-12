package org.ewsk.spectrummobs.core

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MobDropRollerTest {
    @Test
    fun rollsUsingConfiguredChanceAndAmount() {
        val drops = listOf(
            MobDrop("DIAMOND", amount = 2, chance = 1.0),
            MobDrop("GOLD_INGOT", amount = 1, chance = 0.0)
        )

        assertEquals(
            listOf(RolledMobDrop("DIAMOND", 2)),
            MobDropRoller.roll(drops, Random(7))
        )
    }
}
