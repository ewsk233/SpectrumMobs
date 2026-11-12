package org.ewsk.spectrummobs.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreVersionTest {
    @Test
    fun schemaVersionIsStable() {
        assertEquals(1, CoreVersion.SCHEMA)
    }
}
