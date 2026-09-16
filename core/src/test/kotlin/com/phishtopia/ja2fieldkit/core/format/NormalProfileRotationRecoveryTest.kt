package com.phishtopia.ja2fieldkit.core.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** All variants are project-authored synthetic experiments, not real-save evidence. */
class NormalProfileRotationRecoveryTest {
    private val vector = assertNotNull(javaClass.getResourceAsStream(
        "/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin",
    )).use { it.readBytes() }.also { assertEquals(244921, it.size) }
    private val key = vector.copyOfRange(0, 49)
    private val plaintext = vector.copyOfRange(49, 121769)
    private val ciphertext = vector.copyOfRange(121769, 243489)
    private val ambiguityPlaintext = vector.copyOfRange(243489, 244205)
    private val ambiguityCiphertext = vector.copyOfRange(244205, 244921)

    @Test
    fun recoversExactIndependentPythonKeyAndEveryPlaintextByte() {
        assertContentEquals(ByteArray(49) { (73 * it + 19).toByte() }, key)
        val recovered = recover(ciphertext)
        assertContentEquals(key, recovered.toByteArray())
        assertContentEquals(plaintext, decryptRecords(ciphertext, recovered))
    }

    @Test
    fun enumeratesAll256ActualMissingResidueValues() {
        for (value in 0..255) {
            val changedKey = key.copyOf().also { it[10] = value.toByte() }
            val changedCiphertext = rekey(changedKey)
            val recovered = recover(changedCiphertext)
            assertContentEquals(changedKey, recovered.toByteArray(), "missing residue $value")
            assertContentEquals(plaintext, decryptRecords(changedCiphertext, recovered))
        }
    }

    @Test
    fun rejectsAll129SurvivorsOfSignedFieldAmbiguityWitness() {
        // Signed recurrence: 2, -128, -255, -254, -253; the inventory adds 256.
        val expected = ByteArray(716).also {
            it[405] = 252.toByte()
            it[335] = 127
            it[296] = 128.toByte()
            it[451] = 1
            it[696] = 3
        }
        assertContentEquals(expected, ambiguityPlaintext)
        assertContentEquals(expected, NormalSaveBlockDecryptor.decryptBlock(
            ambiguityCiphertext, 716, SaveRotationTable.fromBytes(key),
        ))
        val block = ByteArray(121720) { ambiguityCiphertext[it % 716] }
        val failure = failure(ProfileRotationRecoveryFailure.AMBIGUOUS, block)
        assertEquals(129, failure.candidateCount)
    }

    @Test
    fun rejectsChecksumFailureEvenInFinalRecord() {
        for (record in listOf(0, 169)) {
            // Add one to only the stored checksum's low plaintext byte, preserving all zeros.
            val damaged = changePlainByte(ciphertext, record, 696)
            val error = failure(ProfileRotationRecoveryFailure.NO_CANDIDATE, damaged)
            assertEquals(0, error.candidateCount)
        }
    }

    @Test
    fun rejectsConflictingReservedConstraintsIncludingFinalRecord() {
        for (record in listOf(0, 169)) {
            val damaged = ciphertext.copyOf()
            val offset = record * 716 + 80
            damaged[offset] = (damaged[offset] + 1).toByte()
            val error = failure(ProfileRotationRecoveryFailure.CONFLICTING_RESERVED_CONSTRAINT, damaged)
            assertEquals(record, error.recordIndex)
            assertEquals(if (record == 0) 276 else 80, error.byteOffset)
        }
    }

    @Test
    fun rejectsEmptyTruncatedOversizedAndAllZeroBlocks() {
        for (size in listOf(0, 715, 716, 717, 169 * 716, 121719, 121721)) {
            val error = failure(ProfileRotationRecoveryFailure.INVALID_BLOCK_SIZE, ByteArray(size))
            assertEquals(size, error.actualSize)
        }
        failure(ProfileRotationRecoveryFailure.NO_CANDIDATE, ByteArray(121720))
    }

    @Test
    fun rejectsWholeTableContinuousFeedbackAndRotation() {
        // Deliberately wrong synthetic construction: one continuous operation across the table.
        var feedback = 0
        val continuous = ByteArray(plaintext.size) { offset ->
            feedback = (feedback + (plaintext[offset].toInt() and 255) +
                (key[offset % 49].toInt() and 255)) and 255
            feedback.toByte()
        }
        failure(ProfileRotationRecoveryFailure.CONFLICTING_RESERVED_CONSTRAINT, continuous)
        assertTrue(!plaintext.contentEquals(
            NormalSaveBlockDecryptor.decryptBlock(ciphertext, 121720, SaveRotationTable.fromBytes(key)),
        ))
    }

    @Test
    fun successiveInvocationsHandleZero255HighBitKeysAndUnsignedWrapWithoutCaches() {
        val keys = listOf(
            ByteArray(49), ByteArray(49) { 255.toByte() }, ByteArray(49) { 128.toByte() },
            ByteArray(49) { if (it % 2 == 0) 0 else 255.toByte() }, key,
        )
        for (changedKey in keys) {
            val encrypted = rekey(changedKey)
            val recovered = recover(encrypted)
            assertContentEquals(changedKey, recovered.toByteArray())
            assertContentEquals(plaintext, decryptRecords(encrypted, recovered))
        }
    }

    @Test
    fun inputAndReturnedTableAreDefensivelySeparatedAndDiagnosticsContainNoPayload() {
        val input = ciphertext.copyOf()
        val recovered = recover(input)
        assertContentEquals(ciphertext, input)
        input.fill(0)
        recovered.toByteArray().fill(0)
        assertContentEquals(key, recovered.toByteArray())
        assertEquals("SaveRotationTable(49 bytes)", recovered.toString())
        val error = failure(ProfileRotationRecoveryFailure.AMBIGUOUS,
            ByteArray(121720) { ambiguityCiphertext[it % 716] })
        assertEquals(
            "Profile rotation recovery: AMBIGUOUS (size=null, record=null, offset=null, candidates=129)",
            error.message,
        )
        assertEquals("${ProfileRotationRecoveryException::class.java.name}: ${error.message}", error.toString())
    }

    @Test
    fun changedNameCharacterPassesBecauseChecksumDoesNotCoverNames() {
        // Offset zero is the low byte of the first serialized name code unit.
        // Altering it leaves every checksum and canonical reserved zero untouched.
        val changed = changePlainByte(ciphertext, 169, 0)
        val recovered = recover(changed)
        assertContentEquals(key, recovered.toByteArray())
        val expected = plaintext.copyOf().also { it[169 * 716] = (it[169 * 716] + 1).toByte() }
        assertContentEquals(expected, decryptRecords(changed, recovered))
        assertTrue(!plaintext.contentEquals(expected))
    }

    private fun recover(block: ByteArray): SaveRotationTable =
        NormalProfileRotationRecovery.recoverBuild041202(block)

    private fun failure(reason: ProfileRotationRecoveryFailure, block: ByteArray): ProfileRotationRecoveryException =
        assertFailsWith<ProfileRotationRecoveryException> { recover(block) }.also {
            assertEquals(reason, it.reason)
        }

    private fun decryptRecords(block: ByteArray, table: SaveRotationTable): ByteArray {
        val result = ByteArray(121720)
        repeat(170) { record ->
            NormalSaveBlockDecryptor.decryptBlock(
                block.copyOfRange(record * 716, (record + 1) * 716), 716, table,
            ).copyInto(result, record * 716)
        }
        return result
    }

    // Algebraic perturbation of the independent Python ciphertext; no production encrypt API.
    // A changed key adds the cumulative key difference to each record's ciphertext prefix.
    private fun rekey(newKey: ByteArray): ByteArray {
        var delta = 0
        return ByteArray(121720) { offset ->
            val local = offset % 716
            if (local == 0) delta = 0
            delta += newKey[local % 49].toInt() - key[local % 49].toInt()
            (ciphertext[offset] + delta).toByte()
        }
    }

    // A single plaintext-byte delta propagates through the rest of just that record.
    private fun changePlainByte(block: ByteArray, record: Int, offset: Int): ByteArray =
        block.copyOf().also { changed ->
            for (index in record * 716 + offset until (record + 1) * 716) {
                changed[index] = (changed[index] + 1).toByte()
            }
        }
}
