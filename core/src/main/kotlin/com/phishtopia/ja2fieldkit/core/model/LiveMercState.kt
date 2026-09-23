package com.phishtopia.ja2fieldkit.core.model

import java.util.Collections

/** Exact signed-byte tactical facts already used by the validated soldier checksum. */
data class LiveMercStats(
    val life: Int,
    val lifeMax: Int,
    val agility: Int,
    val dexterity: Int,
    val strength: Int,
    val experienceLevel: Int,
    val marksmanship: Int,
    val mechanical: Int,
    val explosives: Int,
    val medical: Int,
)

/** Common inventory facts only; no raw record, opaque payload, or item classification. */
data class LiveInventorySlot(val role: InventorySlotRole, val itemId: Int, val objectCount: Int)

class LiveMercState internal constructor(
    val profileIndex: Int,
    val stats: LiveMercStats,
    slots: List<LiveInventorySlot>,
) {
    val slots: List<LiveInventorySlot> = Collections.unmodifiableList(ArrayList(slots))
}

/** Read-only presentation boundary; no save or soldier bytes or parser internals escape. */
sealed interface LiveMercStateInspectionResult {
    val format: SaveInspectionFormat

    class Success internal constructor(
        override val format: SaveInspectionFormat,
        mercs: List<LiveMercState>,
    ) : LiveMercStateInspectionResult {
        val mercs: List<LiveMercState> = Collections.unmodifiableList(ArrayList(mercs))
    }

    data class Failure(
        override val format: SaveInspectionFormat,
        val failure: SaveInspectionFailure,
    ) : LiveMercStateInspectionResult
}
