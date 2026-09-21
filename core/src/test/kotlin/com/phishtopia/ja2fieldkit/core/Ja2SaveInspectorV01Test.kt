package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.RotationDigestOracle
import com.phishtopia.ja2fieldkit.core.format.RotationTableDigest
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetector
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.format.SaveRotationTable
import com.phishtopia.ja2fieldkit.core.model.CampaignSector
import com.phishtopia.ja2fieldkit.core.model.CampaignSummaryV01
import com.phishtopia.ja2fieldkit.core.model.MercStats
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionDiagnostic
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailureKind
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Whole saves use manifested project-generated resources and test-owned serialization only. */
class Ja2SaveInspectorV01Test {
    private val header = resource("/fixtures/synthetic-build-04.12.02-header-v1.bin")
    private val vector = resource("/fixtures/synthetic-build-04.12.02-profile-recovery-v1.bin")
    private val key = vector.copyOfRange(0, 49)
    private val profilePlaintext = withSyntheticNames(vector.copyOfRange(49, 121_769))
    private val encryptedProfiles = encryptRecords(profilePlaintext, PROFILE_RECORD_SIZE)

    @Test
    fun completeFacadeIsDeterministicUsesOneDetectionAndDoesNotMutateInput() {
        val save = syntheticSave(listOf(7, 42))
        val original = save.copyOf()
        var detectionCalls = 0
        val inspector = Ja2SaveInspector.withDetectorForTesting { snapshot ->
            detectionCalls++
            SaveFormatDetector.detect(snapshot, admittedOracle())
        }

        val first = assertIs<SaveInspectionV01Result.Success>(inspector.inspectV01(save))
        val second = assertIs<SaveInspectionV01Result.Success>(inspector.inspectV01(save))

        assertEquals(2, detectionCalls)
        assertEquals(first, second)
        assertContentEquals(original, save)
        assertEquals(SaveCompatibility.SUPPORTED, first.format.compatibility)
        assertEquals(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX, first.format.layout)
        assertEquals(103, first.format.saveVersion)
        assertEquals("04.12.02", first.format.buildLabel)
        assertEquals(
            CampaignSummaryV01(
                day = 0x1020_3040L,
                hour = 0x12,
                minute = 0x34,
                sector = CampaignSector(x = 0x1234, y = -0x1234, z = -2),
                playerMercCount = 2,
                balance = -123_456_789,
            ),
            first.campaign,
        )
        assertEquals(listOf(7, 42), first.roster.map { it.profileIndex })
        assertEquals(listOf("Synthetic 7", "Synthetic 42"), first.roster.map { it.name })
        assertEquals(listOf("P7", "P42"), first.roster.map { it.nickname })
        assertEquals(expectedStats(7), first.roster[0].stats)
        assertEquals(expectedStats(42), first.roster[1].stats)
        assertFailsWith<UnsupportedOperationException> {
            (first.roster as MutableList).clear()
        }
    }

    @Test
    fun retainedDetectorArraysCannotChangeParsedResultsOrLaterInterpretations() {
        val save = syntheticSave(listOf(7, 42))
        val original = save.copyOf()
        var retained: ByteArray? = null
        val inspector = Ja2SaveInspector.withDetectorForTesting { snapshot ->
            retained?.fill(0) // A previous detector call no longer owns any parser input.
            retained = snapshot
            SaveFormatDetector.detect(snapshot, admittedOracle())
        }
        val expected = admittedInspector()
        val result = inspector.inspectV01(save)
        retained!!.fill(0)
        assertEquals(expected.inspectV01(save), result)
        assertEquals(expected.parseBuild041202Header(save), inspector.parseBuild041202Header(save))
        assertEquals(expected.parseBuild041202NormalNonLinuxProfiles(save), inspector.parseBuild041202NormalNonLinuxProfiles(save))
        assertEquals(expected.parseBuild041202NormalNonLinuxRoster(save), inspector.parseBuild041202NormalNonLinuxRoster(save))
        val frame = inspector.frameBuild041202NormalNonLinuxProfiles(save)
        retained!!.fill(0)
        assertContentEquals(expected.frameBuild041202NormalNonLinuxProfiles(save).encryptedProfileBytes, frame.encryptedProfileBytes)
        assertEquals(expected.inspectV01(save), result)
        assertContentEquals(original, save)
    }

    @Test
    fun unknownUnsupportedTruncatedAndHeaderBodyMismatchAreSanitizedFailures() {
        val unsupported = header.copyOf().also { it[303] = 2 }
        val mismatch = syntheticSave(listOf(7, 42))
        val cases = listOf(
            Triple(
                ByteArray(64),
                SaveInspectionFailureKind.UNKNOWN_FORMAT,
                SaveInspectionDiagnostic.IDENTITY_UNKNOWN,
            ),
            Triple(
                unsupported,
                SaveInspectionFailureKind.UNSUPPORTED_VARIANT,
                SaveInspectionDiagnostic.VARIANT_UNSUPPORTED,
            ),
            Triple(
                header.copyOf(20),
                SaveInspectionFailureKind.TRUNCATED_INPUT,
                SaveInspectionDiagnostic.HEADER_TRUNCATED,
            ),
            Triple(
                mismatch,
                SaveInspectionFailureKind.INCONSISTENT_INPUT,
                SaveInspectionDiagnostic.HEADER_BODY_MISMATCH,
            ),
        )

        for ((bytes, kind, diagnostic) in cases) {
            val original = bytes.copyOf()
            val result = assertIs<SaveInspectionV01Result.Failure>(
                Ja2SaveInspector().inspectV01(bytes),
            )
            assertEquals(kind, result.failure.kind)
            assertEquals(diagnostic, result.failure.diagnostic)
            assertContentEquals(original, bytes)
            assertTrue(result.toString().none { it.code < 0x20 && it !in "\r\n\t" })
        }
    }

    @Test
    fun admittedPresentButInvalidRosterMarkersAreCorruptWithoutPartialResults() {
        val complete = syntheticSave(listOf(7, 42))
        val keyringMarkerOffset = PROFILE_END + 1 + SOLDIER_RECORD_SIZE + 4
        val marker = "PRIVATE_PAYLOAD_MUST_NOT_LEAK"
        val cases = listOf(
            "active marker" to complete.copyOf().also {
                it[PROFILE_END] = 2
                marker.encodeToByteArray().copyInto(it, PROFILE_END + 1)
            },
            "keyring marker" to complete.copyOf().also { it[keyringMarkerOffset] = 2 },
        )

        for ((variant, save) in cases) {
            val original = save.copyOf()
            val result = assertIs<SaveInspectionV01Result.Failure>(
                admittedInspector().inspectV01(save),
                variant,
            )

            assertEquals(SaveCompatibility.SUPPORTED, result.format.compatibility, variant)
            assertEquals(SaveInspectionFailureKind.CORRUPT_INPUT, result.failure.kind, variant)
            assertEquals(SaveInspectionDiagnostic.CONTENT_CORRUPT, result.failure.diagnostic, variant)
            assertTrue(marker !in result.toString(), variant)
            assertContentEquals(original, save, variant)
        }
    }

    @Test
    fun admittedRosterTailTruncationsReturnSanitizedTruncatedFailures() {
        val save = syntheticSave(listOf(7, 42))
        val soldierStart = PROFILE_END + 1
        val pathCountStart = soldierStart + SOLDIER_RECORD_SIZE
        val pathDataStart = pathCountStart + 4
        val cases = listOf(
            "active marker" to save.copyOf(PROFILE_END),
            "soldier record" to save.copyOf(soldierStart + SOLDIER_RECORD_SIZE - 1),
            "path count" to save.copyOf(pathCountStart + 3),
            "path data" to save.copyOf().also { it.putU32Le(pathCountStart, 1) }
                .copyOf(pathDataStart + 19),
            "keyring marker" to save.copyOf(pathDataStart),
            "keyring data" to save.copyOf().also { it[pathDataStart] = 1 }
                .copyOf(pathDataStart + 1 + 127),
        )

        for ((variant, truncated) in cases) {
            val original = truncated.copyOf()
            val result = assertIs<SaveInspectionV01Result.Failure>(
                admittedInspector().inspectV01(truncated),
                variant,
            )

            assertEquals(SaveCompatibility.SUPPORTED, result.format.compatibility, variant)
            assertEquals(SaveInspectionFailureKind.TRUNCATED_INPUT, result.failure.kind, variant)
            assertEquals(SaveInspectionDiagnostic.LAYOUT_TRUNCATED, result.failure.diagnostic, variant)
            assertContentEquals(original, truncated, variant)
        }
    }

    @Test
    fun publicV01ModelsDoNotExposeParserOrCryptographicInternals() {
        val modelTypes = listOf(
            com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat::class.java,
            CampaignSummaryV01::class.java,
            CampaignSector::class.java,
            com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailure::class.java,
            SaveInspectionV01Result.Success::class.java,
            SaveInspectionV01Result.Failure::class.java,
        )
        val forbidden = listOf(
            "offset",
            "digest",
            "rotation",
            "encryption",
            "key",
            "SaveFormatDetection",
            "SaveDetectionFacts",
            "Frame",
            "Parser",
        )

        val surface = modelTypes.flatMap { type ->
            type.declaredFields.map { "${it.name}:${it.genericType.typeName}" } +
                type.declaredMethods.map { "${it.name}:${it.genericReturnType.typeName}" }
        }.joinToString("\n")

        for (token in forbidden) {
            assertTrue(token !in surface, "v0.1 model surface leaked '$token'")
        }
    }

    private fun expectedStats(profile: Int): MercStats {
        fun signed(offset: Int): Int = profilePlaintext[profile * PROFILE_RECORD_SIZE + offset].toInt()
        return MercStats(
            health = signed(297),
            agility = signed(405),
            dexterity = signed(335),
            strength = signed(296),
            leadership = signed(341),
            wisdom = signed(355),
            experienceLevel = signed(352),
            marksmanship = signed(353),
            mechanical = signed(411),
            explosives = signed(339),
            medical = signed(261),
        )
    }

    private fun syntheticSave(profileIds: List<Int>): ByteArray {
        require(profileIds.size <= PLAYER_SLOT_COUNT)
        val prefix = ByteArray(PROFILE_END).also { save ->
            header.copyInto(save)
            save[291] = profileIds.size.toByte()
            save[436] = 0
            save[437] = (PLAYER_SLOT_COUNT - 1).toByte()
            save.putU32Le(815, 0)
            save[819 + 7_276] = 9
            save[819 + 7_277] = 0
            save[819 + 7_284] = 7
            save[819 + 7_285] = 0
            encryptedProfiles.copyInto(save, PROFILE_START)
        }
        val tail = ByteArrayOutputStream()
        repeat(PLAYER_SLOT_COUNT) { slot ->
            val profileId = profileIds.getOrNull(slot)
            tail.write(if (profileId == null) 0 else 1)
            if (profileId != null) {
                tail.write(encryptRecords(soldierRecord(slot, profileId), SOLDIER_RECORD_SIZE))
                tail.write(byteArrayOf(0, 0, 0, 0))
                tail.write(0)
            }
        }
        return prefix + tail.toByteArray()
    }

    private fun soldierRecord(slot: Int, profileId: Int): ByteArray =
        ByteArray(SOLDIER_RECORD_SIZE).also { bytes ->
            bytes[0] = slot.toByte()
            bytes.putU32Le(8, 8)
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
            bytes[1825] = profileId.toByte()
            bytes.putU32Le(2208, soldierChecksum(bytes))
        }

    private fun soldierChecksum(bytes: ByteArray): Long {
        var sum = 1L
        fun signed(offset: Int): Long = bytes[offset].toLong()
        fun wrap(value: Long): Long = value and 0xffff_ffffL
        sum = wrap(sum + 1 + signed(868))
        sum = wrap(sum * (1 + signed(917)))
        sum = wrap(sum + 1 + signed(880))
        sum = wrap(sum * (1 + signed(840)))
        sum = wrap(sum + 1 + signed(886))
        sum = wrap(sum * (1 + signed(1377)))
        sum = wrap(sum + 1 + signed(1372))
        sum = wrap(sum * (1 + signed(916)))
        sum = wrap(sum + 1 + signed(1378))
        sum = wrap(sum * (1 + signed(849)))
        sum = wrap(sum + 1 + (bytes[1825].toInt() and 0xff))
        repeat(19) { slot ->
            sum = wrap(sum + bytes.u16Le(12 + slot * 36))
            sum = wrap(sum + (bytes[14 + slot * 36].toInt() and 0xff))
        }
        return sum
    }

    private fun withSyntheticNames(source: ByteArray): ByteArray = source.copyOf().also { bytes ->
        repeat(170) { profile ->
            bytes.putUtf16Le(profile * PROFILE_RECORD_SIZE, 30, "Synthetic $profile")
            bytes.putUtf16Le(profile * PROFILE_RECORD_SIZE + 60, 10, "P$profile")
        }
    }

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
                        (key[offset % key.size].toInt() and 0xff)
                    ) and 0xff
                encrypted[start + offset] = previousCiphertext.toByte()
            }
        }
        return encrypted
    }

    private fun admittedInspector(): Ja2SaveInspector =
        Ja2SaveInspector.withRotationDigestOracleForTesting(admittedOracle())

    private fun admittedOracle(): RotationDigestOracle = RotationDigestOracle { index ->
        if (index.value == 139) {
            RotationTableDigest.from(SaveRotationTable.fromBytes(key))
        } else {
            null
        }
    }

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

    private companion object {
        const val PROFILE_RECORD_SIZE = 716
        const val PROFILE_START = 819 + 7_440
        const val PROFILE_END = PROFILE_START + 121_720
        const val PLAYER_SLOT_COUNT = 20
        const val SOLDIER_RECORD_SIZE = 2_328
    }
}
