package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader

/** Writer recurrence; the existing roster reader independently checks serialized integrity. */
internal object NormalSoldierChecksum {
    fun calculate(bytes: ByteArray): Long {
        require(bytes.size == 2328)
        val reader = LittleEndianReader(bytes)
        var value = 1L
        for ((a, b) in pairs) {
            value = ((value + reader.i8(a) + 1L) * (reader.i8(b) + 1L)) and 0xffff_ffffL
        }
        value += 1L + reader.u8(1825)
        repeat(19) { slot -> value += reader.u16(12 + 36 * slot) + reader.u8(14 + 36 * slot) }
        return value and 0xffff_ffffL
    }

    private val pairs = listOf(868 to 917, 880 to 840, 886 to 1377, 1372 to 916, 1378 to 849)
}
