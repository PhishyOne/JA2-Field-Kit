package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.function.Consumer
import kotlin.test.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only manifested project-authored resources, assembled here with independent test arithmetic. */
class SaveEditTransactionTest {
    private val vector by lazy { resource("synthetic-build-04.12.02-profile-recovery-v1") }
    private val key by lazy { vector.copyOfRange(0, 49) }
    private val rotation by lazy { SaveRotationTable.fromBytes(key) }
    private val oracle by lazy { RotationDigestOracle { if (it.value == 139) RotationTableDigest.from(rotation) else null } }
    private fun inspector() = Ja2SaveInspector.withRotationDigestOracleForTesting(oracle)
    private fun editor(
        inspector: Ja2SaveInspector = inspector(),
        capability: SyntheticSaveEditCapability = SyntheticSaveEditCapability(),
        observe: (EditWork) -> Unit = {},
    ): Ja2SaveEditor = Ja2SaveEditor::class.java.getDeclaredConstructor(
        Ja2SaveInspector::class.java, SyntheticSaveEditCapability::class.java, Consumer::class.java,
    ).apply { isAccessible = true }.newInstance(inspector, capability, Consumer<EditWork> { observe(it) })
    // JVM-only integration fixture: uses the same manifested synthetic data and verifier.
    fun androidFixture(item: Int = 201, status: Int = if (item == 0) 0 else 80,
        slot: Int = 7, kind: String = "normal"): Pair<ByteArray, Ja2SaveInspector> =
        inventorySave(item, status, slot, kind) to inspector()
    fun androidEditor(): Ja2SaveEditor = editor()
    fun androidDisagreement(profile: Boolean, field: String): Pair<ByteArray, Ja2SaveInspector> {
        val bytes = inventorySave()
        val offset = if (profile) when (field) { "item" -> 430; "count" -> 384; else -> 365 }
            else when (field) { "item" -> 264; "count" -> 266; else -> 268 }
        rewrite(bytes, if (profile) PROFILE_START + 7 * 716 else PROFILE_END + 1,
            if (profile) 716 else 2328) {
            // Count zero on a nonempty profile is deliberately structurally invalid.
            it[offset] = when (field) { "item" -> 202.toByte(); "count" -> 0; else -> 79 }
        }
        return bytes to inspector()
    }
    fun androidCandidate(): SaveEditResult.VerifiedCandidate {
        val source = inventorySave()
        return assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request(source)))
    }

    private fun request(source: ByteArray, id: Int = 7, expected: Int = 89, value: Int = 90) = SaveEditRequest(
        SaveEditRequest.SourceIdentity(source.size, hash(source)), SaveEditRequest.SetHiredStat(id, HiredMercStat.MARKSMANSHIP, expected, value),
    )

    // Literal serializer facts, independent of the editor registry and read offsets.
    // Pinned upstream audit: tools/check_hired_stat_offsets.py.
    private data class StatCase(val stat: HiredMercStat, val profile: Int, val live: Int,
        val current: Int, val minimum: Int, val maximum: Int, val damage: Int = -1)
    private val cases = listOf(
        StatCase(HiredMercStat.AGILITY, 405, 880, 61, 1, 100, 820),
        StatCase(HiredMercStat.DEXTERITY, 335, 840, 62, 1, 100, 821),
        StatCase(HiredMercStat.STRENGTH, 296, 886, 40, 1, 100, 822),
        StatCase(HiredMercStat.LEADERSHIP, 341, 895, 64, 1, 100),
        StatCase(HiredMercStat.WISDOM, 355, 841, 65, 1, 100, 823),
        StatCase(HiredMercStat.EXPERIENCE_LEVEL, 352, 849, 6, 1, 10),
        StatCase(HiredMercStat.MARKSMANSHIP, 353, 1377, 89, 0, 100),
        StatCase(HiredMercStat.MECHANICAL, 411, 916, 68, 0, 100),
        StatCase(HiredMercStat.EXPLOSIVES, 339, 1378, 69, 0, 100),
        StatCase(HiredMercStat.MEDICAL, 261, 1372, 70, 0, 100),
    )
    private fun statRequest(source: ByteArray, case: StatCase, expected: Int = case.current,
        value: Int = case.current + 1) = SaveEditRequest(
        SaveEditRequest.SourceIdentity(source.size, hash(source)),
        SaveEditRequest.SetHiredStat(7, case.stat, expected, value),
    )

    @TestFactory fun everyStatSynchronizesAndPreservesAllOtherFacts() = cases.mapIndexed { tag, case ->
        dynamicTest(case.stat.name) {
            val source = save()
            val original = source.copyOf()
            val request = statRequest(source, case)
            val result = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request))
            val candidate = result.candidateBytes
            val value = case.current + 1
            assertContentEquals(original, source)
            assertEquals(case.profile, case.stat.profileOffset())
            assertEquals(case.live, case.stat.soldierOffset())
            val oldProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
            val newProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(candidate)
            assertEquals(oldProfiles.map { if (it.profileId == 7) it.withStat(case.stat, value) else it }, newProfiles)
            val old = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
            val new = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(candidate))
            assertEquals(old.format, new.format)
            assertEquals(old.campaign, new.campaign)
            assertEquals(old.roster.map { if (it.profileIndex == 7) it.copy(stats = it.stats.withStat(case.stat, value)) else it }, new.roster)
            val oldLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(source))
            val newLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(candidate))
            oldLive.mercs.zip(newLive.mercs).forEach { (a, b) ->
                assertEquals(a.profileIndex, b.profileIndex)
                assertEquals(a.slots, b.slots)
                assertEquals(if (a.profileIndex == 7) a.stats.withStat(case.stat, value) else a.stats, b.stats)
            }
            val pStart = PROFILE_START + 7 * 716
            val sStart = PROFILE_END + 1
            source.indices.forEach { i ->
                if (i !in pStart + case.profile until pStart + 716 && i !in sStart + case.live until sStart + 2328) {
                    assertEquals(source[i], candidate[i], "untouched ciphertext $i")
                }
            }
            for ((start, size, field, checksum) in listOf(listOf(pStart, 716, case.profile, 696), listOf(sStart, 2328, case.live, 2208))) {
                val a = decrypt(source, start, size)
                val b = decrypt(candidate, start, size)
                assertEquals(case.current, a[field].toInt())
                assertEquals(value, b[field].toInt())
                a.indices.forEach { i -> if (i != field && i !in checksum..checksum + 3) assertEquals(a[i], b[i], "plaintext $i") }
                assertEquals(independentChecksum(b, size == 2328), u32(b, checksum))
                if (case.stat in listOf(HiredMercStat.LEADERSHIP, HiredMercStat.WISDOM)) {
                    assertEquals(u32(a, checksum), u32(b, checksum)) // Neither formula covers these fields.
                } else assertNotEquals(u32(a, checksum), u32(b, checksum))
            }
            val canonical = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
                .put(java.util.HexFormat.of().parseHex(hash(source))).putInt(source.size)
                .putInt(1).putInt(tag + 1).putInt(7).putInt(case.current).putInt(value).array()
            assertEquals(hash(canonical), result.provenance.requestSha256())
            assertEquals("hired-stat-v2", result.provenance.verificationPlan())
            assertEquals(2, result.provenance.modelVersion())
        }
    }

    @TestFactory fun statSpecificRangesAndNoOpIdentity() = cases.map { case -> dynamicTest(case.stat.name) {
        val source = save()
        assertEquals(case.minimum, case.stat.minimum())
        assertEquals(case.maximum, case.stat.maximum())
        for (value in listOf(case.minimum, case.maximum)) {
            val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, statRequest(source, case, value = value))).candidateBytes
            // Boundary also becomes a valid expected-current, through the same complete path.
            assertContentEquals(candidate, assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(candidate,
                statRequest(candidate, case, expected = value, value = value))).candidateBytes)
        }
        assertContentEquals(source, assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source,
            statRequest(source, case, value = case.current))).candidateBytes)
        var work = 0
        val bounded = editor(observe = { work++ })
        for (bad in listOf(case.minimum - 1, case.maximum + 1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            failure(bounded.edit(source, statRequest(source, case, value = bad)), SaveEditResult.Reason.INVALID_REQUEST)
            failure(bounded.edit(source, statRequest(source, case, expected = bad)), SaveEditResult.Reason.INVALID_REQUEST)
        }
        assertEquals(0, work)
    } }

    @TestFactory fun eachStatRejectsStaleValuesDisagreementAndV102() = cases.map { case -> dynamicTest(case.stat.name) {
        val source = save()
        failure(editor().edit(source, statRequest(source, case, expected = case.current - 1)), SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH)
        for ((start, size, field) in listOf(listOf(PROFILE_START + 7 * 716, 716, case.profile), listOf(PROFILE_END + 1, 2328, case.live))) {
            val mismatch = source.copyOf()
            rewrite(mismatch, start, size) { it[field]-- }
            failure(editor().edit(mismatch, statRequest(mismatch, case)), SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH)
        }
        val v102 = source.copyOf().also { it[0] = 102 }
        assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(v102))
        failure(editor().edit(v102, statRequest(v102, case)), SaveEditResult.Reason.UNSUPPORTED_FORMAT)
        failure(Ja2SaveEditor().edit(source, statRequest(source, case)), SaveEditResult.Reason.CAPABILITY_DISABLED)
    } }

    @TestFactory fun statInjuriesAreNotMistakenForProfileLiveAgreement() = cases.filter { it.damage >= 0 }.map { case ->
        dynamicTest(case.stat.name) {
            for (damage in listOf(-128, -1, 1, 127)) {
                val source = save().also { bytes -> rewrite(bytes, PROFILE_END + 1, 2328) { it[case.damage] = damage.toByte() } }
                val original = source.copyOf()
                for (value in listOf(case.current, case.current + 1)) {
                    val rejected = assertIs<SaveEditResult.Failure>(editor().edit(source, statRequest(source, case, value = value)))
                    assertEquals(SaveEditResult.Stage.PRECONDITION, rejected.stage())
                    assertEquals(SaveEditResult.Reason.STAT_INJURY_PRESENT, rejected.reason())
                }
                assertContentEquals(original, source)
                // An unrelated stat edit must preserve this injury byte exactly.
                val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request(source))).candidateBytes
                assertEquals(damage.toByte(), decrypt(candidate, PROFILE_END + 1, 2328)[case.damage])
            }
        }
    }

    @TestFactory fun everyStatRejectsCorruptOrSemanticallyWrongCandidates() = cases.map { case -> dynamicTest(case.stat.name) {
        val source = save()
        val request = statRequest(source, case)
        val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        for ((start, size, field) in listOf(listOf(PROFILE_START + 7 * 716, 716, case.profile), listOf(PROFILE_END + 1, 2328, case.live))) {
            val wrong = candidate.copyOf().also { bytes -> rewrite(bytes, start, size) { it[field]++ } }
            failure(validate(source, wrong, request), SaveEditResult.Reason.VERIFICATION_MISMATCH)
            val corrupt = candidate.copyOf().also { it[start + if (size == 716) 696 else 2208]++ }
            assertEquals(SaveEditResult.Stage.CANDIDATE_PARSE, assertIs<SaveEditResult.Failure>(validate(source, corrupt, request)).stage())
        }
    } }

    @Test fun synchronizedEditPreservesAllOtherFactsAndBytes() {
        for (id in listOf(7, 42)) {
            val source = save()
            val original = source.copyOf()
            val work = mutableListOf<EditWork>()
            val result = assertIs<SaveEditResult.VerifiedCandidate>(editor(observe = work::add).edit(source, request(source, id)))
            val candidate = result.candidateBytes
            assertContentEquals(original, source)
            assertEquals(EditWork.entries.toList(), work)
            val oldProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
            val newProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(candidate)
            assertEquals(oldProfiles.map { if (it.profileId == id) it.copy(marksmanship = 90) else it }, newProfiles)
            val before = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
            val after = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(candidate))
            assertEquals(before.format, after.format)
            assertEquals(before.campaign, after.campaign)
            assertEquals(before.roster.map { if (it.profileIndex == id) it.copy(stats = it.stats.copy(marksmanship = 90)) else it }, after.roster)
            val oldLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(source))
            val newLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(candidate))
            oldLive.mercs.zip(newLive.mercs).forEach { (old, new) ->
                assertEquals(old.profileIndex, new.profileIndex)
                assertEquals(old.slots, new.slots)
                assertEquals(if (old.profileIndex == id) old.stats.copy(marksmanship = 90) else old.stats, new.stats)
            }
            val profileStart = PROFILE_START + id * 716
            val soldierStart = NormalNonLinuxRosterDecoder.validatedRecordLocations(source).single { it.profileIndex == id }.absoluteOffset
            source.indices.forEach { i ->
                if (i !in profileStart + 353 until profileStart + 716 && i !in soldierStart + 1377 until soldierStart + 2328) {
                    assertEquals(source[i], candidate[i], "untouched ciphertext $i")
                }
            }
            for ((start, size, field, checksum) in listOf(listOf(profileStart, 716, 353, 696), listOf(soldierStart, 2328, 1377, 2208))) {
                val old = decrypt(source, start, size)
                val new = decrypt(candidate, start, size)
                old.indices.forEach { i -> if (i != field && i !in checksum..checksum + 3) assertEquals(old[i], new[i]) }
                assertEquals(90, new[field].toInt())
                assertEquals(independentChecksum(new, size == 2328), u32(new, checksum))
                assertNotEquals(u32(old, checksum), u32(new, checksum))
            }
            assertEquals(SaveEditResult.Check.entries, result.verification)
            assertEquals(hash(source), result.provenance.source().sha256())
            assertEquals(hash(candidate), result.provenance.candidate().sha256())
        }
    }

    @Test fun noOpReparsesAndIsByteIdentical() {
        val source = save()
        var detections = 0
        val inspector = Ja2SaveInspector.withDetectorForTesting { detections++; SaveFormatDetector.detect(it, oracle) }
        val result = assertIs<SaveEditResult.VerifiedCandidate>(editor(inspector).edit(source, request(source, value = 89)))
        assertContentEquals(source, result.candidateBytes)
        assertEquals(result.provenance.source(), result.provenance.candidate())
        assertEquals(6, detections) // inspectV01, profiles, live on each side; no no-op shortcut.
    }

    @Test fun immutableSourceCandidateAndDeterministicProvenance() {
        val source = save()
        val original = source.copyOf()
        val request = request(source)
        val first = assertIs<SaveEditResult.VerifiedCandidate>(editor(observe = {
            if (it == EditWork.SOURCE_HASH) source.fill(0) // capture has completed
        }).edit(source, request))
        val second = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(original, request))
        assertEquals(second.provenance, first.provenance)
        first.candidateBytes.fill(0)
        assertContentEquals(second.candidateBytes, first.candidateBytes)
        assertFailsWith<UnsupportedOperationException> { (first.verification as MutableList).clear() }
    }

    @Test fun staleSourceAndExpectedValueFailWithoutBytes() {
        val source = save()
        val originalRequest = request(source)
        source[source.lastIndex]++ // unchanged stat does not excuse a changed source
        failure(editor().edit(source, originalRequest), SaveEditResult.Reason.SOURCE_IDENTITY_MISMATCH)
        failure(editor().edit(source, request(source, expected = 88)), SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH)
        failure(editor().edit(source, SaveEditRequest(SaveEditRequest.SourceIdentity(source.size - 1, hash(source)), originalRequest.operation())),
            SaveEditResult.Reason.SOURCE_IDENTITY_MISMATCH)
    }

    @Test fun exactWriteIdentityOnlyAndV102StillReadable() {
        val v102 = save().also { it[0] = 102 }
        assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(v102))
        assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(v102))
        failure(editor().edit(v102, request(v102)), SaveEditResult.Reason.UNSUPPORTED_FORMAT)
        for (change in listOf<(ByteArray) -> Unit>({ it[0] = 104 }, { it[4] = 'X'.code.toByte() }, { it[303] = 2 })) {
            val source = save().also(change)
            failure(editor().edit(source, request(source)), SaveEditResult.Reason.INVALID_SOURCE)
        }
        val valid = save()
        failure(Ja2SaveEditor().edit(valid, request(valid)), SaveEditResult.Reason.CAPABILITY_DISABLED)
    }

    @Test fun absentVehicleDuplicateAndMismatchedLiveTargetsFailClosed() {
        val source = save()
        failure(editor().edit(source, request(source, id = 0)), SaveEditResult.Reason.TARGET_NOT_UNIQUE_HIRED_MERC)
        for (kind in listOf("vehicle", "duplicate", "vehicleAlias", "nonplayer", "mismatch", "missing")) {
            val bytes = save(kind)
            // Count-one synthetic headers select another table index. Bind their exact
            // project-authored key there too; production detection remains unchanged.
            val selectedIndex = NormalSaveEncryptionSelector.select(
                NormalEncryptionHeaderInputs.fromBuild041202(SaveHeaderParser.parseBuild041202(bytes)),
            )
            val variantInspector = Ja2SaveInspector.withRotationDigestOracleForTesting(
                RotationDigestOracle { if (it == selectedIndex) RotationTableDigest.from(rotation) else null },
            )
            if (kind in listOf("vehicle", "vehicleAlias", "missing")) {
                assertIs<SaveInspectionV01Result.Success>(variantInspector.inspectV01(bytes), kind)
            }
            val result = editor(variantInspector).edit(bytes, request(bytes))
            failure(result, when (kind) {
                "vehicle", "vehicleAlias", "missing" -> SaveEditResult.Reason.TARGET_NOT_UNIQUE_HIRED_MERC
                "mismatch" -> SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH
                else -> SaveEditResult.Reason.INVALID_SOURCE
            })
        }
    }

    @Test fun valueAndStructuralBoundsPrecedeProportionalWork() {
        var work = 0
        val editor = editor(observe = { work++ })
        val empty = byteArrayOf()
        for (value in listOf(-1, 101, Int.MIN_VALUE, Int.MAX_VALUE)) {
            failure(editor.edit(empty, request(empty, value = value)), SaveEditResult.Reason.INVALID_REQUEST)
            failure(editor.edit(empty, request(empty, expected = value)), SaveEditResult.Reason.INVALID_REQUEST)
        }
        for (id in listOf(-1, 170, Int.MAX_VALUE)) failure(editor.edit(empty, request(empty, id = id)), SaveEditResult.Reason.INVALID_REQUEST)
        for (digest in listOf("", "a".repeat(65), "G".repeat(64))) {
            failure(editor.edit(empty, SaveEditRequest(SaveEditRequest.SourceIdentity(0, digest), request(empty).operation())), SaveEditResult.Reason.INVALID_REQUEST)
        }
        failure(editor.edit(empty, SaveEditRequest(SaveEditRequest.SourceIdentity(0, hash(empty)),
            SaveEditRequest.SetHiredStat(7, null, 0, 0))), SaveEditResult.Reason.INVALID_REQUEST)
        failure(editor.edit(empty, null), SaveEditResult.Reason.INVALID_REQUEST)
        failure(editor.edit(empty, SaveEditRequest(null, null)), SaveEditResult.Reason.INVALID_REQUEST)
        failure(editor.edit(ByteArray(Ja2SaveEditor.MAX_SAVE_BYTES + 1), null), SaveEditResult.Reason.SOURCE_TOO_LARGE)
        assertEquals(0, work)
    }

    @Test fun exactAndTighterSourceCandidateBounds() {
        val maximum = ByteArray(Ja2SaveEditor.MAX_SAVE_BYTES)
        failure(Ja2SaveEditor().edit(maximum, request(maximum)), SaveEditResult.Reason.CAPABILITY_DISABLED)
        val source = save()
        val exact = source.copyOf(Ja2SaveEditor.MAX_SAVE_BYTES)
        assertEquals(exact.size, assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(exact, request(exact))).candidateBytes.size)
        failure(editor(capability = SyntheticSaveEditCapability(source.size - 1, source.size)).edit(source, request(source)),
            SaveEditResult.Reason.CAPABILITY_SIZE_LIMIT)
        val work = mutableListOf<EditWork>()
        failure(editor(capability = SyntheticSaveEditCapability(source.size, source.size - 1), observe = work::add).edit(source, request(source)),
            SaveEditResult.Reason.CANDIDATE_SIZE_LIMIT)
        assertFalse(EditWork.CANDIDATE_ALLOCATION in work)
        assertIs<SaveEditResult.VerifiedCandidate>(editor(capability = SyntheticSaveEditCapability(source.size, source.size)).edit(source, request(source)))
    }

    @Test fun endpointsAccepted() {
        val source = save()
        for (value in listOf(0, 100)) assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request(source, value = value)))
    }

    @Test fun actualCandidateCorruptionCannotEscapeThroughSuccess() {
        val source = save()
        val request = request(source)
        val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        for (kind in listOf("checksum", "soldierChecksum", "campaign", "opaque", "profileStat", "soldierStat", "inventory", "requested", "size", "format")) {
            val damaged = candidate.copyOf(if (kind == "size") candidate.size + 1 else candidate.size)
            when (kind) {
                "checksum" -> damaged[PROFILE_START + 7 * 716 + 696]++
                "soldierChecksum" -> damaged[PROFILE_END + 1 + 2208]++
                "campaign" -> damaged[284]++
                "opaque" -> damaged[damaged.lastIndex]++
                "format" -> damaged[0] = 102
                "profileStat" -> rewrite(damaged, PROFILE_START + 7 * 716, 716) { it[297]++ }
                "soldierStat" -> rewrite(damaged, PROFILE_END + 1, 2328) { it[868]++ }
                "inventory" -> rewrite(damaged, PROFILE_END + 1, 2328) { it[20]++ }
                "requested" -> rewrite(damaged, PROFILE_END + 1, 2328) { it[1377] = 91 }
            }
            val failure = assertIs<SaveEditResult.Failure>(validate(source, damaged, request), kind)
            assertTrue(failure.stage() in listOf(SaveEditResult.Stage.CANDIDATE_PARSE, SaveEditResult.Stage.VERIFICATION), kind)
        }
        // Provenance must also reject a privately injected, wrong source binding.
        failure(validate(source, candidate, SaveEditRequest(SaveEditRequest.SourceIdentity(source.size, "0".repeat(64)), request.operation())),
            SaveEditResult.Reason.VERIFICATION_MISMATCH)
    }

    @Test fun sourceIntegrityAndEveryPublicReparseAreMandatory() {
        val source = save()
        for (offset in listOf(PROFILE_START + 7 * 716 + 696, PROFILE_END + 1 + 2208)) {
            val bad = source.copyOf().also { it[offset]++ }
            failure(editor().edit(bad, request(bad)), SaveEditResult.Reason.INVALID_SOURCE)
        }
        for (at in 1..6) {
            var calls = 0
            val detector = Ja2SaveInspector.withDetectorForTesting {
                val result = SaveFormatDetector.detect(it, oracle)
                if (++calls == at) it[284]++
                result
            }
            failure(editor(detector).edit(source, request(source)),
                if (at <= 3) SaveEditResult.Reason.INVALID_SOURCE else SaveEditResult.Reason.INVALID_CANDIDATE)
            assertEquals(at, calls)
        }
    }

    @Test fun liveLeadershipAndWisdomRemainSignedReadFactsForBothVersions() {
        for (version in listOf(102, 103)) {
            val source = save().also { it[0] = version.toByte() }
            rewrite(source, PROFILE_END + 1, 2328) { it[895] = -128; it[841] = 127 }
            val live = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(source))
            assertEquals(-128, live.mercs.first().stats.leadership)
            assertEquals(127, live.mercs.first().stats.wisdom)
        }
    }

    @Test fun capabilityCannotRaiseCoreCeiling() {
        for (limit in listOf(-1, Ja2SaveEditor.MAX_SAVE_BYTES + 1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SyntheticSaveEditCapability(maxSourceBytes = limit) }
            assertFailsWith<IllegalArgumentException> { SyntheticSaveEditCapability(maxCandidateBytes = limit) }
        }
        assertEquals(cases.map { it.stat }, HiredMercStat.entries)
    }

    @Test fun detectorCannotHideMalformedNamesByRepairingItsOwnCopy() {
        val source = save()
        val request = request(source)
        val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        val repairingInspector = Ja2SaveInspector.withDetectorForTesting { bytes ->
            rewrite(bytes, PROFILE_START, 716) { it[0] = 'A'.code.toByte(); it[1] = 0 }
            SaveFormatDetector.detect(bytes, oracle).also { assertEquals(SaveCompatibility.SUPPORTED, it.compatibility) }
        }
        fun corrupt(bytes: ByteArray) = bytes.copyOf().also { bad ->
            rewrite(bad, PROFILE_START, 716) { it[0] = 0; it[1] = 0xd8.toByte() }
        }
        val badSource = corrupt(source)
        val badCandidate = corrupt(candidate)
        val originalSource = badSource.copyOf()
        val originalCandidate = badCandidate.copyOf()
        failure(editor(repairingInspector).edit(badSource, request(badSource)), SaveEditResult.Reason.INVALID_SOURCE)
        failure(validate(source, badCandidate, request, editor(repairingInspector)), SaveEditResult.Reason.INVALID_CANDIDATE)
        assertContentEquals(originalSource, badSource)
        assertContentEquals(originalCandidate, badCandidate)
        assertEquals(SaveDetectionReason.DETECTOR_MUTATED_INPUT, repairingInspector.detect(badCandidate).reason)
    }

    @Test fun verifierRejectsEveryProfileAndAllExposedStatDrift() {
        val source = save()
        val request = request(source)
        val operation = request.operation() as SaveEditRequest.SetHiredStat
        val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
        val before = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
        val after = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(candidate))
        val profiles = inspector().parseBuild041202NormalNonLinuxProfiles(source)
        val newProfiles = inspector().parseBuild041202NormalNonLinuxProfiles(candidate)
        val live = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(source))
        val newLive = assertIs<LiveMercStateInspectionResult.Success>(inspector().inspectLiveMercState(candidate))
        fun verify(changedProfiles: List<MercProfile> = newProfiles,
            changedRoster: SaveInspectionV01Result.Success = after,
            changedLive: LiveMercStateInspectionResult.Success = newLive) =
            verifySaveEdit(source, candidate, operation, before, changedRoster, profiles, changedProfiles, live, changedLive, rotation)
        assertTrue(verify())
        for (id in 0..169) {
            assertFalse(verify(changedProfiles = newProfiles.toMutableList().also { it[id] = it[id].copy(life = it[id].life + 1) }))
        }
        val target = newProfiles[7]
        val variants = listOf(target.copy(profileId = 8), target.copy(name = "changed"),
            target.copy(nickname = "changed"), target.copy(lifeMax = 99)) +
            cases.map { target.withStat(it.stat, -99) }
        variants.forEach { changed -> assertFalse(verify(changedProfiles = newProfiles.toMutableList().also { it[7] = changed })) }
        for (case in cases) {
            assertFalse(verify(changedRoster = SaveInspectionV01Result.Success.create(after.format, after.campaign,
                after.roster.map { it.copy(stats = it.stats.withStat(case.stat, -99)) })))
            assertFalse(verify(changedLive = LiveMercStateInspectionResult.Success(newLive.format,
                newLive.mercs.map { LiveMercState(it.profileIndex, it.stats.withStat(case.stat, -99), it.slots, it.location) })))
        }
        assertFalse(verify(changedRoster = SaveInspectionV01Result.Success.create(after.format, after.campaign, after.roster.reversed())))
        assertFalse(verify(changedLive = LiveMercStateInspectionResult.Success(newLive.format, newLive.mercs.reversed())))
        assertFalse(verify(changedRoster = SaveInspectionV01Result.Success.create(after.format, after.campaign.copy(balance = -1), after.roster)))
    }

    private fun inventoryRequest(source: ByteArray, slot: Int = 7, item: Int = 201, status: Int = 80,
        newItem: Int? = null, newStatus: Int = 90, id: Int = 7, count: Int = if (item == 0) 0 else 1,
        newCount: Int = 1) = SaveEditRequest(SaveEditRequest.SourceIdentity(source.size, hash(source)),
        SaveEditRequest.ExpectedSlot(item, count, status).let { expected ->
            if (newItem == null) SaveEditRequest.ClearSlot(id, slot, expected)
            else SaveEditRequest.SetSimpleItem(id, slot, expected, newItem, newCount, newStatus)
        })

    private fun inventorySave(item: Int = 201, status: Int = if (item == 0) 0 else 80, slot: Int = 7,
        kind: String = "normal", emptyWeight: Int = 1): ByteArray = save(kind).also { bytes ->
        rewrite(bytes, PROFILE_START + 7 * 716, 716) {
            it[416 + 2 * slot] = item.toByte(); it[417 + 2 * slot] = (item ushr 8).toByte()
            it[358 + slot] = status.toByte(); it[377 + slot] = if (item == 0) 0 else 1
            it[412] = 0
        }
        rewrite(bytes, PROFILE_END + 1, 2328) {
            val start = 12 + slot * 36
            it.fill(0, start, start + 36)
            it[start] = item.toByte(); it[start + 1] = (item ushr 8).toByte()
            it[start + 2] = if (item == 0) 0 else 1; it[start + 4] = status.toByte()
            it[start + 32] = (if (item == 0) emptyWeight else 255).toByte()
        }
    }

    @TestFactory fun inventoryAddReplaceAndClearEachAdmittedItem() = listOf(201, 202, 203).flatMap { item ->
        listOf("add", "replace", "clear").map { operation -> dynamicTest("$operation $item") {
            for (slot in 7..10) {
                val oldItem = if (operation == "add") 0 else item
                val oldStatus = if (oldItem == 0) 0 else 80
                val newItem = if (operation == "clear") 0 else if (operation == "replace") 201 + (item - 200) % 3 else item
                val source = inventorySave(oldItem, oldStatus, slot)
                val original = source.copyOf()
                val request = inventoryRequest(source, slot, oldItem, oldStatus, newItem.takeIf { it != 0 })
                val result = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request))
                val candidate = result.candidateBytes
                assertContentEquals(original, source)
                assertEquals("inventory-v1", result.provenance.verificationPlan())
                assertEquals(3, result.provenance.modelVersion())
                val before = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(source))
                val after = assertIs<SaveInspectionV01Result.Success>(inspector().inspectV01(candidate))
                assertEquals(before.format, after.format); assertEquals(before.roster, after.roster)
                assertEquals(before.campaign, after.campaign)
                val a = inspector().parseBuild041202NormalNonLinuxProfiles(source)
                val b = inspector().parseBuild041202NormalNonLinuxProfiles(candidate)
                assertEquals(a.map { if (it.profileId == 7) it.copy(inventory = it.inventory.mapIndexed { i, old ->
                    if (i == slot) ProfileInventorySlot(newItem, if (newItem == 0) 0 else 1, if (newItem == 0) 0 else 90) else old
                }) else it }, b)
                val oldLive = assertIs<LiveInventoryInspectionResult.Success>(inspector().inspectLiveInventory(source))
                val newLive = assertIs<LiveInventoryInspectionResult.Success>(inspector().inspectLiveInventory(candidate))
                oldLive.inventories.zip(newLive.inventories).forEach { (old, new) ->
                    assertEquals(old.profileIndex, new.profileIndex)
                    old.slots.zip(new.slots).forEachIndexed { i, (x, y) ->
                        if (old.profileIndex != 7 || i != slot) assertContentEquals(x.objectRecord.rawRecord, y.objectRecord.rawRecord)
                        else {
                            val expected = ByteArray(36)
                            expected[0] = newItem.toByte(); expected[1] = (newItem ushr 8).toByte()
                            expected[2] = if (newItem == 0) 0 else 1; expected[4] = if (newItem == 0) 0 else 90
                            expected[32] = (if (newItem == 0) 1 else 255).toByte()
                            assertContentEquals(expected, y.objectRecord.rawRecord)
                        }
                    }
                }
                val ps = PROFILE_START + 7 * 716; val ss = PROFILE_END + 1
                val fields = setOf(358 + slot, 377 + slot, 416 + slot * 2, 417 + slot * 2)
                for ((start, size, checksum) in listOf(listOf(ps, 716, 696), listOf(ss, 2328, 2208))) {
                    val old = decrypt(source, start, size); val new = decrypt(candidate, start, size)
                    old.indices.forEach { i ->
                        val admitted = if (size == 716) i in fields else i in 12 + slot * 36 until 48 + slot * 36
                        if (!admitted && i !in checksum..checksum + 3) assertEquals(old[i], new[i], "unrelated byte $i")
                    }
                    assertEquals(independentChecksum(new, size == 2328), u32(new, checksum))
                }
                source.indices.forEach { i ->
                    if (i !in ps + fields.min() until ps + 716 && i !in ss + 12 + slot * 36 until ss + 2328)
                        assertEquals(source[i], candidate[i], "unrelated ciphertext $i")
                }
                val canonical = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN).putInt(3)
                    .put(java.util.HexFormat.of().parseHex(hash(source))).putInt(source.size)
                    .putInt(if (newItem == 0) 2 else 3).putInt(7).putInt(slot)
                    .putInt(oldItem).putInt(if (oldItem == 0) 0 else 1).putInt(oldStatus)
                    .putInt(newItem).putInt(if (newItem == 0) 0 else 1).putInt(if (newItem == 0) 0 else 90).array()
                assertEquals(hash(canonical), result.provenance.requestSha256())
            }
        } }
    }

    @Test fun inventoryNoOpsAndStatusEndpoints() {
        for (weight in listOf(0, 1)) {
            val source = inventorySave(0, emptyWeight = weight)
            val request = inventoryRequest(source, item = 0, status = 0)
            assertContentEquals(source, assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes)
        }
        for (item in listOf(201, 202, 203)) for (status in listOf(1, 100)) {
            val source = inventorySave(item)
            val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source,
                inventoryRequest(source, item = item, newItem = item, newStatus = status))).candidateBytes
            assertContentEquals(candidate, assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(candidate,
                inventoryRequest(candidate, item = item, status = status, newItem = item, newStatus = status))).candidateBytes)
        }
    }

    @Test fun inventoryStaleAssertionsAndProfileLiveDisagreementFailWithoutBytes() {
        val source = inventorySave()
        for (request in listOf(inventoryRequest(source, item = 202), inventoryRequest(source, status = 79),
            inventoryRequest(source, item = 0, status = 0))) {
            failure(editor().edit(source, request), SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH)
        }
        for ((start, size, fields) in listOf(Triple(PROFILE_START + 7 * 716, 716, listOf(430, 384, 365)),
            Triple(PROFILE_END + 1, 2328, listOf(264, 266, 268)))) for (field in fields) {
            val bad = source.copyOf().also { bytes -> rewrite(bytes, start, size) { it[field]++ } }
            failure(editor().edit(bad, inventoryRequest(bad)), SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH)
        }
        val request = inventoryRequest(source)
        source[source.lastIndex]++
        failure(editor().edit(source, request), SaveEditResult.Reason.SOURCE_IDENTITY_MISMATCH)
    }

    @Test fun inventoryInvalidStructurePrecedesBulkWork() {
        var work = 0
        val editor = editor(observe = { work++ })
        val source = byteArrayOf()
        val invalid = listOf(-1, 19, Int.MIN_VALUE, Int.MAX_VALUE).map { inventoryRequest(source, slot = it) } +
            listOf(-1, 0, 2, 8, 255, Int.MAX_VALUE).flatMap { listOf(inventoryRequest(source, count = it),
                inventoryRequest(source, newItem = 201, newCount = it)) } +
            listOf(-1, 0, 101, 255, Int.MAX_VALUE).flatMap { listOf(inventoryRequest(source, status = it),
                inventoryRequest(source, newItem = 201, newStatus = it)) } +
            listOf(-1, 0, 65536, Int.MAX_VALUE).map { inventoryRequest(source, newItem = it) } +
            listOf(SaveEditRequest(SaveEditRequest.SourceIdentity(0, hash(source)), SaveEditRequest.ClearSlot(7, 7, null)))
        invalid.forEach { failure(editor.edit(source, it), SaveEditResult.Reason.INVALID_REQUEST) }
        assertEquals(0, work)
    }

    @Test fun inventoryRejectsSpecialClassesAndNonCanonicalPayloads() {
        // Guns, launchers, ammo, bombs, armour, utility, attachments, money, keys and unknown IDs.
        for (item in listOf(1, 40, 71, 137, 164, 204, 207, 219, 233, 234, 240, 271, 350, 65535)) {
            val plain = inventorySave()
            failure(editor().edit(plain, inventoryRequest(plain, newItem = item)), SaveEditResult.Reason.UNSUPPORTED_INVENTORY_OBJECT)
            val special = inventorySave(item)
            failure(editor().edit(special, inventoryRequest(special, item = item)), SaveEditResult.Reason.UNSUPPORTED_INVENTORY_OBJECT)
        }
        for (offset in (3..35).filter { it != 4 }) {
            val source = inventorySave().also { bytes -> rewrite(bytes, PROFILE_END + 1, 2328) { it[264 + offset]++ } }
            failure(editor().edit(source, inventoryRequest(source)),
                if (offset == 28) SaveEditResult.Reason.INVENTORY_UNDROPPABLE else SaveEditResult.Reason.UNSUPPORTED_INVENTORY_OBJECT)
        }
    }

    @Test fun inventoryRejectsUndroppableSlotsAndUnqualifiedSlotRoles() {
        for (slot in 7..10) {
            val source = inventorySave(slot = slot).also { bytes -> rewrite(bytes, PROFILE_START + 7 * 716, 716) {
                it[412] = (1 shl (slot - 3)).toByte()
            } }
            failure(editor().edit(source, inventoryRequest(source, slot = slot)), SaveEditResult.Reason.INVENTORY_UNDROPPABLE)
        }
        for (slot in (0..18).filter { it !in 7..10 }) {
            val source = inventorySave(slot = slot)
            failure(editor().edit(source, inventoryRequest(source, slot = slot)), SaveEditResult.Reason.UNSUPPORTED_INVENTORY_SLOT)
        }
        // A different pocket's profile flag is preserved, not reset globally.
        val source = inventorySave().also { bytes -> rewrite(bytes, PROFILE_START + 7 * 716, 716) { it[412] = 32 } }
        val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, inventoryRequest(source))).candidateBytes
        assertEquals(32, decrypt(candidate, PROFILE_START + 7 * 716, 716)[412].toInt())
    }

    @Test fun inventoryTargetFormatAndCapabilityGatesRemainClosed() {
        val source = inventorySave()
        failure(editor().edit(source, inventoryRequest(source, id = 0)), SaveEditResult.Reason.TARGET_NOT_UNIQUE_HIRED_MERC)
        val duplicate = inventorySave(kind = "duplicate")
        failure(editor().edit(duplicate, inventoryRequest(duplicate)), SaveEditResult.Reason.INVALID_SOURCE)
        val v102 = source.copyOf().also { it[0] = 102 }
        assertIs<LiveInventoryInspectionResult.Success>(inspector().inspectLiveInventory(v102))
        failure(editor().edit(v102, inventoryRequest(v102)), SaveEditResult.Reason.UNSUPPORTED_FORMAT)
        failure(Ja2SaveEditor().edit(source, inventoryRequest(source)), SaveEditResult.Reason.CAPABILITY_DISABLED)
    }

    @Test fun inventoryVerifierRejectsRechecksummedCorruptionAndUntouchedStateDrift() {
        for (newItem in listOf(null, 202)) {
            val source = inventorySave()
            val request = inventoryRequest(source, newItem = newItem)
            val candidate = assertIs<SaveEditResult.VerifiedCandidate>(editor().edit(source, request)).candidateBytes
            for ((start, size, offsets) in listOf(Triple(PROFILE_START + 7 * 716, 716,
                listOf(261, 358, 365, 377, 384, 412, 416, 430, 431)),
                Triple(PROFILE_END + 1, 2328, listOf(12, 264, 266, 268, 280, 292, 296, 868, 1377)))) {
                for (offset in offsets) {
                    val wrong = candidate.copyOf().also { bytes -> rewrite(bytes, start, size) { it[offset]++ } }
                    failure(validate(source, wrong, request), SaveEditResult.Reason.VERIFICATION_MISMATCH)
                }
                val badChecksum = candidate.copyOf().also { it[start + if (size == 716) 696 else 2208]++ }
                failure(validate(source, badChecksum, request), SaveEditResult.Reason.INVALID_CANDIDATE)
            }
            for (offset in listOf(284, candidate.lastIndex)) {
                val wrong = candidate.copyOf().also { it[offset]++ }
                failure(validate(source, wrong, request), SaveEditResult.Reason.VERIFICATION_MISMATCH)
            }
            // Even a plausible cleared object must contain the upstream serialized empty weight.
            if (newItem == null) {
                val wrong = candidate.copyOf().also { bytes -> rewrite(bytes, PROFILE_END + 1, 2328) { it[296] = 0 } }
                failure(validate(source, wrong, request), SaveEditResult.Reason.VERIFICATION_MISMATCH)
            }
        }
    }

    private fun validate(source: ByteArray, candidate: ByteArray, request: SaveEditRequest, editor: Ja2SaveEditor = editor()): SaveEditResult =
        Ja2SaveEditor::class.java.getDeclaredMethod("validateCandidate", ByteArray::class.java, ByteArray::class.java,
            SaveEditRequest::class.java, SaveInspectionV01Result.Success::class.java, List::class.java,
            LiveMercStateInspectionResult.Success::class.java, SaveRotationTable::class.java,
        ).apply { isAccessible = true }.invoke(editor, source, candidate, request,
            inspector().inspectV01(source), inspector().parseBuild041202NormalNonLinuxProfiles(source),
            inspector().inspectLiveMercState(source), rotation) as SaveEditResult

    private fun save(kind: String = "normal"): ByteArray {
        val prefix = ByteArray(PROFILE_END)
        resource("synthetic-build-04.12.02-header-v1").copyInto(prefix)
        prefix[291] = if (kind in listOf("vehicle", "vehicleAlias", "missing")) 1 else 2
        prefix[436] = 0; prefix[437] = 19
        repeat(170) { id ->
            val profile = vector.copyOfRange(49 + id * 716, 49 + (id + 1) * 716)
            profile.fill(0, 0, 80)
            profile[0] = ('A'.code + id % 26).toByte(); profile[60] = ('a'.code + id % 26).toByte()
            cases.forEach { profile[it.profile] = it.current.toByte() }
            putU32(profile, 696, independentChecksum(profile, false))
            encrypt(profile).copyInto(prefix, PROFILE_START + id * 716)
        }
        val output = ByteArrayOutputStream()
        output.write(prefix)
        repeat(20) { slot ->
            val active = slot < 2 && !(kind == "missing" && slot == 0)
            output.write(if (active) 1 else 0)
            if (active) {
                val soldier = ByteArray(2328)
                soldier[0] = slot.toByte(); soldier[8] = 8; soldier[751] = 1
                soldier[1825] = if (slot == 0 || kind in listOf("duplicate", "vehicleAlias")) 7 else 42
                cases.forEach { soldier[it.live] = it.current.toByte() }
                soldier[1377] = if (kind == "mismatch" && slot == 0) 88 else 89
                soldier[868] = 55; soldier[917] = 65; soldier[886] = 40
                // Opaque inventory bytes and unsigned checksum contributions, preserved exactly.
                repeat(19 * 36) { soldier[12 + it] = (it * 17).toByte() }
                if ((kind == "vehicle" && slot == 0) || (kind == "vehicleAlias" && slot == 1)) soldier[9] = 0x80.toByte()
                if (kind == "nonplayer" && slot == 0) soldier[8] = 0
                putU32(soldier, 2208, independentChecksum(soldier, true))
                output.write(encrypt(soldier))
                output.write(byteArrayOf(2, 0, 0, 0))
                output.write(ByteArray(40) { (it + 1).toByte() }) // Path nodes.
                output.write(1)
                output.write(ByteArray(128) { (it * 3).toByte() }) // Keyring.
            }
        }
        output.write(byteArrayOf(11, 22, 33))
        return output.toByteArray()
    }
    private fun rewrite(bytes: ByteArray, start: Int, size: Int, change: (ByteArray) -> Unit) {
        val plain = decrypt(bytes, start, size).also(change)
        putU32(plain, if (size == 716) 696 else 2208, independentChecksum(plain, size == 2328))
        encrypt(plain).copyInto(bytes, start)
    }
    private fun decrypt(bytes: ByteArray, start: Int, size: Int) =
        NormalSaveBlockDecryptor.decryptBlock(bytes.copyOfRange(start, start + size), size, rotation)
    private fun encrypt(bytes: ByteArray): ByteArray {
        var cumulative = 0
        return ByteArray(bytes.size) { i ->
            cumulative = (cumulative + (bytes[i].toInt() and 255) + (key[i % 49].toInt() and 255)) % 256
            cumulative.toByte()
        }
    }
    private fun independentChecksum(bytes: ByteArray, soldier: Boolean): Long {
        val pairs = if (soldier) listOf(868 to 917, 880 to 840, 886 to 1377, 1372 to 916, 1378 to 849)
            else listOf(334 to 297, 405 to 335, 296 to 353, 261 to 411, 339 to 352)
        var value = BigInteger.ONE
        pairs.forEach { (a, b) -> value = (value + (bytes[a].toLong() + 1).toBigInteger()) * (bytes[b].toLong() + 1).toBigInteger() }
        if (soldier) value += (1 + (bytes[1825].toInt() and 255)).toBigInteger()
        repeat(19) { i ->
            val offset = if (soldier) 12 + i * 36 else 416 + i * 2
            val count = if (soldier) 14 + i * 36 else 377 + i
            value += ((bytes[offset].toInt() and 255) + ((bytes[offset + 1].toInt() and 255) shl 8) + (bytes[count].toInt() and 255)).toBigInteger()
        }
        return value.mod(BigInteger.ONE.shiftLeft(32)).toLong()
    }
    private fun resource(id: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/$id.bin")).use { it.readBytes() }
    private fun putU32(bytes: ByteArray, offset: Int, value: Long) { repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() } }
    private fun u32(bytes: ByteArray, offset: Int) = (0..3).sumOf { (bytes[offset + it].toLong() and 255) shl (8 * it) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun failure(result: SaveEditResult, reason: SaveEditResult.Reason) {
        assertEquals(reason, assertIs<SaveEditResult.Failure>(result).reason())
        assertFalse(result.javaClass.methods.any { it.returnType == ByteArray::class.java })
    }
    companion object {
        private const val PROFILE_START = 819 + 7440
        private const val PROFILE_END = PROFILE_START + 170 * 716
    }
}
