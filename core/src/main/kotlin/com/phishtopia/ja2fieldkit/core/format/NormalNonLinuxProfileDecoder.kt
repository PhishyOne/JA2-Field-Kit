package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.model.MercProfile

/**
 * Whole-save, read-only profile pipeline for the evidenced normal non-Linux v103 / Build 04.12.02
 * layout. The recovered rotation belongs only to this invocation and is not retained or cached.
 */
object NormalNonLinuxProfileDecoder {
    fun decodeBuild041202(saveBytes: ByteArray): List<MercProfile> {
        val frame = NormalNonLinuxProfileFramer.frameBuild041202(saveBytes)
        NormalEncryptionHeaderInputs.fromBuild041202(
            SaveHeaderParser.parseBuild041202(saveBytes),
        )
        val encryptedProfiles = frame.encryptedProfileBytes
        val rotation = NormalProfileRotationRecovery.recoverBuild041202(encryptedProfiles)
        val decryptedProfiles = ByteArray(NormalProfileRotationRecovery.BLOCK_SIZE)

        repeat(NormalProfileRotationRecovery.RECORD_COUNT) { recordIndex ->
            val start = recordIndex * NormalProfileRotationRecovery.RECORD_SIZE
            val end = start + NormalProfileRotationRecovery.RECORD_SIZE
            NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = encryptedProfiles.copyOfRange(start, end),
                expectedBlockSize = NormalProfileRotationRecovery.RECORD_SIZE,
                rotationTable = rotation,
            ).copyInto(decryptedProfiles, start)
        }

        return NormalMercProfileParser.parseBuild041202(decryptedProfiles)
    }
}
