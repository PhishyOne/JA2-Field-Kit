package com.phishtopia.ja2fieldkit.core.format

class InvalidProfileChecksumRecordSizeException(
    val actualSize: Int,
    val requiredSize: Int = NormalProfileRotationRecovery.RECORD_SIZE,
) : IllegalArgumentException("Profile checksum requires $requiredSize bytes; got $actualSize")

/** Project-authored recurrence from docs/profile-rotation-recovery.md; no gameplay clamping. */
object NormalProfileChecksum {
    private val statPairs = listOf(334 to 297, 405 to 335, 296 to 353, 261 to 411, 339 to 352)

    fun calculate(record: ByteArray): Long {
        if (record.size != NormalProfileRotationRecovery.RECORD_SIZE) {
            throw InvalidProfileChecksumRecordSizeException(record.size)
        }
        var sum = statPairs.fold(1L) { value, (addend, multiplier) ->
            ((value + record[addend] + 1L) * (record[multiplier] + 1L)) and 0xffff_ffffL
        }
        repeat(19) { item ->
            val offset = 416 + item * 2
            sum += (record[offset].toInt() and 0xff) or
                ((record[offset + 1].toInt() and 0xff) shl 8)
            sum += record[377 + item].toInt() and 0xff
        }
        return sum and 0xffff_ffffL
    }
}
