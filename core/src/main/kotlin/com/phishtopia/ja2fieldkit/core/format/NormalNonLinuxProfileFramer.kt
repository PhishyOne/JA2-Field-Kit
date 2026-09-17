package com.phishtopia.ja2fieldkit.core.format

import com.phishtopia.ja2fieldkit.core.io.LittleEndianReader

enum class ProfileFramingFailure {
    TRUNCATED_INPUT,
    UNSUPPORTED_DYNAMIC_LAPTOP_TAIL,
}

enum class ProfileFramingStage {
    TACTICAL_STATUS,
    CURRENT_SECTOR,
    GAME_CLOCK,
    STRATEGIC_EVENT_COUNT,
    STRATEGIC_EVENTS,
    LAPTOP_FIXED_BLOCK,
    PROFILE_TABLE,
}

/**
 * Structural diagnostics only: no payload, plaintext, key material, names, or paths.
 *
 * [requiredEndExclusive], when present, is the exclusive end of the required byte range, expressed
 * as a zero-based absolute byte offset into the supplied save.
 */
class ProfileFramingException(
    val reason: ProfileFramingFailure,
    val stage: ProfileFramingStage,
    val actualSize: Int,
    val requiredEndExclusive: Long? = null,
    val eventCount: Long? = null,
    val bobbyRayOrderUsedCount: Int? = null,
    val insurancePayoutUsedCount: Int? = null,
) : IllegalArgumentException(
    "Normal non-Linux Build 04.12.02 profile framing: $reason at $stage " +
        "(size=$actualSize, requiredEndExclusive=$requiredEndExclusive, events=$eventCount, " +
        "orderUsed=$bobbyRayOrderUsedCount, payoutUsed=$insurancePayoutUsedCount)",
)

/**
 * A framed encrypted MERCPROFILESTRUCT table and its normal non-Linux layout metadata.
 *
 * [profileStartOffset] and [profileEndExclusive] are zero-based absolute byte offsets into the
 * supplied save. The start is inclusive and [profileEndExclusive] is exclusive.
 *
 * The encrypted bytes are snapshotted on construction and copied on every access.
 */
class EncryptedProfileFrame internal constructor(
    val profileStartOffset: Int,
    val profileEndExclusive: Int,
    val eventCount: Long,
    val bobbyRayOrderArraySize: Int,
    val bobbyRayOrderUsedCount: Int,
    val insurancePayoutArraySize: Int,
    val insurancePayoutUsedCount: Int,
    encryptedProfileBytes: ByteArray,
) {
    private val encryptedProfileSnapshot = encryptedProfileBytes.copyOf()

    init {
        require(profileStartOffset >= 0) { "Profile start offset must be non-negative" }
        require(
            profileEndExclusive.toLong() - profileStartOffset.toLong() ==
                SaveLayoutFacts.MERC_PROFILE_BLOCK_SIZE.toLong(),
        ) {
            "Profile offsets must span the exact encrypted profile-table size"
        }
        require(encryptedProfileSnapshot.size == SaveLayoutFacts.MERC_PROFILE_BLOCK_SIZE) {
            "Encrypted profile snapshot must have the exact profile-table size"
        }
    }

    val encryptedProfileBytes: ByteArray
        get() = encryptedProfileSnapshot.copyOf()

    override fun toString(): String =
        "EncryptedProfileFrame(profileStartOffset=$profileStartOffset, " +
            "profileEndExclusive=$profileEndExclusive, " +
            "events=$eventCount, encryptedProfiles=${encryptedProfileSnapshot.size} bytes)"
}

/**
 * Read-only framer for the evidenced normal non-Linux v103 / Build 04.12.02 layout.
 *
 * This validates the existing narrow header identity, walks only fixed-size structures and the
 * counted strategic-event array, and fails closed when either platform-dependent laptop tail is
 * present. It neither detects a save family nor recovers/decrypts profile rotation data.
 */
object NormalNonLinuxProfileFramer {
    fun frameBuild041202(saveBytes: ByteArray): EncryptedProfileFrame {
        // Preserve the established header parser's exact truncated/unsupported contracts.
        SaveHeaderParser.parseBuild041202(saveBytes)

        var offset = SaveLayoutFacts.NORMAL_HEADER_SIZE.toLong()
        offset = requireFixedStage(
            saveBytes,
            offset,
            SaveLayoutFacts.NORMAL_NON_LINUX_TACTICAL_STATUS_SIZE,
            ProfileFramingStage.TACTICAL_STATUS,
        )
        offset = requireFixedStage(
            saveBytes,
            offset,
            SaveLayoutFacts.NORMAL_NON_LINUX_CURRENT_SECTOR_SIZE,
            ProfileFramingStage.CURRENT_SECTOR,
        )
        offset = requireFixedStage(
            saveBytes,
            offset,
            SaveLayoutFacts.NORMAL_NON_LINUX_GAME_CLOCK_SIZE,
            ProfileFramingStage.GAME_CLOCK,
        )

        val eventCountEnd = checkedAdd(
            offset,
            SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_COUNT_SIZE.toLong(),
        )
        requireAvailable(
            saveBytes,
            eventCountEnd,
            ProfileFramingStage.STRATEGIC_EVENT_COUNT,
        )
        val eventCount = LittleEndianReader(saveBytes).u32(offset.toInt())
        offset = eventCountEnd

        val eventBytes = Math.multiplyExact(
            eventCount,
            SaveLayoutFacts.NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE.toLong(),
        )
        val eventsEnd = checkedAdd(offset, eventBytes)
        requireAvailable(
            saveBytes,
            eventsEnd,
            ProfileFramingStage.STRATEGIC_EVENTS,
            eventCount,
        )
        offset = eventsEnd

        val laptopStart = offset
        val laptopEnd = checkedAdd(laptopStart, SaveLayoutFacts.NORMAL_NON_LINUX_LAPTOP_FIXED_SIZE.toLong())
        requireAvailable(
            saveBytes,
            laptopEnd,
            ProfileFramingStage.LAPTOP_FIXED_BLOCK,
            eventCount,
        )

        // laptopEnd fitting the Int-sized input proves every fixed-block offset is safe to narrow.
        val reader = LittleEndianReader(saveBytes)
        val laptopStartInt = laptopStart.toInt()
        val orderArraySize = reader.u8(
            laptopStartInt + SaveLayoutFacts.NORMAL_NON_LINUX_BOBBY_RAY_ORDER_ARRAY_SIZE_OFFSET,
        )
        val orderUsedCount = reader.u8(
            laptopStartInt + SaveLayoutFacts.NORMAL_NON_LINUX_BOBBY_RAY_ORDER_USED_COUNT_OFFSET,
        )
        val payoutArraySize = reader.u8(
            laptopStartInt + SaveLayoutFacts.NORMAL_NON_LINUX_INSURANCE_PAYOUT_ARRAY_SIZE_OFFSET,
        )
        val payoutUsedCount = reader.u8(
            laptopStartInt + SaveLayoutFacts.NORMAL_NON_LINUX_INSURANCE_PAYOUT_USED_COUNT_OFFSET,
        )
        if (orderUsedCount != 0 || payoutUsedCount != 0) {
            throw ProfileFramingException(
                reason = ProfileFramingFailure.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL,
                stage = ProfileFramingStage.LAPTOP_FIXED_BLOCK,
                actualSize = saveBytes.size,
                eventCount = eventCount,
                bobbyRayOrderUsedCount = orderUsedCount,
                insurancePayoutUsedCount = payoutUsedCount,
            )
        }

        val profileStart = laptopEnd
        val profileEnd = checkedAdd(profileStart, SaveLayoutFacts.MERC_PROFILE_BLOCK_SIZE.toLong())
        requireAvailable(
            saveBytes,
            profileEnd,
            ProfileFramingStage.PROFILE_TABLE,
            eventCount,
        )

        val profileStartInt = profileStart.toInt()
        val profileEndInt = profileEnd.toInt()
        return EncryptedProfileFrame(
            profileStartOffset = profileStartInt,
            profileEndExclusive = profileEndInt,
            eventCount = eventCount,
            bobbyRayOrderArraySize = orderArraySize,
            bobbyRayOrderUsedCount = orderUsedCount,
            insurancePayoutArraySize = payoutArraySize,
            insurancePayoutUsedCount = payoutUsedCount,
            encryptedProfileBytes = saveBytes.copyOfRange(profileStartInt, profileEndInt),
        )
    }

    private fun requireFixedStage(
        saveBytes: ByteArray,
        start: Long,
        size: Int,
        stage: ProfileFramingStage,
    ): Long = checkedAdd(start, size.toLong()).also { end ->
        requireAvailable(saveBytes, end, stage)
    }

    private fun requireAvailable(
        saveBytes: ByteArray,
        requiredEndExclusive: Long,
        stage: ProfileFramingStage,
        eventCount: Long? = null,
    ) {
        if (requiredEndExclusive > saveBytes.size.toLong()) {
            throw ProfileFramingException(
                reason = ProfileFramingFailure.TRUNCATED_INPUT,
                stage = stage,
                actualSize = saveBytes.size,
                requiredEndExclusive = requiredEndExclusive,
                eventCount = eventCount,
            )
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long = Math.addExact(left, right)
}
