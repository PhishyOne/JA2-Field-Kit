package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole.*

data class InventoryGroupPresentation(
    val title: String,
    val slots: List<InventorySlotPresentation>,
)

private val inventorySections = listOf(
    "Head / armor" to listOf(HELMET, HEAD_1, HEAD_2, VEST, LEGS),
    "Hands" to listOf(MAIN_HAND, OFF_HAND),
    "Big pockets" to listOf(BIG_POCKET_1, BIG_POCKET_2, BIG_POCKET_3, BIG_POCKET_4),
    "Small pockets" to listOf(SMALL_POCKET_1, SMALL_POCKET_2, SMALL_POCKET_3, SMALL_POCKET_4,
        SMALL_POCKET_5, SMALL_POCKET_6, SMALL_POCKET_7, SMALL_POCKET_8),
)

/** Null rejects the entire inventory before any cells render; labels never determine identity. */
fun groupInventory(slots: List<InventorySlotPresentation>): List<InventoryGroupPresentation>? {
    val canonical = InventorySlotRole.entries.toSet()
    if (slots.size != canonical.size || slots.map { it.role }.toSet() != canonical) return null
    val byRole = slots.associateBy { it.role }
    return inventorySections.map { (title, roles) ->
        InventoryGroupPresentation(title, roles.map { byRole.getValue(it) })
    }
}

/** One visible, selectable text node provides both the slot label and its exact read facts. */
fun InventorySlotPresentation.visibleText(): String {
    val value = when (val item = contents) {
        InventoryContentsPresentation.Empty -> "Empty"
        is InventoryContentsPresentation.Occupied -> "Item #${item.itemId} · Count ${item.objectCount}"
    }
    return "$label\n$value"
}
