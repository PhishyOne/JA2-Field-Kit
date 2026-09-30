package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.SaveEditTransactionTest
import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

class PersonnelPresentationTest {
    @Test fun namedFilteringSanitizingAndSquadIdentityDoNotInferMercStatus() {
        val profiles = listOf(personnelProfile(0, " \u202e\n", "\t"), personnelProfile(1, "", "Nick"),
            personnelProfile(2, "Citizen", ""), personnelProfile(3, "Citizen", "C"))
        val result = assertNotNull(PersonnelPresentationMapper.map(profiles, listOf(roster(3, "wrong name"))))
        assertEquals(listOf(1, 2, 3), result.map { it.profileId })
        assertEquals(listOf("Nick", "Citizen", "Citizen (C)"), result.map { it.name })
        assertEquals(listOf(false, false, true), result.map { it.currentSquad })
        assertTrue(result.last().listLabel.endsWith("Current squad"))
        assertFalse(result.first().listLabel.contains("Merc"))
        assertFailsWith<UnsupportedOperationException> { (result as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result[0].record as MutableList).clear() }
    }

    @Test fun invalidAndDuplicateIdentitiesFailClosedBeforeFiltering() {
        val p = personnelProfile(7)
        for (profiles in listOf(listOf(p, p), listOf(p.copy(profileId = -1)), listOf(p.copy(profileId = 170)))) {
            assertNull(PersonnelPresentationMapper.map(profiles, emptyList()))
        }
        for (roster in listOf(listOf(roster(7), roster(7)), listOf(roster(170)), listOf(roster(-1)), listOf(roster(8)))) {
            assertNull(PersonnelPresentationMapper.map(listOf(p), roster))
        }
    }

    @Test fun dossierShowsProfileValuesIndependentTraitSlotsAndDerivedAccuracyOnlyWithDenominator() {
        val p = personnelProfile(1).copy(skillTrait1 = SkillTraitId(4), skillTrait2 = SkillTraitId(4),
            personalityTrait = PersonalityTraitId(-1), attitude = AttitudeId(9),
            career = ProfileCareerRecord(1, 2, 3, 1, 4, 5, 65535, 0xffff_ffffL))
        val result = assertNotNull(PersonnelPresentationMapper.map(listOf(p), emptyList())).single()
        assertEquals(listOf("Night operations", "Night operations"), result.traits.map { it.value })
        assertFalse(result.toString().contains("Expert", ignoreCase = true))
        assertEquals(listOf("Unknown (-1)", "Coward"), result.personality.map { it.value })
        assertEquals(listOf("1", "2", "3", "1", "4", "5", "65535", "4294967295", "33.3%"), result.record.map { it.value })
        assertEquals("Shooting accuracy (derived)", result.record.last().label)
        assertEquals("70 / 80", result.attributes.first().value)
        assertEquals(11, result.attributes.size)
        val zero = assertNotNull(PersonnelPresentationMapper.map(listOf(p.copy(career = ProfileCareerRecord(shotsHit = 65535))), emptyList())).single()
        assertEquals(8, zero.record.size)
    }

    @Test fun relationshipsResolveOnlyWithinSameTableAndKeepSentinelsUnknownAndUnnamedDistinct() {
        val p = personnelProfile(0).copy(relationships = ProfileRelationships(
            ProfileRelationshipId(-1), ProfileRelationshipId(-2), ProfileRelationshipId(1),
            ProfileRelationshipId(2), ProfileRelationshipId(127), ProfileRelationshipId(0)))
        val result = assertNotNull(PersonnelPresentationMapper.map(listOf(p, personnelProfile(1, "A\nB"),
            personnelProfile(2, "")), emptyList())).first()
        assertEquals(listOf("None", "Unknown (-2)", "A B · Profile #1", "Profile #2", "Unknown (127)", "Person 0 · Profile #0"),
            result.relationships.map { it.value })
    }

    @Test fun inspectionToPersonnelToDossierAndBackUsesOnlyRetainedDisplayFacts() {
        val (bytes, inspector) = SaveEditTransactionTest().androidFixture()
        val source = com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance("synthetic",
            com.phishtopia.ja2fieldkit.android.importing.SourceProvenance.DOCUMENT_PICKER, null, bytes.size.toLong(), null, "00".repeat(32))
        val initial = assertIs<InspectionScreenState.Success>(InspectionPresentationMapper.map(source,
            inspector.inspectV01(bytes), inspector.inspectLiveMercState(bytes)))
        bytes.fill(0)
        assertTrue(initial.personnel.isNotEmpty())
        val database = assertIs<InspectionScreenState.Success>(initial.openPersonnel())
        val id = database.personnel.first().profileId
        val dossier = assertIs<InspectionScreenState.Success>(database.openPersonnel(id))
        assertEquals(id, dossier.dossierProfileId)
        assertSame(initial.personnel, dossier.personnel)
        assertSame(initial.roster, dossier.roster)
        assertSame(dossier, dossier.openPersonnel(170))
        assertEquals(database, dossier.closePersonnel())
        assertEquals(initial, database.closePersonnel())
        assertSame(InspectionScreenState.Initial, InspectionScreenState.Initial.openPersonnel())
        val surface = PersonnelPresentation::class.java.declaredFields.joinToString { "${it.name}:${it.genericType.typeName}" }
        for (forbidden in listOf("[B", "MercProfile", "Parser", "offset", "rotation", "Snapshot")) assertFalse(forbidden in surface)
    }

    private fun roster(id: Int, name: String = "Same") = MercRosterEntry(id, name, null,
        MercStats(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
}
