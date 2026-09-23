package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader
import com.phishtopia.ja2fieldkit.core.model.InventorySlot
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.MercInventoryEntry
import com.phishtopia.ja2fieldkit.core.model.MercProfile
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.MercStats
import java.util.Collections

enum class RosterMembershipFailure {
    INVALID_PLAYER_TEAM_RANGE,
    INVALID_ACTIVE_MARKER,
    TRUNCATED_SOLDIER_RECORD,
    INVALID_SOLDIER_ID,
    INVALID_INNER_ACTIVE,
    INVALID_TEAM,
    MISSING_PC_FLAG,
    CHECKSUM_MISMATCH,
    INVALID_PROFILE_ID,
    DUPLICATE_PROFILE_ID,
    TRUNCATED_PATH_COUNT,
    TRUNCATED_PATH_DATA,
    INVALID_KEYRING_MARKER,
    TRUNCATED_KEYRING_DATA,
    HEADER_COUNT_MISMATCH,
    TRUNCATED_ACTIVE_MARKER,
    TRUNCATED_KEYRING_MARKER,
}

enum class RosterMembershipStage {
    TACTICAL_STATUS,
    ACTIVE_MARKER,
    SOLDIER_RECORD,
    SOLDIER_IDENTITY,
    SOLDIER_CHECKSUM,
    PATH_COUNT,
    PATH_DATA,
    KEYRING_MARKER,
    KEYRING_DATA,
    HEADER_CROSS_CHECK,
}

/** Structural diagnostics only; decrypted bytes, names, paths, and rotation material are omitted. */
class RosterMembershipException(
    val reason: RosterMembershipFailure,
    val stage: RosterMembershipStage,
    val actualSize: Int,
    val slotIndex: Int? = null,
    val absoluteOffset: Long? = null,
    val requiredEndExclusive: Long? = null,
    val pathNodeCount: Long? = null,
    val profileId: Int? = null,
    val expectedCount: Int? = null,
    val actualCount: Int? = null,
) : IllegalArgumentException(
    "Normal non-Linux Build 04.12.02 roster membership: $reason at $stage " +
        "(size=$actualSize, slot=$slotIndex, offset=$absoluteOffset, " +
        "requiredEndExclusive=$requiredEndExclusive, pathNodes=$pathNodeCount, " +
        "profile=$profileId, expectedCount=$expectedCount, actualCount=$actualCount)",
)

/**
 * Shared validated traversal of the first 20 normal SOLDIERTYPE slots.
 *
 * Roster and live inventory use identical membership, checksum, and tail-framing authority.
 * Soldier condition, assignments, sectors, paths, and keys remain uninterpreted.
 */
object NormalNonLinuxRosterDecoder {
    fun decodeBuild041202(saveBytes: ByteArray): List<MercRosterEntry> =
        Collections.unmodifiableList(scanBuild041202(saveBytes).map { it.rosterEntry })

    internal fun decodeInventoriesBuild041202(saveBytes: ByteArray): List<MercInventoryEntry> =
        Collections.unmodifiableList(scanBuild041202(saveBytes).map { soldier ->
            MercInventoryEntry(
                profileIndex = soldier.rosterEntry.profileIndex,
                name = soldier.rosterEntry.name,
                nickname = soldier.rosterEntry.nickname,
                slots = soldier.inventorySlots(),
            )
        })

    /** Only a narrow private inventory snapshot survives validation; no full soldier escapes. */
    private class ValidatedPlayer(val rosterEntry: MercRosterEntry, inventory: ByteArray) {
        private val inventory = inventory.copyOf()

        fun inventorySlots(): List<InventorySlot> = InventorySlotRole.entries.map { role ->
            val start = role.slotIndex * INVENTORY_RECORD_SIZE
            InventorySlot(role, InventoryObjectParser.parse(inventory.copyOfRange(start, start + INVENTORY_RECORD_SIZE)))
        }
    }

    private fun scanBuild041202(saveBytes: ByteArray): List<ValidatedPlayer> {
        val context = NormalNonLinuxProfileDecoder.decodeContextBuild041202(saveBytes)
        validateCanonicalPlayerTeamRange(saveBytes)

        var offset = context.frame.profileEndExclusive.toLong()
        val profileIds = ArrayList<Int>()
        val uniqueProfileIds = HashSet<Int>()
        val players = ArrayList<ValidatedPlayer>()

        repeat(PLAYER_SLOT_COUNT) { slotIndex ->
            requireAvailable(
                saveBytes,
                checkedAdd(offset, 1L),
                RosterMembershipFailure.TRUNCATED_ACTIVE_MARKER,
                RosterMembershipStage.ACTIVE_MARKER,
                slotIndex,
                offset,
            )
            val activeMarker = saveBytes[offset.toInt()].toInt() and 0xff
            if (activeMarker !in 0..1) {
                fail(
                    saveBytes,
                    RosterMembershipFailure.INVALID_ACTIVE_MARKER,
                    RosterMembershipStage.ACTIVE_MARKER,
                    slotIndex,
                    offset,
                )
            }
            offset = checkedAdd(offset, 1L)
            if (activeMarker == 0) return@repeat

            val soldierEnd = checkedAdd(offset, SOLDIER_RECORD_SIZE.toLong())
            requireAvailable(
                saveBytes,
                soldierEnd,
                RosterMembershipFailure.TRUNCATED_SOLDIER_RECORD,
                RosterMembershipStage.SOLDIER_RECORD,
                slotIndex,
                offset,
            )
            val decrypted = NormalSaveBlockDecryptor.decryptBlock(
                encryptedBlock = saveBytes.copyOfRange(offset.toInt(), soldierEnd.toInt()),
                expectedBlockSize = SOLDIER_RECORD_SIZE,
                rotationTable = context.rotation,
            )
            val previousCount = profileIds.size
            validateSoldier(decrypted, slotIndex, saveBytes.size, profileIds, uniqueProfileIds)
            if (profileIds.size != previousCount) {
                players += ValidatedPlayer(
                    context.profiles[profileIds.last()].toRosterEntry(),
                    decrypted.copyOfRange(
                        INVENTORY_START_OFFSET,
                        INVENTORY_START_OFFSET + INVENTORY_SLOT_COUNT * INVENTORY_RECORD_SIZE,
                    ),
                )
            }
            offset = soldierEnd

            val pathCountEnd = checkedAdd(offset, PATH_COUNT_SIZE.toLong())
            requireAvailable(
                saveBytes,
                pathCountEnd,
                RosterMembershipFailure.TRUNCATED_PATH_COUNT,
                RosterMembershipStage.PATH_COUNT,
                slotIndex,
                offset,
            )
            val pathNodeCount = LittleEndianReader(saveBytes).u32(offset.toInt())
            offset = pathCountEnd
            val pathEnd = checkedAdd(offset, Math.multiplyExact(pathNodeCount, PATH_NODE_SIZE.toLong()))
            requireAvailable(
                saveBytes,
                pathEnd,
                RosterMembershipFailure.TRUNCATED_PATH_DATA,
                RosterMembershipStage.PATH_DATA,
                slotIndex,
                offset,
                pathNodeCount,
            )
            offset = pathEnd

            val keyringMarkerEnd = checkedAdd(offset, 1L)
            requireAvailable(
                saveBytes,
                keyringMarkerEnd,
                RosterMembershipFailure.TRUNCATED_KEYRING_MARKER,
                RosterMembershipStage.KEYRING_MARKER,
                slotIndex,
                offset,
            )
            val hasKeyring = saveBytes[offset.toInt()].toInt() and 0xff
            if (hasKeyring !in 0..1) {
                fail(
                    saveBytes,
                    RosterMembershipFailure.INVALID_KEYRING_MARKER,
                    RosterMembershipStage.KEYRING_MARKER,
                    slotIndex,
                    offset,
                )
            }
            offset = keyringMarkerEnd
            if (hasKeyring == 1) {
                val keyringEnd = checkedAdd(offset, KEYRING_SIZE.toLong())
                requireAvailable(
                    saveBytes,
                    keyringEnd,
                    RosterMembershipFailure.TRUNCATED_KEYRING_DATA,
                    RosterMembershipStage.KEYRING_DATA,
                    slotIndex,
                    offset,
                )
                offset = keyringEnd
            }
        }

        if (profileIds.size != context.header.playerMercCount) {
            throw RosterMembershipException(
                reason = RosterMembershipFailure.HEADER_COUNT_MISMATCH,
                stage = RosterMembershipStage.HEADER_CROSS_CHECK,
                actualSize = saveBytes.size,
                expectedCount = context.header.playerMercCount,
                actualCount = profileIds.size,
            )
        }

        return Collections.unmodifiableList(players)
    }

    private fun validateCanonicalPlayerTeamRange(saveBytes: ByteArray) {
        val first = saveBytes[FIRST_PLAYER_ID_ABSOLUTE_OFFSET].toInt() and 0xff
        val last = saveBytes[LAST_PLAYER_ID_ABSOLUTE_OFFSET].toInt() and 0xff
        if (first != 0 || last != PLAYER_SLOT_COUNT - 1) {
            throw RosterMembershipException(
                reason = RosterMembershipFailure.INVALID_PLAYER_TEAM_RANGE,
                stage = RosterMembershipStage.TACTICAL_STATUS,
                actualSize = saveBytes.size,
                absoluteOffset = FIRST_PLAYER_ID_ABSOLUTE_OFFSET.toLong(),
                expectedCount = PLAYER_SLOT_COUNT,
                actualCount = if (last >= first) last - first + 1 else 0,
            )
        }
    }

    private fun validateSoldier(
        bytes: ByteArray,
        slotIndex: Int,
        saveSize: Int,
        profileIds: MutableList<Int>,
        uniqueProfileIds: MutableSet<Int>,
    ) {
        val reader = LittleEndianReader(bytes)
        if (reader.u8(ID_OFFSET) != slotIndex) {
            identityFailure(saveSize, RosterMembershipFailure.INVALID_SOLDIER_ID, slotIndex)
        }
        if (reader.i8(INNER_ACTIVE_OFFSET).toInt() != 1) {
            identityFailure(saveSize, RosterMembershipFailure.INVALID_INNER_ACTIVE, slotIndex)
        }
        if (reader.i8(TEAM_OFFSET).toInt() != OUR_TEAM) {
            identityFailure(saveSize, RosterMembershipFailure.INVALID_TEAM, slotIndex)
        }
        val statusFlags = reader.u32(STATUS_FLAGS_OFFSET)
        if (statusFlags and SOLDIER_PC == 0L) {
            identityFailure(saveSize, RosterMembershipFailure.MISSING_PC_FLAG, slotIndex)
        }
        if (reader.u32(STORED_CHECKSUM_OFFSET) != sourceChecksum(reader)) {
            throw RosterMembershipException(
                reason = RosterMembershipFailure.CHECKSUM_MISMATCH,
                stage = RosterMembershipStage.SOLDIER_CHECKSUM,
                actualSize = saveSize,
                slotIndex = slotIndex,
            )
        }
        if (statusFlags and SOLDIER_VEHICLE != 0L) return

        val profileId = reader.u8(PROFILE_OFFSET)
        if (profileId !in 0 until NormalProfileRotationRecovery.RECORD_COUNT) {
            throw RosterMembershipException(
                reason = RosterMembershipFailure.INVALID_PROFILE_ID,
                stage = RosterMembershipStage.SOLDIER_IDENTITY,
                actualSize = saveSize,
                slotIndex = slotIndex,
                profileId = profileId,
            )
        }
        if (!uniqueProfileIds.add(profileId)) {
            throw RosterMembershipException(
                reason = RosterMembershipFailure.DUPLICATE_PROFILE_ID,
                stage = RosterMembershipStage.SOLDIER_IDENTITY,
                actualSize = saveSize,
                slotIndex = slotIndex,
                profileId = profileId,
            )
        }
        profileIds += profileId
    }

    private fun sourceChecksum(reader: LittleEndianReader): Long {
        var sum = CHECKSUM_STAT_OFFSET_PAIRS.fold(1L) { checksum, offsets ->
            wrap(
                (checksum + 1L + reader.i8(offsets.addend)) *
                    (1L + reader.i8(offsets.multiplier)),
            )
        }
        sum = wrap(sum + 1L + reader.u8(PROFILE_OFFSET))
        repeat(INVENTORY_SLOT_COUNT) { slot ->
            val recordOffset = INVENTORY_RECORD_SIZE * slot
            sum = wrap(
                sum +
                    reader.u16(INVENTORY_START_OFFSET + recordOffset) +
                    reader.u8(INVENTORY_COUNT_OFFSET + recordOffset),
            )
        }
        return sum
    }

    private fun identityFailure(
        saveSize: Int,
        reason: RosterMembershipFailure,
        slotIndex: Int,
    ): Nothing = throw RosterMembershipException(
        reason = reason,
        stage = RosterMembershipStage.SOLDIER_IDENTITY,
        actualSize = saveSize,
        slotIndex = slotIndex,
    )

    private fun requireAvailable(
        saveBytes: ByteArray,
        requiredEndExclusive: Long,
        reason: RosterMembershipFailure,
        stage: RosterMembershipStage,
        slotIndex: Int,
        absoluteOffset: Long,
        pathNodeCount: Long? = null,
    ) {
        if (requiredEndExclusive > saveBytes.size.toLong()) {
            throw RosterMembershipException(
                reason = reason,
                stage = stage,
                actualSize = saveBytes.size,
                slotIndex = slotIndex,
                absoluteOffset = absoluteOffset,
                requiredEndExclusive = requiredEndExclusive,
                pathNodeCount = pathNodeCount,
            )
        }
    }

    private fun fail(
        saveBytes: ByteArray,
        reason: RosterMembershipFailure,
        stage: RosterMembershipStage,
        slotIndex: Int,
        absoluteOffset: Long,
    ): Nothing = throw RosterMembershipException(
        reason = reason,
        stage = stage,
        actualSize = saveBytes.size,
        slotIndex = slotIndex,
        absoluteOffset = absoluteOffset,
    )

    private fun checkedAdd(left: Long, right: Long): Long = Math.addExact(left, right)

    private fun wrap(value: Long): Long = value and UINT32_MASK

    private fun MercProfile.toRosterEntry(): MercRosterEntry = MercRosterEntry(
        profileIndex = profileId,
        name = name,
        nickname = nickname,
        stats = MercStats(
            health = lifeMax,
            agility = agility,
            dexterity = dexterity,
            strength = strength,
            leadership = leadership,
            wisdom = wisdom,
            experienceLevel = experienceLevel,
            marksmanship = marksmanship,
            mechanical = mechanical,
            explosives = explosives,
            medical = medical,
        ),
    )

    private const val PLAYER_SLOT_COUNT = 20
    private const val FIRST_PLAYER_ID_ABSOLUTE_OFFSET = 436
    private const val LAST_PLAYER_ID_ABSOLUTE_OFFSET = 437
    private const val SOLDIER_RECORD_SIZE = 2328
    private const val PATH_COUNT_SIZE = 4
    private const val PATH_NODE_SIZE = 20
    private const val KEYRING_SIZE = 128
    private const val OUR_TEAM = 0
    private const val SOLDIER_PC = 0x00000008L
    private const val SOLDIER_VEHICLE = 0x00008000L
    private const val ID_OFFSET = 0
    private const val STATUS_FLAGS_OFFSET = 8
    private const val INVENTORY_START_OFFSET = 12
    private const val INVENTORY_COUNT_OFFSET = 14
    private const val INVENTORY_RECORD_SIZE = 36
    private const val INVENTORY_SLOT_COUNT = 19
    private const val DEXTERITY_OFFSET = 840
    private const val EXPERIENCE_LEVEL_OFFSET = 849
    private const val LIFE_OFFSET = 868
    private const val AGILITY_OFFSET = 880
    private const val STRENGTH_OFFSET = 886
    private const val MECHANICAL_OFFSET = 916
    private const val LIFE_MAX_OFFSET = 917
    private const val MEDICAL_OFFSET = 1372
    private const val MARKSMANSHIP_OFFSET = 1377
    private const val EXPLOSIVE_OFFSET = 1378
    private const val INNER_ACTIVE_OFFSET = 751
    private const val TEAM_OFFSET = 752
    private const val PROFILE_OFFSET = 1825
    private const val STORED_CHECKSUM_OFFSET = 2208
    private const val UINT32_MASK = 0xffff_ffffL

    private data class ChecksumStatOffsets(val addend: Int, val multiplier: Int)

    private val CHECKSUM_STAT_OFFSET_PAIRS =
        listOf(
            ChecksumStatOffsets(LIFE_OFFSET, LIFE_MAX_OFFSET),
            ChecksumStatOffsets(AGILITY_OFFSET, DEXTERITY_OFFSET),
            ChecksumStatOffsets(STRENGTH_OFFSET, MARKSMANSHIP_OFFSET),
            ChecksumStatOffsets(MEDICAL_OFFSET, MECHANICAL_OFFSET),
            ChecksumStatOffsets(EXPLOSIVE_OFFSET, EXPERIENCE_LEVEL_OFFSET),
        )
}
