package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader

/** Project-authored checksum authority shared by validated reads and the closed edit kernel. */
internal object NormalSoldierChecksum {
    fun calculate(bytes: ByteArray): Long {
        require(bytes.size == 2328)
        val reader = LittleEndianReader(bytes)
        var sum = 1L
        for ((addend, multiplier) in statPairs) {
            sum = ((sum + 1L + reader.i8(addend)) * (1L + reader.i8(multiplier))) and 0xffff_ffffL
        }
        sum = (sum + 1L + reader.u8(1825)) and 0xffff_ffffL
        repeat(19) { slot ->
            sum = (sum + reader.u16(12 + 36 * slot) + reader.u8(14 + 36 * slot)) and 0xffff_ffffL
        }
        return sum
    }

    private val statPairs = listOf(868 to 917, 880 to 840, 886 to 1377, 1372 to 916, 1378 to 849)
}
