package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileFramer
import com.phishtopia.ja2fieldkit.core.format.NormalProfileChecksum
import com.phishtopia.ja2fieldkit.core.format.NormalSaveBlockDecryptor
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.format.SaveRotationTable
import com.phishtopia.ja2fieldkit.core.model.MercProfile
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result

/** One profile/base-field rewrite, never a SOLDIERTYPE current/tactical-stat operation. */
data class MarksmanshipEditRequest(
    val profileId: Int,
    val expectedCurrentMarksmanship: Int,
    val newMarksmanship: Int,
)

enum class MarksmanshipEditStage {
    STRUCTURE, SOURCE_IDENTITY, CAPABILITY, SOURCE_PARSE, PRECONDITION,
    SERIALIZATION, CANDIDATE_PARSE, VERIFICATION, PROVENANCE,
}

enum class MarksmanshipEditReason {
    SOURCE_TOO_LARGE, INVALID_PROFILE_ID, INVALID_MARKSMANSHIP,
    CAPABILITY_DISABLED, CAPABILITY_SIZE_LIMIT, UNSUPPORTED_FORMAT, INVALID_SOURCE,
    EXPECTED_CURRENT_MISMATCH, CANDIDATE_SIZE_LIMIT, INVALID_CANDIDATE,
    VERIFICATION_MISMATCH, INTERNAL_FAILURE,
}

/** Fixed, privacy-safe relations. Each relation is evaluated over its entire stated domain. */
enum class MarksmanshipVerificationCheck {
    EXACT_FORMAT_IDENTITY, EXACT_SIZE_AND_LAYOUT, REQUESTED_PROFILE_VALUE,
    ALL_170_PROFILE_FACTS, ALL_CAMPAIGN_FACTS, ALL_ROSTER_IDENTITIES_AND_STATS,
    TARGET_CHECKSUM, TARGET_PLAINTEXT_ENVELOPE, CIPHERTEXT_PREFIX,
    ALL_OUTSIDE_CIPHERTEXT, NO_OP_BYTE_IDENTITY, HASH_BINDINGS,
}

enum class EditVerificationOutcome { PASSED }

data class MarksmanshipCheckResult(
    val relation: MarksmanshipVerificationCheck,
    val outcome: EditVerificationOutcome,
)

/** Identity denotes synthetic evidence only; there is deliberately no production capability. */
enum class MarksmanshipCapabilityIdentity { PROJECT_AUTHORED_SYNTHETIC_V103 }

data class MarksmanshipEditProvenance(
    val sourceSha256: String,
    val sourceSize: Int,
    val candidateSha256: String,
    val candidateSize: Int,
    val sourceFormat: SaveInspectionFormat,
    val request: MarksmanshipEditRequest,
    /** Three signed 32-bit little-endian integers in request declaration order, lowercase hex. */
    val canonicalRequest: String,
    val capabilityIdentity: MarksmanshipCapabilityIdentity,
    val capabilityRevision: Int,
    val verificationIdentity: String,
    val verificationOutcome: EditVerificationOutcome,
    val transactionModelVersion: Int,
)

/** Immutable bounds descriptor. Possession grants no authority; no public consumer accepts it. */
internal class SyntheticMarksmanshipCapability(
    val maxSourceBytes: Int = Ja2MarksmanshipEditor.MAX_SAVE_BYTES,
    val maxCandidateBytes: Int = Ja2MarksmanshipEditor.MAX_SAVE_BYTES,
) {
    init {
        require(maxSourceBytes in 0..Ja2MarksmanshipEditor.MAX_SAVE_BYTES)
        require(maxCandidateBytes in 0..Ja2MarksmanshipEditor.MAX_SAVE_BYTES)
    }
}

/** Test instrumentation exposes work identities only, never source or candidate arrays. */
internal enum class EditWork {
    SOURCE_SNAPSHOT, SOURCE_HASH, SOURCE_INSPECTION, CANDIDATE_ALLOCATION, CANDIDATE_INSPECTION,
}

internal class CandidateBoundException : IllegalArgumentException("Candidate size/layout limit")

/** Pure mandatory verifier, also exercised directly with corrupted synthetic candidates. */
internal fun verifyMarksmanshipCandidate(
    source: ByteArray,
    candidate: ByteArray,
    request: MarksmanshipEditRequest,
    before: SaveInspectionV01Result.Success,
    after: SaveInspectionV01Result.Success,
    sourceProfiles: List<MercProfile>,
    candidateProfiles: List<MercProfile>,
    rotation: SaveRotationTable,
): Boolean {
    if (!exactFormat(after.format) || before.format != after.format ||
        source.size != candidate.size || candidate.size > Ja2MarksmanshipEditor.MAX_SAVE_BYTES ||
        sourceProfiles.size != 170 || candidateProfiles.size != 170 ||
        before.campaign != after.campaign || before.roster.size != after.roster.size ||
        candidateProfiles[request.profileId].marksmanship != request.newMarksmanship
    ) return false
    for (id in 0..169) {
        val expected = sourceProfiles[id].let {
            if (id == request.profileId) it.copy(marksmanship = request.newMarksmanship) else it
        }
        if (candidateProfiles[id] != expected) return false
    }
    for (index in before.roster.indices) {
        val expected = before.roster[index].let {
            if (it.profileIndex == request.profileId) {
                it.copy(stats = it.stats.copy(marksmanship = request.newMarksmanship))
            } else it
        }
        if (after.roster[index] != expected) return false
    }
    val sourceFrame = NormalNonLinuxProfileFramer.frameBuild041202(source)
    val candidateFrame = NormalNonLinuxProfileFramer.frameBuild041202(candidate)
    if (sourceFrame.profileStartOffset != candidateFrame.profileStartOffset ||
        sourceFrame.profileEndExclusive != candidateFrame.profileEndExclusive ||
        sourceFrame.eventCount != candidateFrame.eventCount ||
        sourceFrame.bobbyRayOrderArraySize != candidateFrame.bobbyRayOrderArraySize ||
        sourceFrame.bobbyRayOrderUsedCount != candidateFrame.bobbyRayOrderUsedCount ||
        sourceFrame.insurancePayoutArraySize != candidateFrame.insurancePayoutArraySize ||
        sourceFrame.insurancePayoutUsedCount != candidateFrame.insurancePayoutUsedCount
    ) return false
    val start = sourceFrame.profileStartOffset + request.profileId * 716
    for (index in source.indices) {
        if (index !in start + 353 until start + 716 && source[index] != candidate[index]) return false
    }
    val oldPlain = decryptRecord(source, start, rotation)
    val newPlain = decryptRecord(candidate, start, rotation)
    if (oldPlain[353].toInt() != request.expectedCurrentMarksmanship) return false
    for (index in 0 until 716) {
        if (index != 353 && index !in 696..699 && oldPlain[index] != newPlain[index]) return false
    }
    if (newPlain[353].toInt() != request.newMarksmanship) return false
    val checksum = NormalProfileChecksum.calculate(newPlain)
    repeat(4) { if (newPlain[696 + it] != (checksum ushr (8 * it)).toByte()) return false }
    if (request.expectedCurrentMarksmanship == request.newMarksmanship && !source.contentEquals(candidate)) {
        return false
    }
    return true
}

private fun exactFormat(format: SaveInspectionFormat): Boolean =
    format.compatibility == SaveCompatibility.SUPPORTED &&
        format.layout == SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX &&
        format.saveVersion == 103 && format.buildLabel == "04.12.02"

private fun decryptRecord(bytes: ByteArray, start: Int, rotation: SaveRotationTable): ByteArray =
    NormalSaveBlockDecryptor.decryptBlock(bytes.copyOfRange(start, start + 716), 716, rotation)
