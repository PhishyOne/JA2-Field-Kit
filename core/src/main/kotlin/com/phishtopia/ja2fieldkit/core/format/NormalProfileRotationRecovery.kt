package com.phishtopia.ja2fieldkit.core.format

enum class ProfileRotationRecoveryFailure {
    INVALID_BLOCK_SIZE,
    CONFLICTING_RESERVED_CONSTRAINT,
    NO_CANDIDATE,
    AMBIGUOUS,
}

/** Diagnostics contain only structural metadata, never input, plaintext, or key bytes. */
class ProfileRotationRecoveryException(
    val reason: ProfileRotationRecoveryFailure,
    val actualSize: Int? = null,
    val recordIndex: Int? = null,
    val byteOffset: Int? = null,
    val candidateCount: Int? = null,
) : IllegalArgumentException(
        "Profile rotation recovery: $reason " +
            "(size=$actualSize, record=$recordIndex, offset=$byteOffset, candidates=$candidateCount)",
    )

/**
 * Recovery for an already-framed canonical normal v103 / Build 04.12.02 profile block.
 * This establishes zero/checksum consistency only, not family, authenticity, or stat validity.
 * The returned table belongs to this invocation; do not cache it under a selector index.
 * See docs/profile-rotation-recovery.md for evidence and the independent framing prerequisite.
 */
object NormalProfileRotationRecovery {
    const val RECORD_COUNT = 170
    const val RECORD_SIZE = 716
    const val BLOCK_SIZE = RECORD_COUNT * RECORD_SIZE
    private const val UNKNOWN_RESIDUE = 10
    private const val UINT32_MASK = 0xffff_ffffL

    private val reservedRanges = listOf(
        80 until 108, 236 until 238, 240 until 242, 270 until 280,
        309 until 310, 354 until 355, 356 until 358, 407 until 408,
        415 until 416, 454 until 474, 525 until 529, 539 until 540,
        550 until 552, 573 until 574, 680 until 682, 712 until 716,
    )
    private val checksumPairs = listOf(
        334 to 297, 405 to 335, 296 to 353, 261 to 411, 339 to 352,
    )

    fun recoverBuild041202(encryptedProfiles: ByteArray): SaveRotationTable {
        if (encryptedProfiles.size != BLOCK_SIZE) {
            throw ProfileRotationRecoveryException(
                ProfileRotationRecoveryFailure.INVALID_BLOCK_SIZE,
                actualSize = encryptedProfiles.size,
            )
        }
        // Bound allocation before snapshotting. No subsequent read touches caller-owned bytes.
        val snapshot = encryptedProfiles.copyOf()
        val records = List(RECORD_COUNT) { index ->
            snapshot.copyOfRange(index * RECORD_SIZE, (index + 1) * RECORD_SIZE)
        }
        val residues = IntArray(SaveRotationTable.REQUIRED_LENGTH) { -1 }
        records.forEachIndexed { recordIndex, record ->
            for (range in reservedRanges) {
                for (offset in range) {
                    val previous = if (offset == 0) 0 else record[offset - 1].toInt() and 0xff
                    val constraint = ((record[offset].toInt() and 0xff) - previous) and 0xff
                    val residue = offset % residues.size
                    if (residues[residue] != -1 && residues[residue] != constraint) {
                        throw ProfileRotationRecoveryException(
                            ProfileRotationRecoveryFailure.CONFLICTING_RESERVED_CONSTRAINT,
                            recordIndex = recordIndex,
                            byteOffset = offset,
                        )
                    }
                    residues[residue] = constraint
                }
            }
        }

        val key = ByteArray(residues.size) { residues[it].toByte() }
        var survivor: SaveRotationTable? = null
        var survivorCount = 0
        for (value in 0..255) {
            key[UNKNOWN_RESIDUE] = value.toByte()
            val candidate = SaveRotationTable.fromBytes(key)
            val valid = records.all { record ->
                // Each call resets both feedback and rotation position for this record.
                val plain = NormalSaveBlockDecryptor.decryptBlock(record, RECORD_SIZE, candidate)
                checksum(plain) == unsignedLittleEndian(plain, 696, 4)
            }
            if (valid) {
                survivor = candidate
                survivorCount++
            }
        }
        return when (survivorCount) {
            0 -> throw ProfileRotationRecoveryException(
                ProfileRotationRecoveryFailure.NO_CANDIDATE, candidateCount = 0,
            )
            1 -> checkNotNull(survivor)
            else -> throw ProfileRotationRecoveryException(
                ProfileRotationRecoveryFailure.AMBIGUOUS, candidateCount = survivorCount,
            )
        }
    }

    private fun checksum(record: ByteArray): Long {
        var sum = 1L
        for ((first, second) in checksumPairs) {
            sum = ((sum + record[first].toLong() + 1L) * (record[second].toLong() + 1L)) and UINT32_MASK
        }
        repeat(19) { item ->
            sum += unsignedLittleEndian(record, 416 + item * 2, 2)
            sum += record[377 + item].toInt() and 0xff
        }
        return sum and UINT32_MASK
    }

    private fun unsignedLittleEndian(record: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        repeat(size) { byte ->
            value = value or ((record[offset + byte].toLong() and 0xffL) shl (8 * byte))
        }
        return value
    }
}
