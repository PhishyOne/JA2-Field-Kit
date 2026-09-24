package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import kotlin.test.*

class MercSelectionTest {
    private fun inspected(ids: List<Int> = listOf(9, 4)): InspectionScreenState.Success {
        val result = inspectionSuccess(
            SaveInspectionFormat(SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
                SaveCompatibility.SUPPORTED, SaveFamily.UNKNOWN, 103, "04.12.02"), ids,
        )
        return assertIs<InspectionScreenState.Success>(InspectionPresentationMapper.map(
            ImportedSaveProvenance("save.sav", SourceProvenance.DOCUMENT_PICKER,
                null, 100, null, "00".repeat(32)),
            result, inventorySuccess(result, profileOrder = ids.reversed()),
        ))
    }

    @Test
    fun initialSelectionUsesFirstExactProfileIdFromValidatedJoin() {
        val state = inspected()
        assertEquals(listOf(9, 4), state.roster.map { it.profileIndex })
        assertEquals(9, state.selectedProfileIndex)
        assertSame(state.roster.first(), state.selectedMerc)
    }

    @Test
    fun duplicateNamesAndReorderedRosterCannotChangeIdentityBinding() {
        val mapped = inspected()
        val state = mapped.copy(roster = mapped.roster.map {
            it.copy(name = "Same name", nickname = "Same nickname")
        })
        val selected = assertIs<InspectionScreenState.Success>(state.withSelectedMerc(4))
        assertEquals(4, selected.selectedProfileIndex)
        assertSame(state.roster[1], selected.selectedMerc)
        assertEquals("4", selected.selectedMerc!!.liveStats.first().value)
        assertSame(selected.selectedMerc, selected.copy(roster = selected.roster.reversed()).selectedMerc)
        assertSame(selected, selected.withSelectedMerc(4))
    }

    @Test
    fun unknownRequestsPreserveValidSelectionAndStaleStateFallsBackToFirst() {
        val selected = assertIs<InspectionScreenState.Success>(inspected().withSelectedMerc(4))
        assertSame(selected, selected.withSelectedMerc(999))
        val stale = selected.copy(selectedProfileIndex = 999)
        assertSame(stale.roster.first(), stale.selectedMerc)
        val repaired = assertIs<InspectionScreenState.Success>(stale.withSelectedMerc(-1))
        assertEquals(9, repaired.selectedProfileIndex)
        assertSame(repaired.roster.first(), repaired.selectedMerc)
    }

    @Test
    fun emptyRosterAndNonSuccessActionsAreSafe() {
        val empty = inspected(emptyList())
        assertNull(empty.selectedProfileIndex)
        assertNull(empty.selectedMerc)
        assertSame(empty, empty.withSelectedMerc(4))
        val stale = assertIs<InspectionScreenState.Success>(
            empty.copy(selectedProfileIndex = 4).withSelectedMerc(9))
        assertNull(stale.selectedProfileIndex)
        assertNull(stale.selectedMerc)
        listOf(InspectionScreenState.Initial, InspectionScreenState.Loading(null),
            InspectionPresentationMapper.sourceFailure(SourceFailureKind.UNAVAILABLE)).forEach {
            assertSame(it, it.withSelectedMerc(4))
        }
    }

    @Test
    fun newInspectionResetsSelectionEvenWhenProfileIdsOverlap() {
        val previous = assertIs<InspectionScreenState.Success>(inspected().withSelectedMerc(4))
        assertEquals(4, previous.selectedProfileIndex)
        assertEquals(9, inspected().selectedProfileIndex)
        assertEquals(7, inspected(listOf(7, 4)).selectedProfileIndex)
    }

    @Test
    fun selectionResolvesOneExistingDetailWithoutChangingStatsOrInventory() {
        val state = inspected()
        val selected = assertIs<InspectionScreenState.Success>(state.withSelectedMerc(4))
        assertSame(state.roster, selected.roster)
        val merc = assertNotNull(selected.selectedMerc)
        assertEquals(1, selected.roster.count { it === merc })
        assertSame(state.roster[1].liveStats, merc.liveStats)
        assertSame(state.roster[1].profileStats, merc.profileStats)
        assertSame(state.roster[1].inventory, merc.inventory)
        assertEquals(10, merc.liveStats.size)
        assertEquals(11, merc.profileStats.size)
        assertEquals(19, merc.inventory.size)
    }
}
