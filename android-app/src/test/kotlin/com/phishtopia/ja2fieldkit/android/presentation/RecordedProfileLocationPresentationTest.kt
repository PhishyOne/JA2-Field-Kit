package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

class RecordedProfileLocationPresentationTest {
    @Test fun allCategoriesOnlyExposeRecordedLocationForNonCurrentRpcNpc() {
        val profiles = (0..169).map { personnelProfile(it).copy(
            recordedProfileLocation = RecordedProfileLocation.Sector(9, 1, 2)) }
        val rows = assertNotNull(PersonnelPresentationMapper.map(profiles, emptyList()))
        for (row in rows) {
            if (row.profileId in 57..159) {
                assertEquals(StatPresentation("Recorded profile location", "A9-2"), row.location)
                assertEquals("Profile sector recorded in the save; may differ from the character’s saved soldier location.", row.locationNote)
            } else {
                assertNull(row.location); assertNull(row.locationNote)
            }
        }
    }
    @Test fun currentSquadAlwaysUsesLiveLocationIncludingEveryNonSectorState() {
        val live = listOf(LiveMercLocation.Sector(16,16,3), LiveMercLocation.InTransit,
            LiveMercLocation.Dead, LiveMercLocation.Prisoner, LiveMercLocation.InVehicle, LiveMercLocation.Unavailable)
        val expected = listOf("P16-3", "In transit", "Dead", "POW — location unknown", "In vehicle", "Unavailable")
        for (id in listOf(0,39,40,50,51,56,57,74,75,159,160,163,164,169)) {
            val p = personnelProfile(id).copy(recordedProfileLocation = RecordedProfileLocation.Sector(9,1,0))
            val roster = listOf(MercRosterEntry(id, "Name", null, MercStats(1,1,1,1,1,1,1,1,1,1,1)))
            for ((i, location) in live.withIndex()) {
                val row = assertNotNull(PersonnelPresentationMapper.map(listOf(p), roster, mapOf(id to location))).single()
                assertEquals(StatPresentation("Location", expected[i]), row.location)
                assertNull(row.locationNote)
            }
            assertEquals("Unavailable", assertNotNull(PersonnelPresentationMapper.map(listOf(p), roster)).single().location?.value)
        }
    }
    @Test fun recordedCornersAndDepthsUseCanonicalLabels() {
        for ((x, y, prefix) in listOf(Triple(1,1,"A1"), Triple(16,1,"A16"),
            Triple(1,16,"P1"), Triple(16,16,"P16"))) for (z in 0..3) {
            val row = assertNotNull(PersonnelPresentationMapper.map(listOf(personnelProfile(57).copy(
                recordedProfileLocation = RecordedProfileLocation.Sector(x,y,z))), emptyList())).single()
            assertEquals(prefix + if (z == 0) "" else "-$z", row.location?.value)
        }
    }
    @Test fun nonCurrentKeepsRecordedWordingEvenIfSoldierExistsElsewhere() {
        for ((location, label) in listOf(RecordedProfileLocation.Sector(9,1,0) to "A9",
            RecordedProfileLocation.Unavailable to "Unavailable")) {
            val row = assertNotNull(PersonnelPresentationMapper.map(listOf(personnelProfile(75).copy(
                recordedProfileLocation = location)), emptyList(), mapOf(75 to LiveMercLocation.Sector(16,16,3)))).single()
            assertEquals(StatPresentation("Recorded profile location", label), row.location)
            assertNotNull(row.locationNote)
        }
        val fields = PersonnelPresentation::class.java.declaredFields.joinToString { it.toString() }
        for (name in listOf("ubMisc", "ByteArray", "RecordedProfileLocation", "LiveMercLocation")) assertFalse(name in fields)
    }
}
