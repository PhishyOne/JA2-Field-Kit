package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import com.phishtopia.ja2fieldkit.core.SaveInterpretationAdmissionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Detector constructions are project-authored and make no real-save-family claim. */
class SaveFormatDetectorTest {
    private val header = resource("/fixtures/synthetic-build-04.12.02-header-v1.bin")
    private val recoveryVector =
        resource("/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin")
    private val encryptedProfiles = recoveryVector.copyOfRange(
        PROFILE_VECTOR_CIPHERTEXT_OFFSET,
        PROFILE_VECTOR_CIPHERTEXT_END,
    )

    @Test
    fun oracleMatchForSharedRebornStracciatellaLayoutDoesNotAttributeProducerFamily() {
        val save = syntheticSave(eventCount = 2)

        val result = SaveFormatDetector.detect(save, oracle(139 to SYNTHETIC_ROTATION_DIGEST))

        assertEquals(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX, result.layout)
        assertEquals(SaveCompatibility.SUPPORTED, result.compatibility)
        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(SaveDetectionReason.SELECTOR_BODY_ROTATION_MATCH, result.reason)
        assertEquals(103, result.saveVersion)
        assertEquals(103L, result.facts.rawSaveVersion)
        assertEquals("04.12.02", result.buildLabel)
        assertEquals("Build 04.12.02", result.facts.gameVersion)
        assertEquals(true, result.facts.selectorHeaderCompatible)
        assertEquals(139, result.facts.selectedRotationIndex)
        assertEquals(true, result.facts.rotationOracleAvailable)
        assertEquals(true, result.facts.rotationDigestMatched)
        assertEquals(2L, result.facts.eventCount)
        assertEquals(expectedProfileStart(2), result.facts.profileStartOffset)
        assertEquals(expectedProfileStart(2) + encryptedProfiles.size, result.facts.profileEndExclusive)
        assertFailsWith<UnsupportedOperationException> {
            (result.evidence as MutableList<String>).clear()
        }
        assertTrue(result.evidence.none { it.contains("Reborn", ignoreCase = true) })
    }

    @Test
    fun publicOracleRejectsTheExactSyntheticCompositeWithoutAttributingFamily() {
        val result = Ja2SaveInspector().detect(syntheticSave(eventCount = 0))

        assertEquals(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX, result.layout)
        assertEquals(SaveCompatibility.INCONSISTENT, result.compatibility)
        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(SaveDetectionReason.ROTATION_DIGEST_MISMATCH, result.reason)
        assertEquals(true, result.facts.rotationOracleAvailable)
        assertEquals(false, result.facts.rotationDigestMatched)
    }

    @Test
    fun publicInterpretationRejectsEveryNonSupportedCompatibility() {
        val candidate = syntheticSave(eventCount = 0).also {
            it[LOAD_SCREEN_ID_OFFSET] = (it[LOAD_SCREEN_ID_OFFSET] + 1).toByte()
        }
        val cases = listOf(
            SaveCompatibility.CANDIDATE to candidate,
            SaveCompatibility.INCONSISTENT to syntheticSave(eventCount = 0),
            SaveCompatibility.TRUNCATED to header,
            SaveCompatibility.UNSUPPORTED_VARIANT to header.copyOf().also { it[303] = 2 },
            SaveCompatibility.UNKNOWN to ByteArray(20),
        )

        for ((compatibility, bytes) in cases) {
            val failure = assertFailsWith<SaveInterpretationAdmissionException> {
                Ja2SaveInspector().parseBuild041202NormalNonLinuxProfiles(bytes)
            }
            assertEquals(compatibility, failure.compatibility)
        }
    }

    @Test
    fun selectorAffectingHeaderMutationWithSameBodyFailsClosed() {
        val save = syntheticSave(eventCount = 0)
        save[LOAD_SCREEN_ID_OFFSET] = (save[LOAD_SCREEN_ID_OFFSET] + 1).toByte()

        val result = SaveFormatDetector.detect(
            save,
            oracle(
                139 to SYNTHETIC_ROTATION_DIGEST,
                149 to DIFFERENT_ROTATION_DIGEST,
            ),
        )

        assertEquals(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX, result.layout)
        assertEquals(SaveCompatibility.INCONSISTENT, result.compatibility)
        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(149, result.facts.selectedRotationIndex)
        assertEquals(SaveDetectionReason.ROTATION_DIGEST_MISMATCH, result.reason)
    }

    @Test
    fun missingOracleLeavesStructuralMatchAsCandidate() {
        val result = SaveFormatDetector.detect(syntheticSave(eventCount = 0), oracle())

        assertEquals(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX, result.layout)
        assertEquals(SaveCompatibility.CANDIDATE, result.compatibility)
        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(SaveDetectionReason.ROTATION_DIGEST_ORACLE_MISSING, result.reason)
        assertEquals(false, result.facts.rotationOracleAvailable)
        assertNull(result.facts.rotationDigestMatched)
    }

    @Test
    fun exactIdentityWithoutACompleteHeaderIsTruncatedAndHasNoLayoutClaim() {
        val result = SaveFormatDetector.detect(header.copyOf(20))

        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(SaveLayout.UNKNOWN, result.layout)
        assertEquals(SaveCompatibility.TRUNCATED, result.compatibility)
        assertEquals(SaveDetectionReason.TRUNCATED_RECOGNIZED_HEADER, result.reason)
        assertEquals(103, result.saveVersion)
        assertEquals("04.12.02", result.buildLabel)
        assertFalse(result.facts.hasCompleteNormalHeader)
        assertEquals(432L, result.facts.requiredEndExclusive)
        assertNull(result.facts.eventCount)
    }

    @Test
    fun distinguishesContradictoryAndUnknownHeaderIdentities() {
        val knownVersionWrongBuild = header.copyOf().also {
            it.putSingleByteString(4, 16, "Build 99.99.99")
        }
        val wrongVersionKnownBuild = header.copyOf().also { it.putU32Le(0, 102) }
        for (bytes in listOf(knownVersionWrongBuild, wrongVersionKnownBuild)) {
            val result = SaveFormatDetector.detect(bytes)
            assertEquals(SaveFamily.UNKNOWN, result.family)
            assertEquals(SaveLayout.UNKNOWN, result.layout)
            assertEquals(SaveCompatibility.INCONSISTENT, result.compatibility)
            assertEquals(SaveDetectionReason.CONTRADICTORY_HEADER_IDENTITY, result.reason)
            assertNull(result.buildLabel)
            assertNull(result.facts.selectorHeaderCompatible)
        }

        val unrecognized = ByteArray(200_000).also {
            it.putU32Le(0, 999)
            it.putSingleByteString(4, 16, "1.13 modded")
        }
        val unknown = SaveFormatDetector.detect(unrecognized)
        assertEquals(SaveFamily.UNKNOWN, unknown.family)
        assertEquals(SaveLayout.UNKNOWN, unknown.layout)
        assertEquals(SaveCompatibility.UNKNOWN, unknown.compatibility)
        assertEquals(SaveDetectionReason.UNKNOWN_HEADER_IDENTITY, unknown.reason)
        assertEquals(999, unknown.saveVersion)
        assertEquals("1.13 modded", unknown.facts.gameVersion)
        assertNull(unknown.facts.eventCount)
        assertNull(unknown.facts.profileStartOffset)

        val nonPrintable = unrecognized.copyOf().also { it[4] = 1 }
        assertNull(SaveFormatDetector.detect(nonPrintable).facts.gameVersion)
    }

    @Test
    fun fileSizeAndIncompleteIdentityNeverSelectAParser() {
        val large = ByteArray(1_000_000) { 0x5a }
        val largeResult = SaveFormatDetector.detect(large)
        assertEquals(SaveCompatibility.UNKNOWN, largeResult.compatibility)
        assertEquals(SaveLayout.UNKNOWN, largeResult.layout)
        assertEquals(SaveFamily.UNKNOWN, largeResult.family)

        val short = header.copyOf(19)
        val shortResult = SaveFormatDetector.detect(short)
        assertEquals(SaveDetectionReason.TOO_SHORT_FOR_HEADER_IDENTITY, shortResult.reason)
        assertEquals(103, shortResult.saveVersion)
        assertNull(shortResult.facts.gameVersion)
    }

    @Test
    fun rejectsUnsupportedSelectorFactsBeforeWalkingTheBody() {
        val bytes = header.copyOf().also { it[303] = 2 }

        val result = SaveFormatDetector.detect(bytes)

        assertEquals(SaveCompatibility.UNSUPPORTED_VARIANT, result.compatibility)
        assertEquals(SaveLayout.UNKNOWN, result.layout)
        assertEquals(SaveDetectionReason.UNSUPPORTED_SELECTOR_HEADER, result.reason)
        assertEquals(false, result.facts.selectorHeaderCompatible)
        assertNull(result.facts.selectedRotationIndex)
        assertNull(result.facts.eventCount)
    }

    @Test
    fun reportsTruncationAndUnsupportedDynamicTailsWithoutGuessing() {
        val truncated = SaveFormatDetector.detect(header)
        assertEquals(SaveCompatibility.TRUNCATED, truncated.compatibility)
        assertEquals(SaveDetectionReason.TRUNCATED_NORMAL_NON_LINUX_LAYOUT, truncated.reason)
        assertEquals(ProfileFramingStage.TACTICAL_STATUS, truncated.facts.framingStage)
        assertEquals(748L, truncated.facts.requiredEndExclusive)

        val dynamicTail = syntheticSave(eventCount = 0, orderUsedCount = 1)
            .copyOf(expectedProfileStart(0))
        val dynamic = SaveFormatDetector.detect(dynamicTail)
        assertEquals(SaveCompatibility.UNSUPPORTED_VARIANT, dynamic.compatibility)
        assertEquals(SaveDetectionReason.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL, dynamic.reason)
        assertEquals(0L, dynamic.facts.eventCount)
        assertEquals(ProfileFramingStage.LAPTOP_FIXED_BLOCK, dynamic.facts.framingStage)
        assertNull(dynamic.facts.profileStartOffset)
    }

    @Test
    fun reportsRotationConstraintNoCandidateAndAmbiguousBoundaries() {
        val conflicting = syntheticSave(eventCount = 0).also {
            it[expectedProfileStart(0) + 80] =
                (it[expectedProfileStart(0) + 80].toInt() xor 1).toByte()
        }
        val conflictResult = SaveFormatDetector.detect(conflicting)
        assertEquals(SaveCompatibility.INCONSISTENT, conflictResult.compatibility)
        assertEquals(
            SaveDetectionReason.PROFILE_ROTATION_CONSTRAINT_CONFLICT,
            conflictResult.reason,
        )

        val noCandidate = syntheticSave(eventCount = 0).also {
            it[expectedProfileStart(0) + STORED_CHECKSUM_OFFSET] =
                (it[expectedProfileStart(0) + STORED_CHECKSUM_OFFSET].toInt() xor 1).toByte()
        }
        val noCandidateResult = SaveFormatDetector.detect(noCandidate)
        assertEquals(SaveCompatibility.INCONSISTENT, noCandidateResult.compatibility)
        assertEquals(SaveDetectionReason.PROFILE_ROTATION_NO_CANDIDATE, noCandidateResult.reason)

        val ambiguityRecord = recoveryVector.copyOfRange(
            PROFILE_VECTOR_AMBIGUITY_CIPHERTEXT_OFFSET,
            PROFILE_VECTOR_AMBIGUITY_CIPHERTEXT_END,
        )
        val ambiguousProfiles = ByteArray(encryptedProfiles.size).also { block ->
            repeat(NormalProfileRotationRecovery.RECORD_COUNT) { record ->
                ambiguityRecord.copyInto(block, record * ambiguityRecord.size)
            }
        }
        val ambiguousResult = SaveFormatDetector.detect(
            syntheticSave(eventCount = 0, profiles = ambiguousProfiles),
        )
        assertEquals(SaveCompatibility.CANDIDATE, ambiguousResult.compatibility)
        assertEquals(SaveDetectionReason.PROFILE_ROTATION_AMBIGUOUS, ambiguousResult.reason)
        assertEquals(SaveFamily.UNKNOWN, ambiguousResult.family)
    }

    @Test
    fun detectionDoesNotMutateOrRetainCallerBytes() {
        val save = syntheticSave(eventCount = 0)
        val before = save.copyOf()

        val result = SaveFormatDetector.detect(save, oracle(139 to SYNTHETIC_ROTATION_DIGEST))

        assertContentEquals(before, save)
        save.fill(0)
        assertEquals(SaveCompatibility.SUPPORTED, result.compatibility)
        assertEquals(139, result.facts.selectedRotationIndex)
        assertTrue(result.evidence.none { it.contains(SYNTHETIC_ROTATION_DIGEST) })
    }

    private fun syntheticSave(
        eventCount: Long,
        orderUsedCount: Int = 0,
        profiles: ByteArray = encryptedProfiles,
    ): ByteArray {
        val profileStart = expectedProfileStart(eventCount)
        return ByteArray(profileStart + profiles.size).also { save ->
            header.copyInto(save)
            save.putU32Le(EVENT_COUNT_OFFSET, eventCount)
            val laptopStart = EVENT_DATA_OFFSET +
                eventCount.toInt() * SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE
            save[laptopStart + FIXTURE_ORDER_ARRAY_SIZE_OFFSET] = 9
            save[laptopStart + FIXTURE_ORDER_USED_COUNT_OFFSET] = orderUsedCount.toByte()
            save[laptopStart + FIXTURE_PAYOUT_ARRAY_SIZE_OFFSET] = 7
            profiles.copyInto(save, profileStart)
        }
    }

    private fun oracle(vararg entries: Pair<Int, String>): RotationDigestOracle {
        val digests = entries.associate { (index, digest) ->
            index to RotationTableDigest.parse(digest)
        }
        return RotationDigestOracle { index -> digests[index.value] }
    }

    private fun expectedProfileStart(eventCount: Long): Int =
        (EVENT_DATA_OFFSET +
            eventCount * SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE +
            SaveLayoutFacts.NORMAL_NON_LINUX_LAPTOP_FIXED_SIZE).toInt()

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Synthetic test resource is missing" }
            .use { it.readBytes() }

    private fun ByteArray.putSingleByteString(offset: Int, length: Int, value: String) {
        require(value.length < length)
        fill(0, offset, offset + length)
        value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() }
    }

    private fun ByteArray.putU32Le(offset: Int, value: Int) = putU32Le(offset, value.toLong())

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private companion object {
        const val EVENT_COUNT_OFFSET = 815
        const val EVENT_DATA_OFFSET = 819
        const val LOAD_SCREEN_ID_OFFSET = 302
        const val STORED_CHECKSUM_OFFSET = 696
        const val PROFILE_VECTOR_CIPHERTEXT_OFFSET = 121769
        const val PROFILE_VECTOR_CIPHERTEXT_END = 243489
        const val PROFILE_VECTOR_AMBIGUITY_CIPHERTEXT_OFFSET = 244205
        const val PROFILE_VECTOR_AMBIGUITY_CIPHERTEXT_END = 244921
        const val SYNTHETIC_ROTATION_DIGEST =
            "6f2444fea06021ff6a7319adabd3e3ac31c1d7ecacd530c7f4c9110dbd17dd52"
        const val DIFFERENT_ROTATION_DIGEST =
            "78877fa898f0b4c45c9c33ae941e40617ad7c8657a307db62bc5691f92f4f60e"
        // Test-owned offsets intentionally do not reference production constants.
        const val FIXTURE_ORDER_ARRAY_SIZE_OFFSET = 7276
        const val FIXTURE_ORDER_USED_COUNT_OFFSET = 7277
        const val FIXTURE_PAYOUT_ARRAY_SIZE_OFFSET = 7284
    }
}
