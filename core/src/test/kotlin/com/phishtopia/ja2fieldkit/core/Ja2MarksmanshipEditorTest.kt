package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.*

/** Only variants of validator-materialized, manifested project-authored resources are used. */
class Ja2MarksmanshipEditorTest {
    private val vector by lazy { resource("synthetic-build-04.12.02-profile-recovery-v1") }
    private val key by lazy { vector.copyOfRange(0, 49) }
    private val rotation by lazy { SaveRotationTable.fromBytes(key) }
    private val plain by lazy {
        vector.copyOfRange(49, 121769).also { records ->
            repeat(170) { id ->
                records.fill(0, id * 716, id * 716 + 80)
                records[id * 716] = ('A'.code + id % 26).toByte()
                records[id * 716 + 60] = ('a'.code + id % 26).toByte()
            }
        }
    }
    private val oracle by lazy {
        RotationDigestOracle { index -> if (index.value == 139) RotationTableDigest.from(rotation) else null }
    }
    private fun inspector() = Ja2SaveInspector.withRotationDigestOracleForTesting(oracle)
    private fun editor() = privilegedEditor(oracle)
    private fun request(id: Int = 7, newValue: Int = 55) =
        MarksmanshipEditRequest(id, plain[id * 716 + 353].toInt(), newValue)

    @Test
    fun rosterAndNonRosterTargetsPreserveEveryByteAndParsedFact() {
        for (id in listOf(0, 7, 42, 169)) {
            val source = save()
            val original = source.copyOf()
            val request = request(id)
            val success = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request))
            val candidate = success.candidateBytes
            assertContentEquals(original, source)
            assertEquals(source.size, candidate.size)
            val start = PROFILE_START + id * 716
            assertContentEquals(source.copyOfRange(0, start + 353), candidate.copyOfRange(0, start + 353))
            assertContentEquals(source.copyOfRange(start + 716, source.size), candidate.copyOfRange(start + 716, candidate.size))
            val decrypted = NormalSaveBlockDecryptor.decryptBlock(candidate.copyOfRange(start, start + 716), 716, rotation)
            assertEquals(55, decrypted[353].toInt())
            for (offset in 0 until 716) {
                if (offset != 353 && offset !in 696..699) {
                    assertEquals(plain[id * 716 + offset], decrypted[offset], "plain offset $offset")
                }
            }
            assertEquals(independentChecksum(decrypted), u32(decrypted, 696))
            val before = inspector().parseBuild041202NormalNonLinuxProfiles(source)
            val after = inspector().parseBuild041202NormalNonLinuxProfiles(candidate)
            assertEquals(before.map { if (it.profileId == id) it.copy(marksmanship = 55) else it }, after)
            val oldInspection = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
            val newInspection = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(candidate))
            assertEquals(oldInspection.campaign, newInspection.campaign)
            assertEquals(oldInspection.roster.map {
                if (it.profileIndex == id) it.copy(stats = it.stats.copy(marksmanship = 55)) else it
            }, newInspection.roster)
            // Entire soldier area is outside the exact target record, including current stats.
            assertContentEquals(source.copyOfRange(PROFILE_END, source.size), candidate.copyOfRange(PROFILE_END, candidate.size))
            assertEquals(MarksmanshipVerificationCheck.entries.toList(), success.verification.map { it.relation })
            assertTrue(success.verification.all { it.outcome == EditVerificationOutcome.PASSED })
        }
    }

    @Test
    fun identicalValueStillReparsesAndIsByteIdentical() {
        val work = mutableListOf<EditWork>()
        val editor = privilegedEditor(oracle, observe = work::add)
        val source = save()
        val request = request().let { it.copy(newMarksmanship = it.expectedCurrentMarksmanship) }
        val result = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor.edit(source, request))
        assertContentEquals(source, result.candidateBytes)
        assertEquals(result.provenance.sourceSha256, result.provenance.candidateSha256)
        assertEquals(EditWork.entries.toList(), work)
    }

    @Test
    fun provenanceIsDeterministicExactAndOutputIsDefensivelyOwned() {
        val source = save()
        val original = source.copyOf()
        val request = request(7, -128)
        val first = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request))
        val second = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request))
        assertEquals(first.provenance, second.provenance)
        assertEquals(hash(original), first.provenance.sourceSha256)
        assertEquals(hash(first.candidateBytes), first.provenance.candidateSha256)
        assertEquals(original.size, first.provenance.sourceSize)
        assertEquals(original.size, first.provenance.candidateSize)
        assertEquals(request, first.provenance.request)
        val canonical = ByteArray(12)
        putU32(canonical, 0, 7)
        putU32(canonical, 4, request.expectedCurrentMarksmanship.toLong())
        putU32(canonical, 8, -128)
        assertEquals(canonical.joinToString("") { "%02x".format(it) }, first.provenance.canonicalRequest)
        assertEquals(MarksmanshipCapabilityIdentity.PROJECT_AUTHORED_SYNTHETIC_V103, first.provenance.capabilityIdentity)
        assertEquals(1, first.provenance.capabilityRevision)
        assertEquals(1, first.provenance.transactionModelVersion)
        assertEquals(EditVerificationOutcome.PASSED, first.provenance.verificationOutcome)
        source.fill(0)
        first.candidateBytes.fill(0)
        assertContentEquals(second.candidateBytes, first.candidateBytes)
        assertFailsWith<UnsupportedOperationException> { (first.verification as MutableList).clear() }
        assertFalse(first.provenance.toString().contains("rotation", ignoreCase = true))
    }

    @Test
    fun callerMutationAfterSnapshotCannotChangeTransaction() {
        val source = save()
        val original = source.copyOf()
        val editor = privilegedEditor(oracle, observe = {
            if (it == EditWork.SOURCE_HASH) source.fill(0)
        })
        val result = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor.edit(source, request()))
        assertEquals(hash(original), result.provenance.sourceSha256)
        val control = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(original, request()))
        assertContentEquals(control.candidateBytes, result.candidateBytes)
    }

    @Test
    fun signedRepresentationEndpointsAreAcceptedWithoutGameplayClaims() {
        for (value in listOf(-128, -1, 0, 100, 127)) {
            assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(save(), request(newValue = value)))
        }
    }

    @Test
    fun mismatchDisabledCapabilityAndInvalidSourceFailClosed() {
        val source = save()
        fail(editor().edit(source, request().copy(expectedCurrentMarksmanship = 127)),
            MarksmanshipEditStage.PRECONDITION, MarksmanshipEditReason.EXPECTED_CURRENT_MISMATCH)
        fail(Ja2MarksmanshipEditor().edit(source, request()),
            MarksmanshipEditStage.CAPABILITY, MarksmanshipEditReason.CAPABILITY_DISABLED)
        assertIs<SaveInspectionV01Result.Failure>(Ja2SaveInspector().inspectV01(source))
        for (invalid in listOf(ByteArray(0), source.copyOf(20), source.copyOf().also { it[303] = 2 },
            source.copyOf().also { it[0] = 102 }, source.copyOf().also { it[PROFILE_START + 80]++ },
            source.copyOf().also { it[PROFILE_END] = 2 })) {
            fail(editor().edit(invalid, request()), MarksmanshipEditStage.SOURCE_PARSE, MarksmanshipEditReason.INVALID_SOURCE)
        }
    }

    @Test
    fun mandatoryCandidateAdmissionReparseAndCriticalVerificationCannotBeSkipped() {
        val source = save()
        val request = request()
        val candidate = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        val baseline = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
        val profiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
        for (mode in listOf("reject", "roster", "utf16", "campaign")) {
            val damaged = candidate.copyOf()
            when (mode) {
                "roster" -> damaged[PROFILE_END] = 2
                "utf16" -> corruptName(damaged)
                "campaign" -> damaged[284]++
            }
            var calls = 0
            val validatingInspector = Ja2SaveInspector.withDetectorForTesting { bytes ->
                calls++
                if (mode == "reject") SaveFormatDetector.detect(ByteArray(0))
                else SaveFormatDetector.detect(bytes, oracle)
            }
            val result = validatePrivilegedCandidate(
                privilegedEditor(oracle, inspector = validatingInspector), source, damaged,
                request, baseline, profiles, rotation,
            )
            if (mode == "campaign") {
                fail(result, MarksmanshipEditStage.VERIFICATION, MarksmanshipEditReason.VERIFICATION_MISMATCH)
                assertEquals(2, calls)
            } else {
                fail(result, MarksmanshipEditStage.CANDIDATE_PARSE, MarksmanshipEditReason.INVALID_CANDIDATE)
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun mutatingDetectorsAreRejectedOnSourceAndCandidateBeforeParsing() {
        for (mutateAt in listOf(1, 2, 3, 4)) {
            var calls = 0
            val alteredInspector = Ja2SaveInspector.withDetectorForTesting { bytes ->
                val detection = SaveFormatDetector.detect(bytes, oracle)
                if (++calls == mutateAt) bytes[284]++
                detection
            }
            val source = save()
            val original = source.copyOf()
            val result = privilegedEditor(oracle, inspector = alteredInspector).edit(source, request())
            fail(result,
                if (mutateAt <= 2) MarksmanshipEditStage.SOURCE_PARSE else MarksmanshipEditStage.CANDIDATE_PARSE,
                if (mutateAt <= 2) MarksmanshipEditReason.INVALID_SOURCE else MarksmanshipEditReason.INVALID_CANDIDATE)
            assertEquals(mutateAt, calls)
            assertContentEquals(original, source)
        }
    }

    @Test
    fun detectorCannotConcealInvalidUtf16ByRepairingAndReencryptingItsPrivateCopy() {
        val source = save()
        val request = request()
        val candidate = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        val baseline = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
        val profiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
        val repairingInspector = Ja2SaveInspector.withDetectorForTesting { bytes ->
            val record = NormalSaveBlockDecryptor.decryptBlock(
                bytes.copyOfRange(PROFILE_START, PROFILE_START + 716), 716, rotation,
            )
            record[0] = 'A'.code.toByte(); record[1] = 0
            encrypt(record).copyInto(bytes, PROFILE_START)
            SaveFormatDetector.detect(bytes, oracle).also { assertEquals(SaveCompatibility.SUPPORTED, it.compatibility) }
        }
        val damagedSource = source.copyOf().also(::corruptName)
        val damagedCandidate = candidate.copyOf().also(::corruptName)
        val originalSource = damagedSource.copyOf()
        val originalCandidate = damagedCandidate.copyOf()
        val editor = privilegedEditor(oracle, inspector = repairingInspector)
        fail(editor.edit(damagedSource, request), MarksmanshipEditStage.SOURCE_PARSE, MarksmanshipEditReason.INVALID_SOURCE)
        fail(validatePrivilegedCandidate(editor, source, damagedCandidate, request, baseline, profiles, rotation),
            MarksmanshipEditStage.CANDIDATE_PARSE, MarksmanshipEditReason.INVALID_CANDIDATE)
        assertContentEquals(originalSource, damagedSource)
        assertContentEquals(originalCandidate, damagedCandidate)
        assertEquals(SaveDetectionReason.DETECTOR_MUTATED_INPUT, repairingInspector.detect(damagedCandidate).reason)
    }

    private fun corruptName(bytes: ByteArray) {
        val record = NormalSaveBlockDecryptor.decryptBlock(
            bytes.copyOfRange(PROFILE_START, PROFILE_START + 716), 716, rotation,
        )
        record[0] = 0; record[1] = 0xdc.toByte() // Unpaired low surrogate; checksum inputs unchanged.
        encrypt(record).copyInto(bytes, PROFILE_START)
    }

    @Test
    fun exactCoreAndTighterCapabilitySizesAreEnforcedBeforeCandidateAllocation() {
        val source = save()
        val exact = source.copyOf(Ja2MarksmanshipEditor.MAX_SAVE_BYTES)
        val result = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(exact, request()))
        assertEquals(exact.size, result.candidateBytes.size)
        val work = mutableListOf<EditWork>()
        fun bounded(sourceMax: Int, candidateMax: Int) = privilegedEditor(
            oracle, SyntheticMarksmanshipCapability(sourceMax, candidateMax), work::add,
        )
        fail(bounded(source.size - 1, source.size).edit(source, request()),
            MarksmanshipEditStage.CAPABILITY, MarksmanshipEditReason.CAPABILITY_SIZE_LIMIT)
        assertEquals(listOf(EditWork.SOURCE_SNAPSHOT, EditWork.SOURCE_HASH), work)
        work.clear()
        fail(bounded(source.size, source.size - 1).edit(source, request()),
            MarksmanshipEditStage.SERIALIZATION, MarksmanshipEditReason.CANDIDATE_SIZE_LIMIT)
        assertFalse(EditWork.CANDIDATE_ALLOCATION in work)
        assertIs<MarksmanshipEditResult.VerifiedCandidate>(bounded(source.size, source.size).edit(source, request()))
    }

    @Test
    fun verifierRejectsEveryProfileAndRosterFactDriftAndPreservationDamage() {
        val source = save()
        val request = request()
        val candidate = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        val before = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
        val after = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(candidate))
        val sourceProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
        val candidateProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(candidate)
        fun verify(bytes: ByteArray = candidate,
                   inspection: SaveInspectionV01Result.Success = after,
                   profiles: List<com.phishtopia.ja2fieldkit.core.model.MercProfile> = candidateProfiles) =
            verifyMarksmanshipCandidate(source, bytes, request, before, inspection, sourceProfiles, profiles, rotation)
        assertTrue(verify())
        for (id in 0..169) {
            val altered = candidateProfiles.toMutableList()
            altered[id] = altered[id].copy(life = altered[id].life + 1)
            assertFalse(verify(profiles = altered), "profile $id")
        }
        val target = candidateProfiles[7]
        val variants = listOf(
            target.copy(profileId = 8), target.copy(name = "different"), target.copy(nickname = "different"),
            target.copy(lifeMax = 999), target.copy(agility = 999), target.copy(dexterity = 999),
            target.copy(strength = 999), target.copy(leadership = 999), target.copy(wisdom = 999),
            target.copy(marksmanship = 999), target.copy(explosives = 999), target.copy(mechanical = 999),
            target.copy(medical = 999), target.copy(experienceLevel = 999),
        )
        for (variant in variants) {
            assertFalse(verify(profiles = candidateProfiles.toMutableList().also { it[7] = variant }))
        }
        for (roster in listOf(after.roster.reversed(), after.roster.dropLast(1),
            after.roster.map { it.copy(name = "changed") },
            after.roster.map { it.copy(stats = it.stats.copy(health = 999)) })) {
            assertFalse(verify(inspection = SaveInspectionV01Result.Success.create(after.format, after.campaign, roster)))
        }
        assertFalse(verify(inspection = SaveInspectionV01Result.Success.create(
            after.format.copy(buildLabel = "other"), after.campaign, after.roster,
        )))
        assertFalse(verify(bytes = candidate.copyOf(candidate.size + 1)))
        // Opaque trailing bytes, soldier ciphertext, prefix, forbidden target plaintext, checksum.
        val start = PROFILE_START + 7 * 716
        for (offset in listOf(candidate.lastIndex, PROFILE_END + 1, start + 352, start + 500, start + 696)) {
            val damaged = candidate.copyOf().also { it[offset]++ }
            assertFalse(verify(bytes = damaged), "ciphertext offset $offset")
        }
    }

    private fun save(): ByteArray {
        val prefix = ByteArray(PROFILE_END)
        resource("synthetic-build-04.12.02-header-v1").copyInto(prefix)
        prefix[291] = 2
        prefix[436] = 0; prefix[437] = 19
        repeat(170) { id -> encrypt(plain.copyOfRange(id * 716, (id + 1) * 716)).copyInto(prefix, PROFILE_START + id * 716) }
        val output = ByteArrayOutputStream()
        output.write(prefix)
        repeat(20) { slot ->
            output.write(if (slot < 2) 1 else 0)
            if (slot < 2) {
                val id = if (slot == 0) 7 else 42
                val soldier = ByteArray(2328)
                soldier[0] = slot.toByte(); soldier[8] = 8; soldier[751] = 1
                soldier[1825] = id.toByte()
                // With zero stat/inventory inputs, five (+1)*1 steps then +1+profile.
                putU32(soldier, 2208, (7 + id).toLong())
                output.write(encrypt(soldier)); output.write(ByteArray(5))
            }
        }
        output.write(byteArrayOf(11, 22, 33)) // Opaque sentinel suffix.
        return output.toByteArray()
    }

    private fun encrypt(record: ByteArray): ByteArray {
        var cumulative = 0
        return ByteArray(record.size) { index ->
            cumulative = (cumulative + (record[index].toInt() and 255) + (key[index % 49].toInt() and 255)) % 256
            cumulative.toByte()
        }
    }

    private fun independentChecksum(record: ByteArray): Long {
        // Test arithmetic uses arbitrary precision, reducing only once at the end.
        var value = java.math.BigInteger.ONE
        for ((a, b) in listOf(334 to 297, 405 to 335, 296 to 353, 261 to 411, 339 to 352)) {
            value = (value + (record[a].toLong() + 1).toBigInteger()) * (record[b].toLong() + 1).toBigInteger()
        }
        repeat(19) { index ->
            val item = (record[416 + index * 2].toInt() and 255) + ((record[417 + index * 2].toInt() and 255) shl 8)
            value += (item + (record[377 + index].toInt() and 255)).toBigInteger()
        }
        return value.mod(java.math.BigInteger.ONE.shiftLeft(32)).toLong()
    }

    private fun resource(id: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/$id.bin")).use { it.readBytes() }
    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    private fun u32(bytes: ByteArray, offset: Int): Long =
        (0..3).sumOf { (bytes[offset + it].toLong() and 255) shl (8 * it) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fail(result: MarksmanshipEditResult, stage: MarksmanshipEditStage, reason: MarksmanshipEditReason) {
        val failure = assertIs<MarksmanshipEditResult.Failure>(result)
        assertEquals(stage, failure.stage)
        assertEquals(reason, failure.reason)
    }

    companion object {
        private const val PROFILE_START = 819 + 7440
        private const val PROFILE_END = PROFILE_START + 170 * 716
    }
}
