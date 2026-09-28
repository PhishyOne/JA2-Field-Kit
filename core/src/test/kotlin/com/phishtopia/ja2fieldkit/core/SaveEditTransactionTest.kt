package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.function.Consumer
import kotlin.test.*

/** Only manifested project-authored resources, assembled here with independent test arithmetic. */
class SaveEditTransactionTest {
    private val vector by lazy { resource("synthetic-build-04.12.02-profile-recovery-v1") }
    private val key by lazy { vector.copyOfRange(0, 49) }
    private val rotation by lazy { SaveRotationTable.fromBytes(key) }
    private val oracle by lazy { RotationDigestOracle { if (it.value == 139) RotationTableDigest.from(rotation) else null } }
    private fun inspector() = Ja2SaveInspector.withRotationDigestOracleForTesting(oracle)
    private fun editor(
        inspector: Ja2SaveInspector = inspector(),
        capability: SyntheticMarksmanshipCapability = SyntheticMarksmanshipCapability(),
        observe: (EditWork) -> Unit = {},
    ): Ja2SaveEditor = Ja2SaveEditor::class.java.getDeclaredConstructor(
        Ja2SaveInspector::class.java, SyntheticMarksmanshipCapability::class.java, Consumer::class.java,
    ).apply { isAccessible = true }.newInstance(inspector, capability, Consumer<EditWork> { observe(it) })
    private fun request(source: ByteArray, id: Int = 7, expected: Int = 89, value: Int = 90) = SaveEditRequest(
        SaveEditRequest.SourceIdentity(source.size, hash(source)), SaveEditRequest.SetHiredMarksmanship(id, expected, value),
    )

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
        failure(editor.edit(empty, null), SaveEditResult.Reason.INVALID_REQUEST)
        failure(editor.edit(empty, SaveEditRequest(null, null)), SaveEditResult.Reason.INVALID_REQUEST)
        failure(editor.edit(ByteArray(Ja2SaveEditor.MAX_SAVE_BYTES + 1), null), SaveEditResult.Reason.SOURCE_TOO_LARGE)
        assertEquals(0, work)
    }

    @Test fun exactAndTighterSourceCandidateBounds() {
        val maximum = ByteArray(Ja2SaveEditor.MAX_SAVE_BYTES)
        failure(Ja2SaveEditor().edit(maximum, request(maximum)), SaveEditResult.Reason.CAPABILITY_DISABLED)
        val source = save()
        failure(editor(capability = SyntheticMarksmanshipCapability(source.size - 1, source.size)).edit(source, request(source)),
            SaveEditResult.Reason.CAPABILITY_SIZE_LIMIT)
        val work = mutableListOf<EditWork>()
        failure(editor(capability = SyntheticMarksmanshipCapability(source.size, source.size - 1), observe = work::add).edit(source, request(source)),
            SaveEditResult.Reason.CANDIDATE_SIZE_LIMIT)
        assertFalse(EditWork.CANDIDATE_ALLOCATION in work)
        assertIs<SaveEditResult.VerifiedCandidate>(editor(capability = SyntheticMarksmanshipCapability(source.size, source.size)).edit(source, request(source)))
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

    private fun validate(source: ByteArray, candidate: ByteArray, request: SaveEditRequest): SaveEditResult =
        Ja2SaveEditor::class.java.getDeclaredMethod("validateCandidate", ByteArray::class.java, ByteArray::class.java,
            SaveEditRequest::class.java, SaveInspectionV01Result.Success::class.java, List::class.java,
            LiveMercStateInspectionResult.Success::class.java, SaveRotationTable::class.java,
        ).apply { isAccessible = true }.invoke(editor(), source, candidate, request,
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
            profile[353] = 89
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
