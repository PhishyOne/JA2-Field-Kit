package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.*

/** Pure fixed-plan verification. Cannot create success or grant edit authority. */
internal fun verifySaveEdit(
    source: ByteArray,
    candidate: ByteArray,
    request: SaveEditRequest.SetHiredStat,
    before: SaveInspectionV01Result.Success,
    after: SaveInspectionV01Result.Success,
    sourceProfiles: List<MercProfile>,
    candidateProfiles: List<MercProfile>,
    sourceLive: LiveMercStateInspectionResult.Success,
    candidateLive: LiveMercStateInspectionResult.Success,
    rotation: SaveRotationTable,
): Boolean {
    val profileOffset = request.stat().profileOffset()
    val soldierOffset = request.stat().soldierOffset()
    if (!exactFormat(after.format) || before.format != after.format ||
        source.size != candidate.size || candidate.size > Ja2SaveEditor.MAX_SAVE_BYTES ||
        sourceProfiles.size != 170 || candidateProfiles.size != 170 ||
        before.campaign != after.campaign || before.roster.size != after.roster.size ||
        request.stat().profileValue(candidateProfiles[request.profileId()]) != request.value()
    ) return false
    for (id in 0..169) {
        val expected = sourceProfiles[id].let {
            if (id == request.profileId()) it.withStat(request.stat(), request.value()) else it
        }
        if (candidateProfiles[id] != expected) return false
    }
    for (index in before.roster.indices) {
        val expected = before.roster[index].let {
            if (it.profileIndex == request.profileId()) {
                it.copy(stats = it.stats.withStat(request.stat(), request.value()))
            } else it
        }
        if (after.roster[index] != expected) return false
    }
    if (sourceLive.format != before.format || candidateLive.format != after.format ||
        sourceLive.mercs.size != candidateLive.mercs.size ||
        sourceLive.mercs.count { it.profileIndex == request.profileId() } != 1 ||
        before.roster.count { it.profileIndex == request.profileId() } != 1
    ) return false
    for ((old, new) in sourceLive.mercs.zip(candidateLive.mercs)) {
        val target = old.profileIndex == request.profileId()
        if (old.profileIndex != new.profileIndex || old.slots != new.slots ||
            (target && request.stat().liveValue(old.stats) != request.expectedCurrent()) ||
            new.stats != if (target) old.stats.withStat(request.stat(), request.value()) else old.stats
        ) return false
    }
    val locations = NormalNonLinuxRosterDecoder.validatedRecordLocations(source)
    if (locations != NormalNonLinuxRosterDecoder.validatedRecordLocations(candidate)) return false
    val targets = locations.filter { it.profileIndex == request.profileId() }
    if (targets.size != 1 || !targets.single().playerMerc) return false
    val soldierStart = targets.single().absoluteOffset
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
    val start = sourceFrame.profileStartOffset + request.profileId() * 716
    for (index in source.indices) {
        if (index !in start + profileOffset until start + 716 &&
            index !in soldierStart + soldierOffset until soldierStart + 2328 && source[index] != candidate[index]
        ) return false
    }
    val oldPlain = decryptRecord(source, start, rotation)
    val newPlain = decryptRecord(candidate, start, rotation)
    if (oldPlain[profileOffset].toInt() != request.expectedCurrent()) return false
    for (index in 0 until 716) {
        if (index != profileOffset && index !in 696..699 && oldPlain[index] != newPlain[index]) return false
    }
    if (newPlain[profileOffset].toInt() != request.value()) return false
    val checksum = NormalProfileChecksum.calculate(newPlain)
    repeat(4) { if (newPlain[696 + it] != (checksum ushr (8 * it)).toByte()) return false }
    val oldSoldier = NormalSaveBlockDecryptor.decryptBlock(source.copyOfRange(soldierStart, soldierStart + 2328), 2328, rotation)
    val newSoldier = NormalSaveBlockDecryptor.decryptBlock(candidate.copyOfRange(soldierStart, soldierStart + 2328), 2328, rotation)
    if (!request.stat().admitsInjuryState(oldSoldier) || !request.stat().admitsInjuryState(newSoldier)) return false
    if (oldSoldier[soldierOffset].toInt() != request.expectedCurrent() || newSoldier[soldierOffset].toInt() != request.value()) return false
    for (index in oldSoldier.indices) {
        if (index != soldierOffset && index !in 2208..2211 && oldSoldier[index] != newSoldier[index]) return false
    }
    val soldierChecksum = NormalSoldierChecksum.calculate(newSoldier)
    repeat(4) { if (newSoldier[2208 + it] != (soldierChecksum ushr (8 * it)).toByte()) return false }
    if (request.expectedCurrent() == request.value() && !source.contentEquals(candidate)) {
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
