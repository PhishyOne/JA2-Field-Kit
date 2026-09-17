package com.phishtopia.ja2fieldkit.core.format

object SaveLayoutFacts {
    /** Confirmed for the normal Windows/Stracciatella-style header used by the examined Reborn save. */
    const val NORMAL_HEADER_SIZE = 432

    /** Evidenced normal non-Linux v103 / Build 04.12.02 body-prefix sizes. */
    const val NORMAL_NON_LINUX_TACTICAL_STATUS_SIZE = 316
    const val NORMAL_NON_LINUX_CURRENT_SECTOR_SIZE = 5
    const val NORMAL_NON_LINUX_GAME_CLOCK_SIZE = 62
    const val NORMAL_NON_LINUX_STRATEGIC_EVENT_COUNT_SIZE = 4
    const val NORMAL_NON_LINUX_STRATEGIC_EVENT_SIZE = 28
    const val NORMAL_NON_LINUX_LAPTOP_FIXED_SIZE = 7440

    /** Byte offsets within the fixed normal non-Linux laptop block. */
    const val NORMAL_NON_LINUX_BOBBY_RAY_ORDER_ARRAY_SIZE_OFFSET = 7276
    const val NORMAL_NON_LINUX_BOBBY_RAY_ORDER_USED_COUNT_OFFSET = 7277
    const val NORMAL_NON_LINUX_INSURANCE_PAYOUT_ARRAY_SIZE_OFFSET = 7284
    const val NORMAL_NON_LINUX_INSURANCE_PAYOUT_USED_COUNT_OFFSET = 7285

    /** Confirmed count of encrypted MERCPROFILESTRUCT records in the examined format. */
    const val MERC_PROFILE_COUNT = 170

    /** Confirmed serialized size of each encrypted MERCPROFILESTRUCT record. */
    const val MERC_PROFILE_SIZE = 716

    /** Exact encrypted profile-table size for the evidenced layout. */
    const val MERC_PROFILE_BLOCK_SIZE = MERC_PROFILE_COUNT * MERC_PROFILE_SIZE

    /** Confirmed serialized size of each encrypted active SOLDIERTYPE record. */
    const val ACTIVE_SOLDIER_SIZE = 2328
}

data class HeaderProbe(
    val fileSize: Int,
    val hasCompleteNormalHeader: Boolean,
    val rawNormalHeader: ByteArray?,
    val notes: List<String>,
)

object SaveHeaderProbe {
    fun probe(bytes: ByteArray): HeaderProbe {
        if (bytes.size < SaveLayoutFacts.NORMAL_HEADER_SIZE) {
            return HeaderProbe(
                fileSize = bytes.size,
                hasCompleteNormalHeader = false,
                rawNormalHeader = null,
                notes = listOf(
                    "File is smaller than the confirmed 432-byte normal header; do not attempt normal-format parsing.",
                ),
            )
        }

        return HeaderProbe(
            fileSize = bytes.size,
            hasCompleteNormalHeader = true,
            rawNormalHeader = bytes.copyOfRange(0, SaveLayoutFacts.NORMAL_HEADER_SIZE),
            notes = listOf(
                "A complete 432-byte candidate header is present.",
                "File size alone does not identify JA2 Reborn, Stracciatella, or classic JA2.",
                "The Build 04.12.02 parser is layout-only; format detection and encryption selection remain disabled.",
            ),
        )
    }
}
