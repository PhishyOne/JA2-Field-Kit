package com.phishtopia.ja2fieldkit.core.io

/** Bounds-checked little-endian primitive reader for save-file structures. */
class LittleEndianReader(private val data: ByteArray) {
    val size: Int
        get() = data.size

    fun u8(offset: Int): Int {
        checkRange(offset, 1)
        return data[offset].toInt() and 0xff
    }

    fun i8(offset: Int): Byte {
        checkRange(offset, 1)
        return data[offset]
    }

    fun u16(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8)

    fun i16(offset: Int): Short = u16(offset).toShort()

    fun i32(offset: Int): Int =
        u8(offset) or
            (u8(offset + 1) shl 8) or
            (u8(offset + 2) shl 16) or
            (u8(offset + 3) shl 24)

    fun u32(offset: Int): Long = i32(offset).toLong() and 0xffff_ffffL

    fun bytes(offset: Int, length: Int): ByteArray {
        checkRange(offset, length)
        return data.copyOfRange(offset, offset + length)
    }

    private fun checkRange(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > data.size || length > data.size - offset) {
            throw IndexOutOfBoundsException(
                "Read [$offset, ${offset + length}) exceeds ${data.size}-byte input",
            )
        }
    }
}
