package org.ewsk.spectrummobs.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreatTableTest {
    @Test
    fun accumulatesOrdersAndDecaysThreat() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val table = ThreatTable()
        table.add(first, 10.0, 1)
        table.add(second, 20.0, 2)
        assertEquals(second, table.top()?.targetId)
        table.decay(0.5, 3)
        assertEquals(10.0, table.amount(second))
        table.set(second, 0.0, 4)
        assertEquals(listOf(first), table.entries().map { it.targetId })
    }
}
