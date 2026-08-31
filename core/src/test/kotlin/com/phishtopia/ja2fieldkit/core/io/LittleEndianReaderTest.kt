package com.phishtopia.ja2fieldkit.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LittleEndianReaderTest {
    @Test
    fun readsUnsignedLittleEndianPrimitives() {
        val reader = LittleEndianReader(
            byteArrayOf(
                0x34,
                0x12,
                0x78,
                0x56,
                0x34,
                0x12,
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
            ),
        )

        assertEquals(0x1234, reader.u16(0))
        assertEquals(0x12345678, reader.i32(2))
        assertEquals(0xffff_ffffL, reader.u32(6))
    }

    @Test
    fun rejectsOutOfBoundsReads() {
        val reader = LittleEndianReader(byteArrayOf(1, 2, 3))

        assertFailsWith<IndexOutOfBoundsException> {
            reader.i32(0)
        }
    }
}
