package org.ewsk.spectrummobs.mob

import org.ewsk.spectrummobs.core.MobIntervalTrigger
import org.ewsk.spectrummobs.core.ResourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID

class MobIntervalScheduleTest {
    @Test
    fun schedulesAreIndependentPerEntityAndFireOncePerTick() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val firstInterval = MobIntervalTrigger("heartbeat", 5, listOf(ResourceId.parse("spectrum:pulse")!!))
        val secondInterval = MobIntervalTrigger("heartbeat", 3, listOf(ResourceId.parse("spectrum:pulse")!!))
        val schedule = MobIntervalSchedule()
        schedule.register(first, "world", listOf(firstInterval), 0)
        schedule.register(second, "world", listOf(secondInterval), 2)

        fun active(id: UUID) = if (id == first) listOf(firstInterval) else listOf(secondInterval)

        assertEquals(emptyList(), schedule.tick(4, ::active))
        assertEquals(setOf(first, second), schedule.tick(5, ::active).map { it.entityId }.toSet())
        assertEquals(emptyList(), schedule.tick(6, ::active))
        assertEquals(listOf(second), schedule.tick(8, ::active).map { it.entityId })
        assertEquals(listOf(first), schedule.tick(10, ::active).map { it.entityId })
    }

    @Test
    fun removedEntityStopsFutureFires() {
        val entityId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val heartbeat = MobIntervalTrigger("heartbeat", 1, emptyList())
        val schedule = MobIntervalSchedule()
        schedule.register(entityId, "world", listOf(heartbeat), 0)

        assertEquals(1, schedule.tick(1) { listOf(heartbeat) }.size)
        assertEquals(emptyList(), schedule.tick(2) { null })
        assertEquals(emptyList(), schedule.tick(3) { listOf(heartbeat) })
    }
}
