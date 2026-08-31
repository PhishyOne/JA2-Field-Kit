package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaveHeaderProbeTest {
    @Test
    fun rejectsTruncatedNormalHeader() {
        val result = SaveHeaderProbe.probe(ByteArray(SaveLayoutFacts.NORMAL_HEADER_SIZE - 1))

        assertFalse(result.hasCompleteNormalHeader)
        assertEquals(null, result.rawNormalHeader)
    }

    @Test
    fun capturesExactlyTheNormalHeader() {
        val bytes = ByteArray(SaveLayoutFacts.NORMAL_HEADER_SIZE + 100) { (it and 0xff).toByte() }
        val result = SaveHeaderProbe.probe(bytes)
        val header = assertNotNull(result.rawNormalHeader)

        assertTrue(result.hasCompleteNormalHeader)
        assertEquals(SaveLayoutFacts.NORMAL_HEADER_SIZE, header.size)
        assertEquals(bytes[0], header[0])
        assertEquals(bytes[SaveLayoutFacts.NORMAL_HEADER_SIZE - 1], header.last())
    }
}
