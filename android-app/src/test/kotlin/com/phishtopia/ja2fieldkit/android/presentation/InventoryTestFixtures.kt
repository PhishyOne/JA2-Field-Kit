package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.InventoryAttachment
import com.phishtopia.ja2fieldkit.core.model.InventoryObject
import com.phishtopia.ja2fieldkit.core.model.InventoryPayload
import com.phishtopia.ja2fieldkit.core.model.InventorySlot
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.LiveInventoryInspectionResult
import com.phishtopia.ja2fieldkit.core.model.CampaignSector
import com.phishtopia.ja2fieldkit.core.model.CampaignSummaryV01
import com.phishtopia.ja2fieldkit.core.model.MercInventoryEntry
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.MercStats
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result

internal fun inventorySuccess(
    inspection: SaveInspectionV01Result,
    profileOrder: List<Int> = (inspection as? SaveInspectionV01Result.Success)
        ?.roster
        ?.map { it.profileIndex }
        .orEmpty(),
    records: Map<Pair<Int, InventorySlotRole>, Pair<Int, Int>> = emptyMap(),
): LiveInventoryInspectionResult.Success {
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
    inventories: List<MercInventoryEntry>,
): LiveInventoryInspectionResult.Success = construct(
    LiveInventoryInspectionResult.Success::class.java,
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
    slots: List<InventorySlot> = InventorySlotRole.entries.map { inventorySlot(it, 0, 0) },
): MercInventoryEntry = construct(
    MercInventoryEntry::class.java,
    profileIndex,
    "Inventory $profileIndex",
    null,
    slots,
)

internal fun inventorySlot(role: InventorySlotRole, itemId: Int, objectCount: Int): InventorySlot =
    construct(InventorySlot::class.java, role, inventoryObject(itemId, objectCount))

private fun inventoryObject(itemId: Int, objectCount: Int): InventoryObject {
    val payload = if (itemId == 0 && objectCount == 0) {
        InventoryPayload.Empty
    } else {
        construct(InventoryPayload.Unknown::class.java, ByteArray(12))
    }
    return construct(
        InventoryObject::class.java,
        itemId,
        objectCount,
        0,
        List(4) { InventoryAttachment(0, 0) },
        0,
        0,
        0,
        0,
        0,
        0,
        listOf(0, 0),
        payload,
        ByteArray(36),
    )
}

@Suppress("UNCHECKED_CAST")
private fun <T> construct(type: Class<T>, vararg arguments: Any?): T {
    val constructor = type.declaredConstructors.single { it.parameterCount == arguments.size }
        .apply { isAccessible = true }
    return constructor.newInstance(*arguments) as T
}
