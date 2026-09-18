package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import com.phishtopia.ja2fieldkit.core.SaveInterpretationAdmissionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** All save bodies in these tests are project-authored synthetic framing data. */
class NormalNonLinuxProfileFramerTest {
    private val header = resource("/fixtures/synthetic-build-04.12.02-header-v1.bin")
    private val encryptedProfiles =
        resource("/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin")
            .copyOfRange(PROFILE_VECTOR_CIPHERTEXT_OFFSET, PROFILE_VECTOR_CIPHERTEXT_END)

    @Test
    fun extractsExactProfileTableAtDerivedOffsetsForMultipleEventCounts() {
        for (eventCount in listOf(0L, 1L, 142L)) {
            val save = syntheticSave(eventCount)

            val frame = NormalNonLinuxProfileFramer.frameBuild041202(save)

            val expectedStart = expectedProfileStart(eventCount)
            assertEquals(expectedStart, frame.profileStartOffset)
            assertEquals(
                expectedStart + SaveLayoutFacts.MERC_PROFILE_BLOCK_SIZE,
                frame.profileEndExclusive,
            )
            assertEquals(eventCount, frame.eventCount)
            assertEquals(9, frame.bobbyRayOrderArraySize)
            assertEquals(0, frame.bobbyRayOrderUsedCount)
            assertEquals(7, frame.insurancePayoutArraySize)
            assertEquals(0, frame.insurancePayoutUsedCount)
            assertContentEquals(encryptedProfiles, frame.encryptedProfileBytes)
        }
    }

    @Test
    fun publicEntryPointRequiresDigestAdmissionBeforeReturningAFrame() {
        val save = syntheticSave(eventCount = 1)

        val failure = assertFailsWith<SaveInterpretationAdmissionException> {
            Ja2SaveInspector().frameBuild041202NormalNonLinuxProfiles(save)
        }

        assertEquals(SaveCompatibility.INCONSISTENT, failure.compatibility)
        assertEquals(SaveDetectionReason.ROTATION_DIGEST_MISMATCH, failure.reason)
    }

    @Test
    fun rejectsTruncationAtEveryFixedPrefixAndCountedEventStage() {
        val cases = listOf(
            TruncationCase(432, 0, ProfileFramingStage.TACTICAL_STATUS, 748),
            TruncationCase(748, 0, ProfileFramingStage.CURRENT_SECTOR, 753),
            TruncationCase(753, 0, ProfileFramingStage.GAME_CLOCK, 815),
            TruncationCase(815, 0, ProfileFramingStage.STRATEGIC_EVENT_COUNT, 819),
            TruncationCase(819, 1, ProfileFramingStage.STRATEGIC_EVENTS, 847),
            TruncationCase(847, 1, ProfileFramingStage.LAPTOP_FIXED_BLOCK, 8287),
        )

        for (case in cases) {
            val error = framingFailure(prefixWithEventCount(case.inputSize, case.eventCount))
            assertEquals(ProfileFramingFailure.TRUNCATED_INPUT, error.reason)
            assertEquals(case.stage, error.stage)
            assertEquals(case.inputSize, error.actualSize)
            assertEquals(case.requiredEndExclusive, error.requiredEndExclusive)
        }
    }

    @Test
    fun preservesEstablishedTruncatedHeaderContract() {
        val error = assertFailsWith<TruncatedSaveHeaderException> {
            NormalNonLinuxProfileFramer.frameBuild041202(header.copyOf(header.size - 1))
        }

        assertEquals(header.size - 1, error.actualSize)
        assertEquals(SaveLayoutFacts.NORMAL_HEADER_SIZE, error.requiredSize)
    }

    @Test
    fun rejectsTruncatedProfileTableAtItsExactRequiredBoundary() {
        val complete = syntheticSave(eventCount = 142)
        val profileStart = expectedProfileStart(142)
        for (size in listOf(profileStart, complete.size - 1)) {
            val error = framingFailure(complete.copyOf(size))
            assertEquals(ProfileFramingFailure.TRUNCATED_INPUT, error.reason)
            assertEquals(ProfileFramingStage.PROFILE_TABLE, error.stage)
            assertEquals(complete.size.toLong(), error.requiredEndExclusive)
            assertEquals(142L, error.eventCount)
        }
    }

    @Test
    fun rejectsCountsWhoseEventExtentExceedsSignedIntWithoutOverflowOrAllocation() {
        for (eventCount in listOf(76_695_845L, 0xffff_ffffL)) {
            val save = prefixWithEventCount(EVENT_DATA_OFFSET, eventCount)

            val error = framingFailure(save)

            assertEquals(ProfileFramingFailure.TRUNCATED_INPUT, error.reason)
            assertEquals(ProfileFramingStage.STRATEGIC_EVENTS, error.stage)
            assertEquals(eventCount, error.eventCount)
            assertEquals(
                EVENT_DATA_OFFSET.toLong() +
                    eventCount * SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE,
                error.requiredEndExclusive,
            )
            assertTrue(checkNotNull(error.requiredEndExclusive) > Int.MAX_VALUE.toLong())
        }
    }

    @Test
    fun acceptsOversizedTrailingSaveBodyWithoutChangingTheFramedRange() {
        val save = syntheticSave(eventCount = 1, trailingSize = 65_537)

        val frame = NormalNonLinuxProfileFramer.frameBuild041202(save)

        assertEquals(expectedProfileStart(1), frame.profileStartOffset)
        assertEquals(expectedProfileStart(1) + encryptedProfiles.size, frame.profileEndExclusive)
        assertContentEquals(encryptedProfiles, frame.encryptedProfileBytes)
    }

    @Test
    fun failsClosedForEachCombinationOfUnsupportedDynamicLaptopTails() {
        for ((orderUsed, payoutUsed) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            // Deliberately omit the profile block: the used counts must fail at the fixed laptop
            // block rather than prompting any attempt to infer or skip a dynamic tail.
            val save = syntheticSave(
                eventCount = 1,
                orderUsedCount = orderUsed,
                payoutUsedCount = payoutUsed,
            ).copyOf(expectedProfileStart(1))

            val error = framingFailure(save)

            assertEquals(ProfileFramingFailure.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL, error.reason)
            assertEquals(ProfileFramingStage.LAPTOP_FIXED_BLOCK, error.stage)
            assertEquals(null, error.requiredEndExclusive)
            assertEquals(1L, error.eventCount)
            assertEquals(orderUsed, error.bobbyRayOrderUsedCount)
            assertEquals(payoutUsed, error.insurancePayoutUsedCount)
        }
    }

    @Test
    fun snapshotsInputAndReturnsANewDefensiveProfileCopyOnEveryAccess() {
        val save = syntheticSave(eventCount = 0)
        val originalSave = save.copyOf()
        val frame = NormalNonLinuxProfileFramer.frameBuild041202(save)

        assertContentEquals(originalSave, save)
        save[frame.profileStartOffset] = (save[frame.profileStartOffset] + 1).toByte()
        assertContentEquals(encryptedProfiles, frame.encryptedProfileBytes)

        val firstCopy = frame.encryptedProfileBytes
        firstCopy.fill(0)
        assertContentEquals(encryptedProfiles, frame.encryptedProfileBytes)
        assertTrue(firstCopy !== frame.encryptedProfileBytes)
    }

    @Test
    fun diagnosticsContainOnlyStructuralValues() {
        val save = syntheticSave(eventCount = 0, orderUsedCount = 3)
        val marker = "PRIVATE_NAME_OR_PATH"
        marker.encodeToByteArray().copyInto(save, expectedProfileStart(0))

        val error = framingFailure(save)

        assertEquals(
            "Normal non-Linux Build 04.12.02 profile framing: " +
                "UNSUPPORTED_DYNAMIC_LAPTOP_TAIL at LAPTOP_FIXED_BLOCK " +
                "(size=129979, requiredEndExclusive=null, events=0, orderUsed=3, payoutUsed=0)",
            error.message,
        )
        assertTrue(marker !in error.toString())
        assertTrue("/" !in error.toString())
        assertEquals(
            "EncryptedProfileFrame(profileStartOffset=8259, " +
                "profileEndExclusive=129979, events=0, " +
                "encryptedProfiles=121720 bytes)",
            NormalNonLinuxProfileFramer.frameBuild041202(syntheticSave(0)).toString(),
        )
    }

    @Test
    fun propagatesExistingUnsupportedHeaderContractWithoutGuessing() {
        val wrongVersion = syntheticSave(0).also { it.putU32Le(0, 102) }
        val versionError = assertFailsWith<UnsupportedSaveHeaderException> {
            NormalNonLinuxProfileFramer.frameBuild041202(wrongVersion)
        }
        assertEquals(102L, versionError.saveVersion)
        assertEquals("Build 04.12.02", versionError.gameVersion)

        val wrongBuild = syntheticSave(0).also {
            it.putSingleByteString(4, 16, "Build 99.99.99")
        }
        val buildError = assertFailsWith<UnsupportedSaveHeaderException> {
            NormalNonLinuxProfileFramer.frameBuild041202(wrongBuild)
        }
        assertEquals(103L, buildError.saveVersion)
        assertEquals("Build 99.99.99", buildError.gameVersion)
    }

    private fun syntheticSave(
        eventCount: Long,
        trailingSize: Int = 0,
        orderUsedCount: Int = 0,
        payoutUsedCount: Int = 0,
    ): ByteArray {
        val profileStart = expectedProfileStart(eventCount)
        val profileEnd = profileStart + SaveLayoutFacts.MERC_PROFILE_BLOCK_SIZE
        return ByteArray(profileEnd + trailingSize) { ((it * 29 + 17) and 0xff).toByte() }.also { save ->
            header.copyInto(save)
            save.putU32Le(EVENT_COUNT_OFFSET, eventCount)
            val laptopStart =
                EVENT_DATA_OFFSET +
                    eventCount.toInt() * SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE
            save[laptopStart + FIXTURE_BOBBY_RAY_ORDER_ARRAY_SIZE_OFFSET] = 9
            save[laptopStart + FIXTURE_BOBBY_RAY_ORDER_USED_COUNT_OFFSET] =
                orderUsedCount.toByte()
            save[laptopStart + FIXTURE_INSURANCE_PAYOUT_ARRAY_SIZE_OFFSET] = 7
            save[laptopStart + FIXTURE_INSURANCE_PAYOUT_USED_COUNT_OFFSET] =
                payoutUsedCount.toByte()
            encryptedProfiles.copyInto(save, profileStart)
        }
    }

    private fun prefixWithEventCount(size: Int, eventCount: Long): ByteArray =
        ByteArray(size).also { bytes ->
            header.copyInto(bytes, endIndex = minOf(header.size, bytes.size))
            if (bytes.size >= EVENT_DATA_OFFSET) bytes.putU32Le(EVENT_COUNT_OFFSET, eventCount)
        }

    private fun expectedProfileStart(eventCount: Long): Int =
        (EVENT_DATA_OFFSET +
            eventCount * SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE +
            SaveLayoutFacts.NORMAL_NON_LINUX_LAPTOP_FIXED_SIZE).toInt()

    private fun framingFailure(save: ByteArray): ProfileFramingException =
        assertFailsWith { NormalNonLinuxProfileFramer.frameBuild041202(save) }

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
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private data class TruncationCase(
        val inputSize: Int,
        val eventCount: Long,
        val stage: ProfileFramingStage,
        val requiredEndExclusive: Long,
    )

    private companion object {
        const val EVENT_COUNT_OFFSET = 815
        const val EVENT_DATA_OFFSET = 819
        const val PROFILE_VECTOR_CIPHERTEXT_OFFSET = 121769
        const val PROFILE_VECTOR_CIPHERTEXT_END = 243489
        // Test-owned format oracle: keep these independent of the production offsets under test.
        const val FIXTURE_BOBBY_RAY_ORDER_ARRAY_SIZE_OFFSET = 7276
        const val FIXTURE_BOBBY_RAY_ORDER_USED_COUNT_OFFSET = 7277
        const val FIXTURE_INSURANCE_PAYOUT_ARRAY_SIZE_OFFSET = 7284
        const val FIXTURE_INSURANCE_PAYOUT_USED_COUNT_OFFSET = 7285
    }
}
