package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
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
    private val encryptedProfiles =
        resource("/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin")
            .copyOfRange(PROFILE_VECTOR_CIPHERTEXT_OFFSET, PROFILE_VECTOR_CIPHERTEXT_END)

    @Test
    fun supportsOnlyTheCompleteEvidencedRebornPath() {
        val save = syntheticSave(eventCount = 2)

        val result = Ja2SaveInspector().detect(save)

        assertEquals(SaveFamily.JA2_REBORN, result.family)
        assertEquals(SaveDetectionState.SUPPORTED, result.state)
        assertEquals(
            SaveDetectionReason.MATCHED_REBORN_BUILD_041202_NORMAL_NON_LINUX,
            result.reason,
        )
        assertEquals(103, result.saveVersion)
        assertEquals(103L, result.facts.rawSaveVersion)
        assertEquals("04.12.02", result.buildLabel)
        assertEquals("Build 04.12.02", result.facts.gameVersion)
        assertEquals(true, result.facts.selectorHeaderCompatible)
        assertEquals(2L, result.facts.eventCount)
        assertEquals(expectedProfileStart(2), result.facts.profileStartOffset)
        assertEquals(expectedProfileStart(2) + encryptedProfiles.size, result.facts.profileEndExclusive)
        assertFailsWith<UnsupportedOperationException> {
            (result.evidence as MutableList<String>).clear()
        }
    }

    @Test
    fun exactIdentityWithoutACompleteHeaderIsOnlyPartial() {
        val result = SaveFormatDetector.detect(header.copyOf(20))

        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(SaveDetectionState.PARTIALLY_SUPPORTED, result.state)
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
            assertEquals(SaveDetectionState.CONTRADICTORY, result.state)
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
        assertEquals(SaveDetectionState.UNKNOWN, unknown.state)
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
        assertEquals(SaveDetectionState.UNKNOWN, largeResult.state)
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

        assertEquals(SaveDetectionState.PARTIALLY_SUPPORTED, result.state)
        assertEquals(SaveDetectionReason.UNSUPPORTED_SELECTOR_HEADER, result.reason)
        assertEquals(false, result.facts.selectorHeaderCompatible)
        assertNull(result.facts.eventCount)
    }

    @Test
    fun reportsTruncationAndUnsupportedDynamicTailsWithoutGuessing() {
        val truncated = SaveFormatDetector.detect(header)
        assertEquals(SaveDetectionState.PARTIALLY_SUPPORTED, truncated.state)
        assertEquals(SaveDetectionReason.TRUNCATED_NORMAL_NON_LINUX_LAYOUT, truncated.reason)
        assertEquals(ProfileFramingStage.TACTICAL_STATUS, truncated.facts.framingStage)
        assertEquals(748L, truncated.facts.requiredEndExclusive)

        val dynamicTail = syntheticSave(eventCount = 0, orderUsedCount = 1)
            .copyOf(expectedProfileStart(0))
        val dynamic = SaveFormatDetector.detect(dynamicTail)
        assertEquals(SaveDetectionState.PARTIALLY_SUPPORTED, dynamic.state)
        assertEquals(SaveDetectionReason.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL, dynamic.reason)
        assertEquals(0L, dynamic.facts.eventCount)
        assertEquals(ProfileFramingStage.LAPTOP_FIXED_BLOCK, dynamic.facts.framingStage)
        assertNull(dynamic.facts.profileStartOffset)
    }

    @Test
    fun completeFramingWithoutProfileConsistencyIsNotSupported() {
        val save = syntheticSave(eventCount = 0)
        save[expectedProfileStart(0) + 80] =
            (save[expectedProfileStart(0) + 80].toInt() xor 1).toByte()

        val result = SaveFormatDetector.detect(save)

        assertEquals(SaveFamily.UNKNOWN, result.family)
        assertEquals(SaveDetectionState.PARTIALLY_SUPPORTED, result.state)
        assertEquals(SaveDetectionReason.PROFILE_ROTATION_NOT_CONFIRMED, result.reason)
        assertEquals(expectedProfileStart(0), result.facts.profileStartOffset)
    }

    @Test
    fun detectionDoesNotMutateOrRetainCallerBytes() {
        val save = syntheticSave(eventCount = 0)
        val before = save.copyOf()

        val result = SaveFormatDetector.detect(save)

        assertContentEquals(before, save)
        save.fill(0)
        assertEquals(SaveDetectionState.SUPPORTED, result.state)
        assertTrue(result.evidence.none { it.contains("Synthetic") })
    }

    private fun syntheticSave(eventCount: Long, orderUsedCount: Int = 0): ByteArray {
        val profileStart = expectedProfileStart(eventCount)
        return ByteArray(profileStart + encryptedProfiles.size).also { save ->
            header.copyInto(save)
            save.putU32Le(EVENT_COUNT_OFFSET, eventCount)
            val laptopStart = EVENT_DATA_OFFSET +
                eventCount.toInt() * SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE
            save[laptopStart + FIXTURE_ORDER_ARRAY_SIZE_OFFSET] = 9
            save[laptopStart + FIXTURE_ORDER_USED_COUNT_OFFSET] = orderUsedCount.toByte()
            save[laptopStart + FIXTURE_PAYOUT_ARRAY_SIZE_OFFSET] = 7
            encryptedProfiles.copyInto(save, profileStart)
        }
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
        const val PROFILE_VECTOR_CIPHERTEXT_OFFSET = 121769
        const val PROFILE_VECTOR_CIPHERTEXT_END = 243489
        // Test-owned offsets intentionally do not reference production constants.
        const val FIXTURE_ORDER_ARRAY_SIZE_OFFSET = 7276
        const val FIXTURE_ORDER_USED_COUNT_OFFSET = 7277
        const val FIXTURE_PAYOUT_ARRAY_SIZE_OFFSET = 7284
    }
}
