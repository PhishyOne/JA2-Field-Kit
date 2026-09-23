package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.*

internal fun inventorySuccess(
    inspection: SaveInspectionV01Result,
    profileOrder: List<Int> = (inspection as? SaveInspectionV01Result.Success)
        ?.roster
        ?.map { it.profileIndex }
        .orEmpty(),
    records: Map<Pair<Int, InventorySlotRole>, Pair<Int, Int>> = emptyMap(),
): LiveMercStateInspectionResult.Success {
    val inventories = profileOrder.map { profileIndex ->
        inventoryEntry(
            profileIndex,
            InventorySlotRole.entries.map { role ->
                val item = records[profileIndex to role]
                inventorySlot(role, item?.first ?: 0, item?.second ?: 0)
            },
        )
    }
    return inventorySuccess(inspection.format, inventories)
}

internal fun inventorySuccess(
    format: SaveInspectionFormat,
    inventories: List<LiveMercState>,
): LiveMercStateInspectionResult.Success = construct(
    LiveMercStateInspectionResult.Success::class.java,
    format,
    inventories,
)

internal fun inspectionSuccess(
    format: SaveInspectionFormat,
    profileIndices: List<Int>,
): SaveInspectionV01Result.Success = construct(
    SaveInspectionV01Result.Success::class.java,
    format,
    CampaignSummaryV01(12, 7, 5, CampaignSector(9, 4, 0), profileIndices.size, 45_000),
    profileIndices.map { profileIndex ->
        MercRosterEntry(
            profileIndex,
            "Merc $profileIndex",
            "M$profileIndex",
            MercStats(80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90),
        )
    },
)

internal fun inventoryEntry(
    profileIndex: Int,
    slots: List<LiveInventorySlot> = InventorySlotRole.entries.map { inventorySlot(it, 0, 0) },
): LiveMercState = construct(
    LiveMercState::class.java,
    profileIndex,
    LiveMercStats(profileIndex, 99, -128, -2, 127, -3, -4, -5, -6, -7),
    slots,
)

internal fun inventorySlot(role: InventorySlotRole, itemId: Int, objectCount: Int): LiveInventorySlot =
    LiveInventorySlot(role, itemId, objectCount)

@Suppress("UNCHECKED_CAST")
private fun <T> construct(type: Class<T>, vararg arguments: Any?): T {
    val constructor = type.declaredConstructors.single { it.parameterCount == arguments.size }
        .apply { isAccessible = true }
    return constructor.newInstance(*arguments) as T
}
