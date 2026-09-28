package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.*

/** Pure fixed-plan verification. Cannot create success or grant edit authority. */
internal fun verifySaveEdit(
    source: ByteArray,
    candidate: ByteArray,
    request: SaveEditRequest.Operation,
    before: SaveInspectionV01Result.Success,
    after: SaveInspectionV01Result.Success,
    sourceProfiles: List<MercProfile>,
    candidateProfiles: List<MercProfile>,
    sourceLive: LiveMercStateInspectionResult.Success,
    candidateLive: LiveMercStateInspectionResult.Success,
    rotation: SaveRotationTable,
): Boolean {
    val stat = request as? SaveEditRequest.SetHiredStat
    val inventory = request as? SaveEditRequest.InventoryOperation
    if (stat == null && inventory == null) return false
    val desired = inventory?.let { InventoryMutation.desired(it) }
    val profileOffsets = stat?.let { setOf(it.stat().profileOffset()) } ?: inventory!!.slot().let {
        setOf(358 + it, 377 + it, 416 + 2 * it, 417 + 2 * it)
    }
    val soldierOffsets = stat?.let { it.stat().soldierOffset()..it.stat().soldierOffset() }
        ?: (12 + inventory!!.slot() * 36).let { it until it + 36 }
    if (!exactFormat(after.format) || before.format != after.format ||
        source.size != candidate.size || candidate.size > Ja2SaveEditor.MAX_SAVE_BYTES ||
        sourceProfiles.size != 170 || candidateProfiles.size != 170 ||
        before.campaign != after.campaign || before.roster.size != after.roster.size
    ) return false
    for (id in 0..169) {
        val expected = sourceProfiles[id].let {
            if (id != request.profileId()) it
            else if (stat != null) it.withStat(stat.stat(), stat.value())
            else it.copy(inventory = it.inventory.mapIndexed { slot, old ->
                if (slot == inventory!!.slot()) ProfileInventorySlot(desired!!.itemId(), desired.count(), desired.status()) else old
            })
        }
        if (candidateProfiles[id] != expected) return false
    }
    for (index in before.roster.indices) {
        val expected = before.roster[index].let {
            if (it.profileIndex == request.profileId() && stat != null) {
                it.copy(stats = it.stats.withStat(stat.stat(), stat.value()))
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
        val expectedSlots = if (target && inventory != null) old.slots.mapIndexed { slot, previous ->
            if (slot == inventory.slot()) previous.copy(itemId = desired!!.itemId(), objectCount = desired.count()) else previous
        } else old.slots
        if (old.profileIndex != new.profileIndex || expectedSlots != new.slots ||
            (target && stat != null && stat.stat().liveValue(old.stats) != stat.expectedCurrent()) ||
            new.stats != if (target && stat != null) old.stats.withStat(stat.stat(), stat.value()) else old.stats
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
    val firstProfileChange = start + profileOffsets.min()
    for (index in source.indices) {
        if (index !in firstProfileChange until start + 716 &&
            index !in soldierStart + soldierOffsets.first until soldierStart + 2328 && source[index] != candidate[index]
        ) return false
    }
    val oldPlain = decryptRecord(source, start, rotation)
    val newPlain = decryptRecord(candidate, start, rotation)
    if (stat != null && (oldPlain[stat.stat().profileOffset()].toInt() != stat.expectedCurrent() ||
        newPlain[stat.stat().profileOffset()].toInt() != stat.value())) return false
    for (index in 0 until 716) {
        if (index !in profileOffsets && index !in 696..699 && oldPlain[index] != newPlain[index]) return false
    }
    val checksum = NormalProfileChecksum.calculate(newPlain)
    repeat(4) { if (newPlain[696 + it] != (checksum ushr (8 * it)).toByte()) return false }
    val oldSoldier = NormalSaveBlockDecryptor.decryptBlock(source.copyOfRange(soldierStart, soldierStart + 2328), 2328, rotation)
    val newSoldier = NormalSaveBlockDecryptor.decryptBlock(candidate.copyOfRange(soldierStart, soldierStart + 2328), 2328, rotation)
    if (stat != null) {
        if (!stat.stat().admitsInjuryState(oldSoldier) || !stat.stat().admitsInjuryState(newSoldier)) return false
        if (oldSoldier[stat.stat().soldierOffset()].toInt() != stat.expectedCurrent() ||
            newSoldier[stat.stat().soldierOffset()].toInt() != stat.value()) return false
    } else if (!InventoryMutation.verify(inventory!!, oldPlain, newPlain, oldSoldier, newSoldier)) return false
    for (index in oldSoldier.indices) {
        if (index !in soldierOffsets && index !in 2208..2211 && oldSoldier[index] != newSoldier[index]) return false
    }
    val soldierChecksum = NormalSoldierChecksum.calculate(newSoldier)
    repeat(4) { if (newSoldier[2208 + it] != (soldierChecksum ushr (8 * it)).toByte()) return false }
    val noOp = if (stat != null) stat.expectedCurrent() == stat.value() else inventory!!.expected() == desired
    if (noOp && !source.contentEquals(candidate)) {
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
