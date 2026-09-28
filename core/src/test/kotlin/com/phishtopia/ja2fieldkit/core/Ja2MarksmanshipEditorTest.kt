package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.LiveMercStateInspectionResult
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
                records[id * 716 + 353] = 89
                putU32(records, id * 716 + 696, independentChecksum(records.copyOfRange(id * 716, (id + 1) * 716)))
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
    fun hiredTargetsPreserveEveryByteAndParsedFact() {
        for (id in listOf(7, 42)) {
            val source = save()
            val original = source.copyOf()
            val request = request(id)
            val success = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request))
            val candidate = success.candidateBytes
            assertContentEquals(original, source)
            assertEquals(source.size, candidate.size)
            val start = PROFILE_START + id * 716
            assertContentEquals(source.copyOfRange(0, start + 353), candidate.copyOfRange(0, start + 353))
            val soldierStart = soldierStart(id)
            for (offset in source.indices) {
                if (offset !in start + 353 until start + 716 && offset !in soldierStart + 1377 until soldierStart + 2328) {
                    assertEquals(source[offset], candidate[offset], "outside records/prefix $offset")
                }
            }
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
            val oldSoldier = NormalSaveBlockDecryptor.decryptBlock(source.copyOfRange(soldierStart, soldierStart + 2328), 2328, rotation)
            val newSoldier = NormalSaveBlockDecryptor.decryptBlock(candidate.copyOfRange(soldierStart, soldierStart + 2328), 2328, rotation)
            assertEquals(55, newSoldier[1377].toInt())
            for (offset in oldSoldier.indices) {
                if (offset != 1377 && offset !in 2208..2211) assertEquals(oldSoldier[offset], newSoldier[offset], "soldier $offset")
            }
            assertEquals(independentSoldierChecksum(newSoldier), u32(newSoldier, 2208))
            assertFalse(u32(oldSoldier, 2208) == u32(newSoldier, 2208))
            assertFalse(u32(plain, id * 716 + 696) == u32(decrypted, 696))
            val oldLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(source))
            val newLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(candidate))
            assertTrue(verifyLiveMarksmanship(request, oldInspection.format, oldLive, newLive))
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
        val request = request(7, 0)
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
        putU32(canonical, 8, 0)
        assertEquals(canonical.joinToString("") { "%02x".format(it) }, first.provenance.canonicalRequest)
        assertEquals(MarksmanshipCapabilityIdentity.PROJECT_AUTHORED_SYNTHETIC_V103, first.provenance.capabilityIdentity)
        assertEquals(1, first.provenance.capabilityRevision)
        assertEquals(2, first.provenance.transactionModelVersion)
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
    fun gameplayEndpointsAreAccepted() {
        for (value in listOf(0, 100)) {
            assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(save(), request(newValue = value)))
        }
    }

    @Test
    fun admittedV102ReadDoesNotGrantEditingAuthority() {
        val source = save().also { it[0] = 102 }
        val original = source.copyOf()
        assertEquals(SaveCompatibility.SUPPORTED, inspector().detect(source).compatibility)
        assertEquals(SaveLayout.NORMAL_V102_BUILD_041202_NON_LINUX, inspector().detect(source).layout)
        fail(Ja2MarksmanshipEditor().edit(source, request()),
            MarksmanshipEditStage.CAPABILITY, MarksmanshipEditReason.CAPABILITY_DISABLED)
        fail(editor().edit(source, request()),
            MarksmanshipEditStage.SOURCE_PARSE, MarksmanshipEditReason.UNSUPPORTED_FORMAT)
        assertContentEquals(original, source)
    }

    @Test
    fun mismatchDisabledCapabilityAndInvalidSourceFailClosed() {
        val source = save()
        fail(editor().edit(source, request().copy(expectedCurrentMarksmanship = 88)),
            MarksmanshipEditStage.PRECONDITION, MarksmanshipEditReason.EXPECTED_CURRENT_MISMATCH)
        fail(Ja2MarksmanshipEditor().edit(source, request()),
            MarksmanshipEditStage.CAPABILITY, MarksmanshipEditReason.CAPABILITY_DISABLED)
        assertIs<SaveInspectionV01Result.Failure>(Ja2SaveInspector().inspectV01(source))
        for (invalid in listOf(ByteArray(0), source.copyOf(20), source.copyOf().also { it[303] = 2 },
            source.copyOf().also { it[0] = 104 }, source.copyOf().also { it[PROFILE_START + 80]++ },
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
                assertEquals(4, calls)
            } else {
                fail(result, MarksmanshipEditStage.CANDIDATE_PARSE, MarksmanshipEditReason.INVALID_CANDIDATE)
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun mutatingDetectorsAreRejectedOnSourceAndCandidateBeforeParsing() {
        for (mutateAt in listOf(1, 2, 3, 4, 5, 6, 7)) {
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
                if (mutateAt <= 3) MarksmanshipEditStage.SOURCE_PARSE else MarksmanshipEditStage.CANDIDATE_PARSE,
                if (mutateAt <= 3) MarksmanshipEditReason.INVALID_SOURCE else MarksmanshipEditReason.INVALID_CANDIDATE)
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

    @Test
    fun nonRosterVehicleAndDisagreementFailClosed() {
        for (id in listOf(0, 169)) fail(editor().edit(save(), request(id)),
            MarksmanshipEditStage.PRECONDITION, MarksmanshipEditReason.TARGET_NOT_HIRED)
        val disagreement = save().also { rewriteSoldier(it, 7) { bytes -> bytes[1377] = 88 } }
        fail(editor().edit(disagreement, request()), MarksmanshipEditStage.PRECONDITION,
            MarksmanshipEditReason.PROFILE_LIVE_DISAGREEMENT)
        val vehicle = save().also {
            rewriteSoldier(it, 7) { bytes -> bytes[9] = 0x80.toByte() }
            it[291] = 1
        }
        // Header count participates in rotation selection; bind this synthetic variant's exact index.
        val vehicleIndex = NormalSaveEncryptionSelector.select(
            NormalEncryptionHeaderInputs.fromBuild041202(SaveHeaderParser.parseBuild041202(vehicle)))
        val vehicleOracle = RotationDigestOracle { index ->
            if (index == vehicleIndex) RotationTableDigest.from(rotation) else null
        }
        assertIs<SaveInspectionV01Result.Success>(
            Ja2SaveInspector.withRotationDigestOracleForTesting(vehicleOracle).inspectV01(vehicle))
        fail(privilegedEditor(vehicleOracle).edit(vehicle, request()), MarksmanshipEditStage.PRECONDITION,
            MarksmanshipEditReason.TARGET_NOT_HIRED)
        val duplicate = save().also { rewriteSoldier(it, 42) { bytes -> bytes[1825] = 7 } }
        fail(editor().edit(duplicate, request()), MarksmanshipEditStage.SOURCE_PARSE, MarksmanshipEditReason.INVALID_SOURCE)
    }

    @Test
    fun soldierOnlyProfileOnlyAndUnrelatedPlaintextDamageCannotVerify() {
        val source = save()
        val candidate = assertIs<MarksmanshipEditResult.VerifiedCandidate>(editor().edit(source, request())).candidateBytes
        val before = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
        val profiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
        for (mode in listOf("profile-only", "soldier-only", "inventory", "metadata", "other-stat", "soldier-checksum")) {
            val damaged = candidate.copyOf()
            when (mode) {
                "profile-only" -> source.copyInto(damaged, soldierStart(7), soldierStart(7), soldierStart(7) + 2328)
                "soldier-only" -> source.copyInto(damaged, PROFILE_START + 7 * 716, PROFILE_START + 7 * 716, PROFILE_START + 8 * 716)
                "inventory" -> rewriteSoldier(damaged, 7) { it[25]++ }
                "metadata" -> rewriteSoldier(damaged, 7) { it[1500]++ }
                "other-stat" -> rewriteSoldier(damaged, 42) { it[868]++ }
                "soldier-checksum" -> damaged[soldierStart(7) + 2208]++
            }
            assertIs<MarksmanshipEditResult.Failure>(validatePrivilegedCandidate(editor(), source, damaged,
                request(), before, profiles, rotation), mode)
        }
    }

    private fun soldierStart(id: Int) = PROFILE_END + 1 + (if (id == 7) 0 else 2328 + 4 + 20 + 1 + 128 + 1)

    private fun rewriteSoldier(save: ByteArray, id: Int, change: (ByteArray) -> Unit) {
        val start = soldierStart(id)
        val plain = NormalSaveBlockDecryptor.decryptBlock(save.copyOfRange(start, start + 2328), 2328, rotation)
        change(plain)
        putU32(plain, 2208, independentSoldierChecksum(plain))
        encrypt(plain).copyInto(save, start)
    }

    private fun independentSoldierChecksum(record: ByteArray): Long {
        var value = java.math.BigInteger.ONE
        for ((a, b) in listOf(868 to 917, 880 to 840, 886 to 1377, 1372 to 916, 1378 to 849)) {
            value = (value + (record[a].toLong() + 1).toBigInteger()) * (record[b].toLong() + 1).toBigInteger()
        }
        value += (1 + (record[1825].toInt() and 255)).toBigInteger()
        repeat(19) { slot ->
            val offset = 12 + 36 * slot
            value += ((record[offset].toInt() and 255) + ((record[offset + 1].toInt() and 255) shl 8) +
                (record[offset + 2].toInt() and 255)).toBigInteger()
        }
        return value.mod(java.math.BigInteger.ONE.shiftLeft(32)).toLong()
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
                soldier[1377] = plain[id * 716 + 353]
                soldier[868] = 64; soldier[917] = 90; soldier[840] = 66
                soldier[12] = 17; soldier[14] = 2; soldier[25] = 93 // inventory opaque payload
                soldier[1500] = 87 // unrelated progress metadata
                putU32(soldier, 2208, independentSoldierChecksum(soldier))
                output.write(encrypt(soldier))
                output.write(byteArrayOf(1, 0, 0, 0)) // one path node
                output.write(ByteArray(20) { (it + 3).toByte() })
                output.write(1) // keyring present
                output.write(ByteArray(128) { (it + 9).toByte() })
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
