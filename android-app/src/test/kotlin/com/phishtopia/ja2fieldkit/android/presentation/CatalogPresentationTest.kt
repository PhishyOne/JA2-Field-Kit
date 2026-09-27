package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.catalog.*
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import kotlin.test.*

class CatalogPresentationTest {
    // Synthetic-only construction, matching the other cross-module presentation fixtures.
    private fun catalog(names: Map<Int, String>): BaseItemCatalog =
        BaseItemCatalog::class.java.getDeclaredConstructor(Map::class.java)
            .apply { isAccessible = true }.newInstance(names)

    private fun loaded(name: String = "Test Wrench") = CatalogResult.Loaded(catalog(mapOf(7 to name)))

    @Test fun staleRequestsCannotWinAndFailedReplacementKeepsKnownGoodExplicitly() {
        val session = CatalogSession()
        val first = session.begin()
        val second = session.begin()
        assertFalse(session.complete(first, loaded("Stale")))
        assertTrue(session.presentation.loading)
        assertTrue(session.complete(second, loaded()))
        assertFalse(session.complete(first, CatalogResult.Rejected(CatalogFailure.READ_FAILED)))
        val good = session.presentation.active
        val replacement = session.begin()
        assertSame(good, session.presentation.active)
        assertTrue(session.presentation.status.contains("Loading"))
        session.complete(replacement, CatalogResult.Rejected(CatalogFailure.WRONG_IDENTITY))
        assertSame(good, session.presentation.active)
        assertTrue(session.presentation.status.contains("Previous catalog remains active"))
        assertTrue(session.presentation.status.contains(GogEnglishItemCatalog.LABEL))
        session.clear()
        assertNull(session.presentation.active)
        assertFalse(session.complete(replacement, loaded()))
    }
    @Test fun numericFallbackAndSanitizedBaseDecorationPreserveIdentity() {
        val slot = InventorySlotPresentation(InventorySlotRole.MAIN_HAND, "Main hand", InventoryContentsPresentation.Occupied(7, 3))
        val names = CatalogNames(loaded("Test\u202eWrench").catalog)
        assertEquals("Main hand\nTest Wrench\nBase catalog · Item #7 · Count 3", slot.visibleText(names))
        assertEquals("Main hand\nItem #7 · Count 3", slot.visibleText())
        assertEquals(slot.visibleText(), slot.visibleText(CatalogNames(catalog(mapOf(8 to "Unknown")))) )
        assertEquals(slot.visibleText(), slot.visibleText(CatalogNames(loaded("\u202e").catalog)))
        assertEquals("Main hand\nEmpty", slot.copy(contents = InventoryContentsPresentation.Empty).visibleText(names))
    }
    @Test fun catalogChangesPreserveRosterRolesAndLaterSelectionUsesSameRoster() {
        val inspection = inspectionSuccess(
            com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat(
                com.phishtopia.ja2fieldkit.core.format.SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
                com.phishtopia.ja2fieldkit.core.format.SaveCompatibility.SUPPORTED,
                com.phishtopia.ja2fieldkit.core.format.SaveFamily.UNKNOWN, 103, "04.12.02"), listOf(7, 9))
        val original = assertIs<InspectionScreenState.Success>(InspectionPresentationMapper.map(
            com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance("test.sav",
                com.phishtopia.ja2fieldkit.android.importing.SourceProvenance.DOCUMENT_PICKER,
                null, 100, null, "00".repeat(32)), inspection, inventorySuccess(inspection)))
        val session = CatalogSession()
        session.complete(session.begin(), loaded())
        val decorated = original.copy(catalog = session.presentation)
        fun assertSafe(value: Any?) {
            when (value) {
                null, is String, is Number, is Boolean, is Enum<*> -> return
                is List<*> -> value.forEach(::assertSafe)
                is Map<*, *> -> value.forEach { (k, v) -> assertSafe(k); assertSafe(v) }
                else -> {
                    assertFalse(value is ByteArray)
                    assertFalse(value.javaClass.name.contains("Uri"))
                    assertFalse(value.javaClass.name.contains("Slf", true))
                    assertFalse(value.javaClass.name.contains("Itemdesc", true))
                    value.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .forEach { it.isAccessible = true; assertSafe(it.get(value)) }
                }
            }
        }
        assertSafe(decorated)
        assertSame(original.roster, decorated.roster)
        decorated.roster.forEach { assertEquals(groupInventory(it.inventory), groupInventory(original.roster.first { old -> old.profileIndex == it.profileIndex }.inventory)) }
        val selected = assertIs<InspectionScreenState.Success>(decorated.withSelectedMerc(decorated.roster.last().profileIndex))
        assertSame(decorated.roster, selected.roster)
        assertSame(decorated.catalog, selected.catalog)
        assertEquals(decorated, selected.copy(selectedProfileIndex = decorated.selectedProfileIndex))
    }
}
