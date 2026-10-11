package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.ProfileInventorySlot
import java.util.Collections

/** Numeric read facts only; catalog names are resolved at rendering time. */
data class ProfileInventoryPresentation(
    val role: InventorySlotRole,
    val itemId: Int,
    val count: Int,
    val status: Int,
) {
    val anomalous: Boolean get() = (itemId != 0 && count == 0) ||
        (itemId == 0 && (count != 0 || status != 0)) || count > 8

    fun visibleText(catalog: CatalogNames? = null): String = buildString {
        append(role.displayLabel())
        val name = if (itemId == 0) null else catalog?.names?.get(itemId)
        if (name != null) append("\n$name\nBase catalog")
        append("\nItem #$itemId\nRecorded count: $count\nProfile status: $status")
        if (anomalous) append("\nUnusual recorded values; shown unchanged.")
    }
}

/** Reject malformed extents before assigning any roles; never pad or truncate. */
internal fun profileInventory(slots: List<ProfileInventorySlot>): List<ProfileInventoryPresentation>? {
    if (slots.size != 19) return null
    return Collections.unmodifiableList(InventorySlotRole.entries.mapNotNull { role ->
        val slot = slots[role.slotIndex]
        if (slot.itemId == 0 && slot.count == 0 && slot.status == 0) null
        else ProfileInventoryPresentation(role, slot.itemId, slot.count, slot.status)
    })
}

fun PersonnelPresentation.profileInventoryText(catalog: CatalogNames? = null): String {
    if (currentSquad) return "Current tactical inventory is shown in this character's roster detail."
    val note = "Recorded in this save's character profile. These entries may differ from the character's tactical inventory."
    val rows = profileInventory
    val contents = when {
        rows == null -> "Profile inventory unavailable."
        rows.isEmpty() -> "No items recorded in profile inventory."
        else -> rows.joinToString("\n\n") { it.visibleText(catalog) }
    }
    return "$note\n\n$contents"
}
