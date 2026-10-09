package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.SaveEditTransactionTest
import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import kotlin.test.*

class PersonnelFilterTest {
    private val people = assertNotNull(PersonnelPresentationMapper.map(
        listOf(personnelProfile(112, "Alice", "FOX"), personnelProfile(12, "Bob", "Wolf"),
            personnelProfile(40), personnelProfile(51), personnelProfile(57), personnelProfile(160), personnelProfile(164)),
        emptyList(),
    )).map { if (it.profileId == 12) it.copy(currentSquad = true) else it }

    @Test fun searchUsesSanitizedDisplayNamesNicknameAndExactDecimalId() {
        fun ids(query: String) = PersonnelFilter(query).results(people).map { it.profileId }
        assertEquals(listOf(112), ids("LiC"))
        assertEquals(listOf(112), ids("fox"))
        assertEquals(listOf(112), ids("alice (fox)"))
        assertEquals(listOf(12), ids("  12  "))
        assertTrue(ids("012").isEmpty())
        assertTrue(ids("+12").isEmpty())
        assertEquals(people, PersonnelFilter("  ").results(people))
        assertEquals(people, PersonnelFilter().results(people))
        assertTrue(ids("absent").isEmpty())
    }

    @Test fun everyCategoryUsesPresentationFactAndIntersectsWithQueryAndSquad() {
        val ids = listOf(12, 40, 51, 57, 112, 160, 164)
        PersonnelCategoryFilter.entries.drop(1).zip(ids).forEach { (category, id) ->
            assertEquals(listOf(id), PersonnelFilter(category = category).results(people).map { it.profileId })
        }
        assertEquals(listOf(12), PersonnelFilter(currentSquadOnly = true).results(people).map { it.profileId })
        assertEquals(listOf(12), PersonnelFilter("WOLF", PersonnelCategoryFilter.AIM, true).results(people).map { it.profileId })
        assertTrue(PersonnelFilter("WOLF", PersonnelCategoryFilter.NPC, true).results(people).isEmpty())
        // Category comes from the retained presentation label, never a fresh ID classification.
        assertEquals(1, PersonnelFilter(category = PersonnelCategoryFilter.NPC)
            .results(listOf(people[1].copy(standardCategory = "NPC profile"))).size)
    }

    @Test fun queryIsBoundedByCodePointsAndUsesExistingSanitization() {
        val emoji = "\uD83D\uDE00"
        val bounded = PersonnelFilter.safeQuery(emoji.repeat(121))
        assertEquals(emoji.repeat(120), bounded)
        assertEquals(120, bounded.codePointCount(0, bounded.length))
        assertEquals("A     B", PersonnelFilter.safeQuery("A\n\t\u202e\u2028\u2029B"))
        assertFailsWith<IllegalArgumentException> { PersonnelFilter("x".repeat(121)) }
        assertFailsWith<IllegalArgumentException> { PersonnelFilter("\n") }
    }

    @Test fun resultOrderingAndFactsStayUnchangedAndListsAreImmutable() {
        val source = assertNotNull(PersonnelPresentationMapper.map(
            listOf(personnelProfile(112), personnelProfile(12)), emptyList()))
        val before = source.toList()
        val results = PersonnelFilter("Person").results(source)
        assertEquals(listOf(112, 12), results.map { it.profileId })
        assertSame(source[0], results[0])
        assertEquals(before, source)
        assertFailsWith<UnsupportedOperationException> { (source as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (results as MutableList).clear() }
    }

    @Test fun stateTransitionsRetainFiltersAndDossiersWhileNewImportDefaults() {
        val (bytes, inspector) = SaveEditTransactionTest().androidFixture()
        val source = ImportedSaveProvenance("synthetic", SourceProvenance.DOCUMENT_PICKER,
            null, bytes.size.toLong(), null, "00".repeat(32))
        fun imported() = assertIs<InspectionScreenState.Success>(InspectionPresentationMapper.map(source,
            inspector.inspectV01(bytes), inspector.inspectLiveMercState(bytes)))
        val initial = imported()
        val filtered = assertIs<InspectionScreenState.Success>(initial.openPersonnel()
            .withPersonnelQuery("x".repeat(121)).withPersonnelCategory(PersonnelCategoryFilter.AIM)
            .withPersonnelCurrentSquadOnly(true))
        assertEquals(120, filtered.personnelFilter.query.length)
        assertSame(filtered, filtered.withPersonnelQuery("x".repeat(122)))
        assertSame(filtered, filtered.withPersonnelCategory(PersonnelCategoryFilter.AIM))
        assertSame(filtered, filtered.withPersonnelCurrentSquadOnly(true))
        val dossier = assertIs<InspectionScreenState.Success>(filtered.openPersonnel(initial.personnel.first().profileId))
        assertSame(initial.personnel, dossier.personnel)
        assertEquals(filtered, dossier.closePersonnel())
        assertEquals(filtered.copy(personnelVisible = false), filtered.closePersonnel())
        assertEquals(PersonnelFilter(), imported().personnelFilter)
        val cleared = assertIs<InspectionScreenState.Success>(filtered.clearPersonnelFilters())
        assertEquals(PersonnelFilter(), cleared.personnelFilter)
        assertSame(cleared, cleared.clearPersonnelFilters())
        assertSame(initial.personnel, cleared.personnel)
        bytes.fill(0)
        assertEquals(initial.personnel, cleared.personnelFilter.results(cleared.personnel))
    }

    @Test fun nonSuccessStatesAreUnaffected() {
        listOf(InspectionScreenState.Initial, InspectionScreenState.Loading(null),
            InspectionPresentationMapper.sourceFailure(SourceFailureKind.READ_FAILED)).forEach { state ->
            assertSame(state, state.withPersonnelQuery("name"))
            assertSame(state, state.withPersonnelCategory(PersonnelCategoryFilter.NPC))
            assertSame(state, state.withPersonnelCurrentSquadOnly(true))
            assertSame(state, state.clearPersonnelFilters())
        }
    }
}
