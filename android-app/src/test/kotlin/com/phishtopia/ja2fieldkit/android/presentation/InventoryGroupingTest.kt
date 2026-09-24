package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InventoryGroupingTest {
    private val slots = InventorySlotRole.entries.map {
        InventorySlotPresentation(it, it.name, InventoryContentsPresentation.Empty)
    }
    private val expectedRoles = listOf(
        listOf(HELMET, HEAD_1, HEAD_2, VEST, LEGS),
        listOf(MAIN_HAND, OFF_HAND),
        listOf(BIG_POCKET_1, BIG_POCKET_2, BIG_POCKET_3, BIG_POCKET_4),
        listOf(SMALL_POCKET_1, SMALL_POCKET_2, SMALL_POCKET_3, SMALL_POCKET_4,
            SMALL_POCKET_5, SMALL_POCKET_6, SMALL_POCKET_7, SMALL_POCKET_8),
    )

    @Test
    fun exactSectionsContainEveryCanonicalRoleOnceInDisplayOrder() {
        val groups = assertNotNull(groupInventory(slots))
        assertEquals(listOf("Head / armor", "Hands", "Big pockets", "Small pockets"),
            groups.map { it.title })
        assertEquals(expectedRoles, groups.map { group -> group.slots.map { it.role } })
        assertEquals(listOf(5, 2, 4, 8), groups.map { it.slots.size })
        val roles = groups.flatMap { it.slots }.map { it.role }
        assertEquals(19, roles.size)
        assertEquals(InventorySlotRole.entries.toSet(), roles.toSet())
    }

    @Test
    fun shuffledSlotsAndMisleadingIdenticalLabelsDoNotChangeGrouping() {
        val changed = slots.reversed().map {
            it.copy(label = "Small pocket 8", contents = InventoryContentsPresentation.Occupied(65535, 255))
        }
        val groups = assertNotNull(groupInventory(changed))
        assertEquals(expectedRoles, groups.map { group -> group.slots.map { it.role } })
        groups.flatMap { it.slots }.forEach { slot ->
            assertEquals(changed.single { it.role == slot.role }, slot)
        }
    }

    @Test
    fun everyMissingOrDuplicateRoleRejectsTheWholeInventory() {
        assertNull(groupInventory(emptyList()))
        slots.forEach { slot ->
            assertNull(groupInventory(slots - slot))
            assertNull(groupInventory(slots + slot))
            val other = slots.first { it.role != slot.role }
            assertNull(groupInventory(slots.map { if (it.role == slot.role) other else it }))
        }
    }

    @Test
    fun visibleCellTextIncludesLabelAndOnlyExactContents() {
        val slot = slots.first().copy(label = "Helmet")
        assertEquals("Helmet\nEmpty", slot.visibleText())
        listOf(321 to 4, 65535 to 255, 0 to 1, 1 to 0).forEach { (id, count) ->
            assertEquals("Helmet\nItem #$id · Count $count",
                slot.copy(contents = InventoryContentsPresentation.Occupied(id, count)).visibleText())
        }
    }
}
