package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.catalog.*
import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

class ProfileInventoryPresentationTest {
    private val empty = ProfileInventorySlot(0, 0, 0)
    private fun person(slots: List<ProfileInventorySlot>, current: Boolean = false): PersonnelPresentation {
        val roster = if (current) listOf(MercRosterEntry(7, "Live", null, MercStats(1,1,1,1,1,1,1,1,1,1,1))) else emptyList()
        return assertNotNull(PersonnelPresentationMapper.map(listOf(personnelProfile(7).copy(inventory = slots)), roster)).single()
    }

    @Test fun allNineteenIndicesUseCanonicalRolesAndSharedLabels() {
        val rows = assertNotNull(person(List(19) { ProfileInventorySlot(400 + it, 1, it) }).profileInventory)
        val labels = listOf("Helmet", "Vest", "Legs", "Head 1", "Head 2", "Main hand", "Off hand") +
            (1..4).map { "Big pocket $it" } + (1..8).map { "Small pocket $it" }
        assertEquals(19, rows.size)
        rows.forEachIndexed { i, row ->
            assertEquals(i, row.role.slotIndex)
            assertEquals(InventorySlotRole.entries[i], row.role)
            assertEquals(labels[i], row.role.displayLabel())
            assertEquals(400 + i, row.itemId)
        }
        assertFailsWith<UnsupportedOperationException> { (rows as MutableList).clear() }
    }

    @Test fun canonicalEmptyIsOmittedAndInvalidExtentsAreUnavailable() {
        assertEquals(emptyList(), person(List(19) { empty }).profileInventory)
        assertTrue(person(List(19) { empty }).profileInventoryText().endsWith("No items recorded in profile inventory."))
        for (size in listOf(0, 18, 20)) {
            val p = person(List(size) { empty })
            assertNull(p.profileInventory)
            assertTrue(p.profileInventoryText().endsWith("Profile inventory unavailable."))
        }
        val source = MutableList(19) { empty }.also { it[5] = ProfileInventorySlot(65535, 8, 255) }
        val p = person(source)
        source[5] = empty
        assertEquals(65535, assertNotNull(p.profileInventory).single().itemId)
        assertTrue(p.profileInventoryText().contains("Item #65535"))
    }

    @Test fun anomaliesAndUnsignedValuesRemainLiteral() {
        for (item in listOf(0, 7)) for (count in listOf(0, 8, 9, 255)) for (status in listOf(0, 100, 101, 128, 255)) {
            val p = person(List(19) { if (it == 0) ProfileInventorySlot(item, count, status) else empty })
            if (item == 0 && count == 0 && status == 0) continue
            val row = assertNotNull(p.profileInventory).single()
            assertEquals(item, row.itemId); assertEquals(count, row.count); assertEquals(status, row.status)
            assertEquals(item == 0 || count == 0 || count > 8, row.anomalous)
            val text = row.visibleText()
            assertTrue(text.contains("Item #$item\nRecorded count: $count\nProfile status: $status"))
            assertEquals(row.anomalous, text.contains("Unusual recorded values"))
            assertFalse(text.contains("Condition")); assertFalse(text.contains("%"))
        }
    }

    @Test fun catalogReplacementDecoratesRetainedFactsAndNeverItemZero() {
        val p = person(List(19) { when (it) { 0 -> ProfileInventorySlot(7, 1, 128); 1 -> ProfileInventorySlot(0, 1, 255); else -> empty } })
        val session = CatalogSession()
        for (name in listOf("First", "Second\u202eName")) {
            val catalog = BaseItemCatalog::class.java.getDeclaredConstructor(Map::class.java)
                .apply { isAccessible = true }.newInstance(mapOf(7 to name, 0 to "Forbidden"))
            session.complete(session.begin(), CatalogResult.Loaded(catalog))
            val text = p.profileInventoryText(session.presentation.active)
            assertTrue(text.contains(PresentationTextSanitizer.sanitize(name) + "\nBase catalog\nItem #7"))
            assertTrue(text.contains("Item #0")); assertFalse(text.contains("Forbidden"))
            if (name.startsWith("Second")) assertFalse(text.contains("First"))
        }
        session.clear()
        assertFalse(p.profileInventoryText(session.presentation.active).contains("Base catalog"))
    }

    @Test fun currentSquadSuppressesEvenDivergentProfileRowsAndKeepsLiveInventory() {
        val (bytes, inspector) = com.phishtopia.ja2fieldkit.core.SaveEditTransactionTest().androidFixture()
        val inspection = assertIs<SaveInspectionV01Result.Success>(inspector.inspectV01(bytes))
        val live = inventorySuccess(inspection, records = mapOf(7 to InventorySlotRole.MAIN_HAND to (777 to 3)))
        val source = com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance("synthetic",
            com.phishtopia.ja2fieldkit.android.importing.SourceProvenance.DOCUMENT_PICKER, null, bytes.size.toLong(), null, "00".repeat(32))
        val state = assertIs<InspectionScreenState.Success>(InspectionPresentationMapper.map(source, inspection, live))
        val rows = List(19) { ProfileInventorySlot(65535, 255, 128) }
        assertEquals(201, inspection.profiles.single { it.profileId == 7 }.inventory[7].itemId)
        val current = state.personnel.single { it.profileId == 7 }
        assertTrue(current.currentSquad)
        assertNull(person(rows, true).profileInventory)
        assertNull(current.profileInventory)
        assertEquals("Current tactical inventory is shown in this character's roster detail.", current.profileInventoryText())
        assertFalse(current.copy(profileInventory = person(rows).profileInventory).profileInventoryText().contains("65535"))
        val hand = state.roster.single { it.profileIndex == 7 }.inventory.single { it.role == InventorySlotRole.MAIN_HAND }
        assertEquals(InventoryContentsPresentation.Occupied(777, 3), hand.contents)
        assertTrue(person(rows).profileInventoryText().contains("Recorded in this save's character profile."))
        val surface = ProfileInventoryPresentation::class.java.declaredFields.joinToString { it.genericType.typeName }
        for (forbidden in listOf("[B", "MercProfile", "ProfileInventorySlot", "Parser")) assertFalse(forbidden in surface)
    }
}
