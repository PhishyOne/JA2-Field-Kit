package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import com.phishtopia.ja2fieldkit.core.SaveInterpretationAdmissionException
import com.phishtopia.ja2fieldkit.core.model.*
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Whole saves use only manifested project-generated resources and test-owned serialization. */
class NormalNonLinuxRosterDecoderTest {
    private val header = resource("/fixtures/synthetic-build-04.12.02-header-v1.bin")
    private val vector = resource("/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin")
    private val key = vector.copyOfRange(0, 49)
    private val profilePlaintext = withSyntheticNames(vector.copyOfRange(49, 121_769))
    private val encryptedProfiles = encryptRecords(profilePlaintext, 716)

    @Test
    fun combinedLiveStatePreservesAllTenSignedFactsAndProfileBinding() {
        val offsets = listOf(868, 917, 880, 840, 886, 849, 1377, 916, 1378, 1372)
        val values = listOf(-128, 127, -3, -4, -5, -6, -7, -8, -9, -10)
        val save = syntheticSave(mapOf(
            0 to SoldierSpec(42, pathNodeCount = 2, hasKeyring = true, beforeChecksum = { bytes ->
                offsets.zip(values).forEach { (offset, value) -> bytes[offset] = value.toByte() }
            }),
            5 to SoldierSpec(7),
            19 to SoldierSpec(255, vehicle = true, hasKeyring = true),
        ), 2)
        val original = save.copyOf()
        val inspector = admittedInspector()
        val result = assertIs<LiveMercStateInspectionResult.Success>(inspector.inspectLiveMercState(save))
        assertEquals(listOf(42, 7), result.mercs.map { it.profileIndex })
        assertEquals(LiveMercStats(-128, 127, -3, -4, -5, -6, -7, -8, -9, -10), result.mercs[0].stats)
        assertEquals(LiveMercStats(55, 65, 45, 35, 40, 7, 50, 30, 20, 25), result.mercs[1].stats)
        val inventories = assertIs<LiveInventoryInspectionResult.Success>(inspector.inspectLiveInventory(save))
        assertEquals(inventories.format, result.format)
        result.mercs.zip(inventories.inventories).forEach { (live, inventory) ->
            assertEquals(inventory.profileIndex, live.profileIndex)
            assertEquals(inventory.slots.map { LiveInventorySlot(it.role, it.objectRecord.itemId,
                it.objectRecord.objectCount) }, live.slots)
        }
        assertContentEquals(original, save)
        save.fill(0)
        assertEquals(-128, result.mercs.first().stats.life)
        assertEquals(300, result.mercs.first().slots.first().itemId)
        assertFailsWith<UnsupportedOperationException> { (result.mercs as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.mercs.first().slots as MutableList).clear() }
    }

    @Test
    fun liveInventoriesBindEverySentinelToItsRosterProfileAndPreserveInput() {
        fun sentinel(player: Int, slot: Int) = ByteArray(36) { byte ->
            (player * 43 + slot * 17 + byte * 7).toByte()
        }.also {
            it.putU16Le(0, 50_000 + player * 100 + slot)
            it[2] = (slot + 1).toByte()
        }
        fun inventory(player: Int): (ByteArray) -> Unit = { soldier ->
            repeat(19) { slot -> sentinel(player, slot).copyInto(soldier, 12 + 36 * slot) }
        }
        val save = syntheticSave(
            mapOf(
                0 to SoldierSpec(42, pathNodeCount = 2, hasKeyring = true, beforeChecksum = inventory(1)),
                5 to SoldierSpec(7, beforeChecksum = inventory(2)),
                19 to SoldierSpec(255, vehicle = true, hasKeyring = true),
            ),
            2,
        )
        val original = save.copyOf()
        val inspector = admittedInspector()
        val roster = inspector.parseBuild041202NormalNonLinuxRoster(save)
        val result = assertIs<LiveInventoryInspectionResult.Success>(inspector.inspectLiveInventory(save))
        assertEquals(listOf(42, 7), result.inventories.map { it.profileIndex })
        assertEquals(roster.map { it.name }, result.inventories.map { it.name })
        assertEquals(roster.map { it.nickname }, result.inventories.map { it.nickname })
        val roles = listOf("HELMET", "VEST", "LEGS", "HEAD_1", "HEAD_2", "MAIN_HAND", "OFF_HAND",
            "BIG_POCKET_1", "BIG_POCKET_2", "BIG_POCKET_3", "BIG_POCKET_4", "SMALL_POCKET_1",
            "SMALL_POCKET_2", "SMALL_POCKET_3", "SMALL_POCKET_4", "SMALL_POCKET_5",
            "SMALL_POCKET_6", "SMALL_POCKET_7", "SMALL_POCKET_8")
        result.inventories.forEachIndexed { player, merc ->
            assertEquals(19, merc.slots.size)
            assertEquals(roles, merc.slots.map { it.role.name })
            assertEquals((0..18).toList(), merc.slots.map { it.role.slotIndex })
            merc.slots.forEachIndexed { slot, entry ->
                assertContentEquals(sentinel(player + 1, slot), entry.objectRecord.rawRecord)
                assertIs<InventoryPayload.Unknown>(entry.objectRecord.payload)
                entry.objectRecord.rawRecord.fill(0)
                assertContentEquals(sentinel(player + 1, slot), entry.objectRecord.rawRecord)
            }
            assertFailsWith<UnsupportedOperationException> { (merc.slots as MutableList).clear() }
        }
        assertFailsWith<UnsupportedOperationException> { (result.inventories as MutableList).clear() }
        assertEquals(roster, inspector.parseBuild041202NormalNonLinuxRoster(save))
        assertEquals(roster, assertIs<SaveInspectionV01Result.Success>(inspector.inspectV01(save)).roster)
        assertContentEquals(original, save)
        save.fill(0)
        assertContentEquals(sentinel(1, 0), result.inventories[0].slots[0].objectRecord.rawRecord)
    }

    @Test
    fun liveInventoryPublicFailuresAreSanitizedAndUseTheSameAdmission() {
        val save = syntheticSave(mapOf(0 to SoldierSpec(7), 19 to SoldierSpec(42, hasKeyring = true)), 2)
        val truncated = save.copyOf(save.size - 1)
        val original = truncated.copyOf()
        val inspector = admittedInspector()
        val result = assertIs<LiveInventoryInspectionResult.Failure>(inspector.inspectLiveInventory(truncated))
        assertEquals(SaveInspectionFailureKind.TRUNCATED_INPUT, result.failure.kind)
        assertEquals(SaveInspectionDiagnostic.LAYOUT_TRUNCATED, result.failure.diagnostic)
        assertEquals(assertIs<SaveInspectionV01Result.Failure>(inspector.inspectV01(truncated)).failure, result.failure)
        assertContentEquals(original, truncated)
        val production = Ja2SaveInspector()
        val denied = assertIs<LiveInventoryInspectionResult.Failure>(production.inspectLiveInventory(save))
        assertEquals(production.detect(save).compatibility, denied.format.compatibility)
        val liveDenied = assertIs<LiveMercStateInspectionResult.Failure>(production.inspectLiveMercState(save))
        assertEquals(denied.format, liveDenied.format)
        assertEquals(denied.failure, liveDenied.failure)
        assertEquals(assertIs<SaveInspectionV01Result.Failure>(production.inspectV01(save)).failure, denied.failure)
    }

    @Test
    fun liveInventoryKeepsCanonicalEmptyEvenWithOpaqueResidualBytes() {
        val save = syntheticSave(mapOf(0 to SoldierSpec(7, beforeChecksum = { soldier ->
            soldier.fill(0, 12, 696)
            soldier[16] = 99
        })), 1)
        val merc = NormalNonLinuxRosterDecoder.decodeInventoriesBuild041202(save).single()
        assertEquals(19, merc.slots.size)
        assertTrue(merc.slots.all { it.objectRecord.payload == InventoryPayload.Empty })
        assertEquals(99, merc.slots[0].objectRecord.rawRecord[4].toInt())
    }

    @Test
    fun sparseSlotsJoinByProfileIndexAndEveryEncryptedRecordResets() {
        val save = syntheticSave(
            mapOf(
                0 to SoldierSpec(profileId = 7, pathNodeCount = 2, hasKeyring = true),
                5 to SoldierSpec(profileId = 42),
                19 to SoldierSpec(profileId = 169, vehicle = true, pathNodeCount = 1),
            ),
            headerCount = 2,
        )
        val inspector = admittedInspector()

        val detection = inspector.detect(save)
        val roster = inspector.parseBuild041202NormalNonLinuxRoster(save)

        assertEquals(SaveCompatibility.SUPPORTED, detection.compatibility)
        assertEquals(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX, detection.layout)
        assertEquals(listOf(7, 42), roster.map { it.profileIndex })
        assertEquals(listOf("Synthetic 7", "Synthetic 42"), roster.map { it.name })
        assertEquals("P7", roster[0].nickname)
        assertEquals(profilePlaintext[7 * 716 + 297].toInt(), roster[0].stats.health)
        assertEquals(profilePlaintext[42 * 716 + 411].toInt(), roster[1].stats.mechanical)
        assertFailsWith<UnsupportedOperationException> { (roster as MutableList).clear() }
    }

    @Test
    fun publicApiRejectsTheSameHeaderBodyDigestMismatchAsDetectionWithoutMutation() {
        val save = syntheticSave(emptyMap(), 0).also {
            // Preserve the admitted synthetic header selector while retaining the roster-shaped tail.
            it[291] = header[291]
        }
        val original = save.copyOf()
        val inspector = Ja2SaveInspector()

        val detection = inspector.detect(save)
        val failure = assertFailsWith<SaveInterpretationAdmissionException> {
            inspector.parseBuild041202NormalNonLinuxRoster(save)
        }

        assertEquals(SaveCompatibility.INCONSISTENT, detection.compatibility)
        assertEquals(SaveDetectionReason.ROTATION_DIGEST_MISMATCH, detection.reason)
        assertEquals(detection.compatibility, failure.compatibility)
        assertEquals(detection.layout, failure.layout)
        assertEquals(detection.reason, failure.reason)
        assertContentEquals(original, save)
    }

    @Test
    fun acceptsExactlyEighteenNonVehicleMercs() {
        val roster = NormalNonLinuxRosterDecoder.decodeBuild041202(
            syntheticSave((0 until 18).associateWith { SoldierSpec(profileId = 100 + it) }, 18),
        )

        assertEquals(18, roster.size)
        assertEquals((100 until 118).toList(), roster.map { it.profileIndex })
    }

    @Test
    fun vehiclesInHighSlotsAreValidButExcludedFromRosterAndHeaderCount() {
        val roster = NormalNonLinuxRosterDecoder.decodeBuild041202(
            syntheticSave(
                mapOf(
                    17 to SoldierSpec(profileId = 3),
                    18 to SoldierSpec(profileId = 250, vehicle = true),
                    19 to SoldierSpec(profileId = 255, vehicle = true, hasKeyring = true),
                ),
                1,
            ),
        )

        assertEquals(listOf(3), roster.map { it.profileIndex })
    }

    @Test
    fun distinguishesMissingAndMalformedOuterActiveMarker() {
        val missing = failure(
            RosterMembershipFailure.TRUNCATED_ACTIVE_MARKER,
            syntheticSave(emptyMap(), 0).copyOf(profileEnd()),
            slot = 0,
        )
        assertTruncation(
            missing,
            RosterMembershipStage.ACTIVE_MARKER,
            offset = profileEnd(),
            requiredEndExclusive = profileEnd() + 1L,
        )

        val save = syntheticSave(emptyMap(), 0)
        save[profileEnd()] = 2
        val malformed = failure(RosterMembershipFailure.INVALID_ACTIVE_MARKER, save, slot = 0)
        assertEquals(RosterMembershipStage.ACTIVE_MARKER, malformed.stage)
        assertEquals(profileEnd().toLong(), malformed.absoluteOffset)
        assertEquals(null, malformed.requiredEndExclusive)
    }

    @Test
    fun rejectsTruncatedSoldierRecord() {
        val complete = syntheticSave(mapOf(19 to SoldierSpec(4)), 1)
        val recordStart = profileEnd() + 20
        val error = failure(
            RosterMembershipFailure.TRUNCATED_SOLDIER_RECORD,
            complete.copyOf(recordStart + 2327),
            slot = 19,
        )
        assertTruncation(
            error,
            RosterMembershipStage.SOLDIER_RECORD,
            offset = recordStart,
            requiredEndExclusive = recordStart + 2328L,
        )
    }

    @Test
    fun rejectsTruncatedPathCountAndHugeOrTruncatedPathData() {
        val complete = syntheticSave(mapOf(19 to SoldierSpec(4, pathNodeCount = 2)), 1)
        val pathCountOffset = profileEnd() + 20 + 2328
        val pathCountError = failure(
            RosterMembershipFailure.TRUNCATED_PATH_COUNT,
            complete.copyOf(pathCountOffset + 3),
            slot = 19,
        )
        assertTruncation(
            pathCountError,
            RosterMembershipStage.PATH_COUNT,
            offset = pathCountOffset,
            requiredEndExclusive = pathCountOffset + 4L,
        )
        val pathDataError = failure(
            RosterMembershipFailure.TRUNCATED_PATH_DATA,
            complete.copyOf(pathCountOffset + 4 + 39),
            slot = 19,
        )
        assertTruncation(
            pathDataError,
            RosterMembershipStage.PATH_DATA,
            offset = pathCountOffset + 4,
            requiredEndExclusive = pathCountOffset + 4L + 40L,
        )
        assertEquals(2L, pathDataError.pathNodeCount)

        val huge = complete.copyOf(pathCountOffset + 4).also {
            it.putU32Le(pathCountOffset, 0xffff_ffffL)
        }
        val error = failure(RosterMembershipFailure.TRUNCATED_PATH_DATA, huge, slot = 19)
        assertEquals(0xffff_ffffL, error.pathNodeCount)
        assertEquals(pathCountOffset + 4L + 0xffff_ffffL * 20L, error.requiredEndExclusive)
    }

    @Test
    fun distinguishesMissingAndMalformedKeyringMarkerAndRejectsTruncatedKeyring() {
        val noKeys = syntheticSave(mapOf(19 to SoldierSpec(4)), 1)
        val markerOffset = profileEnd() + 20 + 2328 + 4
        val missing = failure(
            RosterMembershipFailure.TRUNCATED_KEYRING_MARKER,
            noKeys.copyOf(markerOffset),
            slot = 19,
        )
        assertTruncation(
            missing,
            RosterMembershipStage.KEYRING_MARKER,
            offset = markerOffset,
            requiredEndExclusive = markerOffset + 1L,
        )

        noKeys[markerOffset] = 2
        val malformed = failure(RosterMembershipFailure.INVALID_KEYRING_MARKER, noKeys, slot = 19)
        assertEquals(RosterMembershipStage.KEYRING_MARKER, malformed.stage)
        assertEquals(markerOffset.toLong(), malformed.absoluteOffset)
        assertEquals(null, malformed.requiredEndExclusive)

        val withKeys = syntheticSave(mapOf(19 to SoldierSpec(4, hasKeyring = true)), 1)
        val keyringDataStart = markerOffset + 1
        val truncated = failure(
            RosterMembershipFailure.TRUNCATED_KEYRING_DATA,
            withKeys.copyOf(withKeys.size - 1),
            slot = 19,
        )
        assertTruncation(
            truncated,
            RosterMembershipStage.KEYRING_DATA,
            offset = keyringDataStart,
            requiredEndExclusive = keyringDataStart + 128L,
        )
    }

    @Test
    fun rejectsEachInnerIdentityInvariant() {
        val cases = listOf(
            RosterMembershipFailure.INVALID_SOLDIER_ID to { bytes: ByteArray -> bytes[0] = 9 },
            RosterMembershipFailure.INVALID_INNER_ACTIVE to { bytes: ByteArray -> bytes[751] = 0 },
            RosterMembershipFailure.INVALID_TEAM to { bytes: ByteArray -> bytes[752] = 1 },
            RosterMembershipFailure.MISSING_PC_FLAG to { bytes: ByteArray -> bytes.putU32Le(8, 0) },
        )
        for ((reason, mutation) in cases) {
            failure(
                reason,
                syntheticSave(mapOf(3 to SoldierSpec(4, beforeChecksum = mutation)), 1),
                slot = 3,
            )
        }
    }

    @Test
    fun rejectsChecksumMismatch() {
        val save = syntheticSave(mapOf(2 to SoldierSpec(4)), 1)
        val ciphertextOffset = profileEnd() + 3 + 2208
        // A plaintext delta changes this and all later ciphertext in this one operation.
        for (index in ciphertextOffset until profileEnd() + 3 + 2328) {
            save[index] = (save[index] + 1).toByte()
        }
        failure(RosterMembershipFailure.CHECKSUM_MISMATCH, save, slot = 2)
    }

    @Test
    fun checksumAcceptsSyntheticSignedAndUnsignedBoundaryContributions() {
        val boundaryFields: (ByteArray) -> Unit = { bytes ->
            val statPairs = listOf(868 to 917, 880 to 840, 886 to 1377, 1372 to 916, 1378 to 849)
            statPairs.forEachIndexed { index, (addend, multiplier) ->
                bytes[addend] = if (index % 2 == 0) 0x80.toByte() else 0x7f.toByte()
                bytes[multiplier] = if (index % 2 == 0) 0x7f.toByte() else 0x80.toByte()
            }
            repeat(19) { inventorySlot ->
                bytes.putU16Le(12 + inventorySlot * 36, 0xffff - inventorySlot)
                bytes[14 + inventorySlot * 36] = (0xff - inventorySlot).toByte()
            }
        }
        val roster = NormalNonLinuxRosterDecoder.decodeBuild041202(
            syntheticSave(mapOf(0 to SoldierSpec(7, beforeChecksum = boundaryFields)), 1),
        )

        assertEquals(listOf(7), roster.map { it.profileIndex })
    }

    @Test
    fun rejectsInvalidAndDuplicateNonVehicleProfileIds() {
        failure(
            RosterMembershipFailure.INVALID_PROFILE_ID,
            syntheticSave(mapOf(0 to SoldierSpec(170)), 1),
            slot = 0,
        )
        failure(
            RosterMembershipFailure.DUPLICATE_PROFILE_ID,
            syntheticSave(mapOf(0 to SoldierSpec(7), 4 to SoldierSpec(7)), 2),
            slot = 4,
        )
    }

    @Test
    fun headerMercCountIsOnlyACrossCheckAfterAllTwentySlots() {
        val error = failure(
            RosterMembershipFailure.HEADER_COUNT_MISMATCH,
            syntheticSave(mapOf(19 to SoldierSpec(7)), 2),
        )
        assertEquals(2, error.expectedCount)
        assertEquals(1, error.actualCount)
    }

    @Test
    fun requiresCanonicalZeroThroughNineteenPlayerRange() {
        for ((first, last) in listOf(1 to 20, 0 to 18, 5 to 4)) {
            val save = syntheticSave(emptyMap(), 0).also {
                it[436] = first.toByte()
                it[437] = last.toByte()
            }
            failure(RosterMembershipFailure.INVALID_PLAYER_TEAM_RANGE, save)
        }
    }

    @Test
    fun diagnosticsContainStructuralMetadataOnlyAndInputIsUnchanged() {
        val marker = "PRIVATE_NAME_PATH_OR_KEY"
        val markerBytes = marker.encodeToByteArray()
        val markerOffset = profileEnd() + 1
        val save = syntheticSave(emptyMap(), 0).copyOf(markerOffset + markerBytes.size)
        markerBytes.copyInto(save, markerOffset)
        save[profileEnd()] = 3
        val original = save.copyOf()

        val error = failure(RosterMembershipFailure.INVALID_ACTIVE_MARKER, save, slot = 0)

        assertTrue(marker !in error.toString())
        assertTrue("SaveRotationTable" !in error.toString())
        assertContentEquals(original, save)
    }

    private fun syntheticSave(slots: Map<Int, SoldierSpec>, headerCount: Int): ByteArray {
        require(slots.keys.all { it in 0..19 })
        val profileStart = 819 + 7_440
        val prefix = ByteArray(profileStart + 121_720).also { save ->
            header.copyInto(save)
            save[291] = headerCount.toByte()
            save[436] = 0
            save[437] = 19
            save.putU32Le(815, 0)
            save[819 + 7_276] = 9
            save[819 + 7_277] = 0
            save[819 + 7_284] = 7
            save[819 + 7_285] = 0
            encryptedProfiles.copyInto(save, profileStart)
        }
        val tail = ByteArrayOutputStream()
        repeat(20) { slot ->
            val spec = slots[slot]
            tail.write(if (spec == null) 0 else 1)
            if (spec != null) {
                tail.write(encryptRecords(soldierRecord(slot, spec), 2328))
                tail.write(u32Le(spec.pathNodeCount.toLong()))
                repeat(spec.pathNodeCount * 20) { tail.write((slot * 17 + it) and 0xff) }
                tail.write(if (spec.hasKeyring) 1 else 0)
                if (spec.hasKeyring) repeat(128) { tail.write((slot + it * 3) and 0xff) }
            }
        }
        return prefix + tail.toByteArray()
    }

    private fun soldierRecord(slot: Int, spec: SoldierSpec): ByteArray = ByteArray(2328).also { bytes ->
        bytes[0] = slot.toByte()
        bytes.putU32Le(8, if (spec.vehicle) 0x0000_8008 else 0x0000_0008)
        repeat(19) { inventorySlot ->
            bytes.putU16Le(12 + inventorySlot * 36, 300 + slot + inventorySlot)
            bytes[14 + inventorySlot * 36] = ((slot + inventorySlot) % 4).toByte()
        }
        bytes[840] = (30 + slot).toByte()
        bytes[849] = (2 + slot % 8).toByte()
        bytes[868] = (50 + slot).toByte()
        bytes[880] = (40 + slot).toByte()
        bytes[886] = (35 + slot).toByte()
        bytes[916] = (25 + slot).toByte()
        bytes[917] = (60 + slot).toByte()
        bytes[1372] = (20 + slot).toByte()
        bytes[1377] = (45 + slot).toByte()
        bytes[1378] = (15 + slot).toByte()
        bytes[751] = 1
        bytes[752] = 0
        bytes[1825] = spec.profileId.toByte()
        spec.beforeChecksum(bytes)
        bytes.putU32Le(2208, soldierChecksum(bytes))
    }

    // Test-owned recurrence over literal format offsets; production constants are not referenced.
    private fun soldierChecksum(bytes: ByteArray): Long {
        val statPairs = listOf(868 to 917, 880 to 840, 886 to 1377, 1372 to 916, 1378 to 849)
        fun signed(offset: Int): Long = bytes[offset].toLong()
        fun wrap(value: Long): Long = value and 0xffff_ffffL
        var sum = statPairs.fold(1L) { checksum, (addend, multiplier) ->
            wrap((checksum + 1 + signed(addend)) * (1 + signed(multiplier)))
        }
        sum = wrap(sum + 1 + (bytes[1825].toInt() and 0xff))
        repeat(19) { slot ->
            sum = wrap(
                sum +
                    bytes.u16Le(12 + slot * 36) +
                    (bytes[14 + slot * 36].toInt() and 0xff),
            )
        }
        return sum
    }

    private fun withSyntheticNames(source: ByteArray): ByteArray = source.copyOf().also { bytes ->
        repeat(170) { profile ->
            bytes.putUtf16Le(profile * 716, 30, "Synthetic $profile")
            bytes.putUtf16Le(profile * 716 + 60, 10, "P$profile")
        }
    }

    // Independent test-only forward transform. Feedback and key position reset for each record.
    private fun encryptRecords(plaintext: ByteArray, recordSize: Int): ByteArray {
        require(plaintext.size % recordSize == 0)
        val encrypted = ByteArray(plaintext.size)
        repeat(plaintext.size / recordSize) { record ->
            val start = record * recordSize
            var previousCiphertext = 0
            repeat(recordSize) { offset ->
                previousCiphertext = (
                    previousCiphertext +
                        (plaintext[start + offset].toInt() and 0xff) +
                        (key[offset % 49].toInt() and 0xff)
                    ) and 0xff
                encrypted[start + offset] = previousCiphertext.toByte()
            }
        }
        return encrypted
    }

    private fun failure(
        reason: RosterMembershipFailure,
        save: ByteArray,
        slot: Int? = null,
    ): RosterMembershipException =
        assertFailsWith<RosterMembershipException> {
            NormalNonLinuxRosterDecoder.decodeBuild041202(save)
        }.also {
            assertEquals(reason, it.reason)
            assertEquals(slot, it.slotIndex)
            val original = save.copyOf()
            val inventoryError = assertFailsWith<RosterMembershipException> {
                NormalNonLinuxRosterDecoder.decodeInventoriesBuild041202(save)
            }
            assertEquals(it.message, inventoryError.message)
            val liveError = assertFailsWith<RosterMembershipException> {
                NormalNonLinuxRosterDecoder.decodeLiveMercStatesBuild041202(save)
            }
            assertEquals(it.message, liveError.message)
            val inspector = admittedInspector()
            val liveFailure = assertIs<LiveMercStateInspectionResult.Failure>(inspector.inspectLiveMercState(save))
            assertEquals(assertIs<LiveInventoryInspectionResult.Failure>(
                inspector.inspectLiveInventory(save)).failure, liveFailure.failure)
            assertContentEquals(original, save)
        }

    private fun assertTruncation(
        error: RosterMembershipException,
        stage: RosterMembershipStage,
        offset: Int,
        requiredEndExclusive: Long,
    ) {
        assertEquals(stage, error.stage)
        assertEquals(offset.toLong(), error.absoluteOffset)
        assertEquals(requiredEndExclusive, error.requiredEndExclusive)
    }

    private fun admittedInspector(): Ja2SaveInspector =
        Ja2SaveInspector.withRotationDigestOracleForTesting(
            RotationDigestOracle { index ->
                if (index.value == 139) {
                    RotationTableDigest.from(SaveRotationTable.fromBytes(key))
                } else {
                    null
                }
            },
        )

    private fun profileEnd(): Int = 819 + 7_440 + 121_720

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Synthetic test resource is missing" }
            .use { it.readBytes() }

    private fun ByteArray.putUtf16Le(offset: Int, codeUnits: Int, value: String) {
        require(value.length < codeUnits)
        fill(0, offset, offset + codeUnits * 2)
        value.forEachIndexed { index, character -> putU16Le(offset + index * 2, character.code) }
    }

    private fun ByteArray.putU16Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.putU32Le(offset: Int, value: Int) = putU32Le(offset, value.toLong())

    private fun ByteArray.putU32Le(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun u32Le(value: Long): ByteArray = ByteArray(4).also { it.putU32Le(0, value) }

    private data class SoldierSpec(
        val profileId: Int,
        val vehicle: Boolean = false,
        val pathNodeCount: Int = 0,
        val hasKeyring: Boolean = false,
        val beforeChecksum: (ByteArray) -> Unit = {},
    )

}
