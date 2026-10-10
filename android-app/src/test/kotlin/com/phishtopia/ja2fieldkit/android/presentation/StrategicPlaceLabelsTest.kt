package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.LiveMercLocation
import com.phishtopia.ja2fieldkit.core.model.RecordedProfileLocation
import kotlin.test.*

class StrategicPlaceLabelsTest {
    // Independently specified acceptance table, never obtained from production metadata.
    private val approved = mapOf(
        "A2" to "Chitzena", "A9" to "Omerta", "A10" to "Omerta",
        "B2" to "Chitzena", "B13" to "Drassen Airport",
        "C5" to "San Mona", "C6" to "San Mona", "C13" to "Drassen",
        "D4" to "San Mona", "D5" to "San Mona", "D13" to "Drassen",
        "F8" to "Cambria Hospital", "F9" to "Cambria",
        "G1" to "Grumm", "G2" to "Grumm", "G8" to "Cambria", "G9" to "Cambria",
        "H1" to "Grumm", "H2" to "Grumm", "H3" to "Grumm", "H8" to "Cambria",
        "H13" to "Alma", "H14" to "Alma", "I6" to "Estoni", "I13" to "Alma", "I14" to "Alma",
        "L11" to "Balime", "L12" to "Balime", "N3" to "Meduna Airport", "N5" to "Meduna",
        "O3" to "Meduna", "O4" to "Meduna", "P3" to "Meduna",
    )

    @Test fun everySurfaceCoordinateMatchesThe33ApprovedLabels() {
        assertEquals(33, approved.size)
        for (y in 1..16) for (x in 1..16) {
            val code = "${'A' + y - 1}$x"
            val expected = approved[code]?.let { "$code — $it" } ?: code
            assertEquals(expected, LiveMercLocation.Sector(x, y, 0).displayLocation())
        }
    }

    @Test fun everyUndergroundCoordinateAtAllThreeDepthsStaysCoordinateOnly() {
        for (y in 1..16) for (x in 1..16) for (z in 1..3) {
            assertEquals("${'A' + y - 1}$x-$z", LiveMercLocation.Sector(x, y, z).displayLocation())
        }
    }

    @Test fun asymmetryOverridesAndSecretExclusionsAreExact() {
        for ((x, y, expected) in listOf(
            Triple(13, 2, "B13 — Drassen Airport"), Triple(2, 13, "M2"),
            Triple(8, 6, "F8 — Cambria Hospital"), Triple(6, 8, "H6"),
            Triple(3, 14, "N3 — Meduna Airport"), Triple(14, 3, "C14"),
            Triple(9, 1, "A9 — Omerta"), Triple(1, 1, "A1"),
        )) assertEquals(expected, decoratedSector(x, y, 0))
        for (code in listOf("D2", "D15", "I8", "J9", "K4", "N4")) {
            assertEquals(code, decoratedSector(code.drop(1).toInt(), code[0] - 'A' + 1, 0))
        }
    }

    @Test fun recordedRpcAndNpcUseSameDecorationAndKeepTheirCaveat() {
        for (id in listOf(57, 75)) for (y in 1..16) for (x in 1..16) for (z in 0..3) {
            val row = assertNotNull(PersonnelPresentationMapper.map(listOf(personnelProfile(id).copy(
                recordedProfileLocation = RecordedProfileLocation.Sector(x, y, z))), emptyList())).single()
            assertEquals(StatPresentation("Recorded profile location", decoratedSector(x, y, z)), row.location)
            assertEquals("Profile sector recorded in the save; may differ from the character’s saved soldier location.", row.locationNote)
        }
    }

    @Test fun noncanonicalRecordedCoordinatesCannotBecomeNamedSectors() {
        for ((x, y, z) in listOf(Triple(0, 1, 0), Triple(9, 0, 0), Triple(17, 1, 0),
            Triple(9, 17, 0), Triple(9, 1, -1), Triple(9, 1, 4))) {
            assertFailsWith<IllegalArgumentException> { RecordedProfileLocation.Sector(x, y, z) }
        }
        val row = assertNotNull(PersonnelPresentationMapper.map(listOf(personnelProfile(75).copy(
            recordedProfileLocation = RecordedProfileLocation.Unavailable)), emptyList())).single()
        assertEquals(StatPresentation("Recorded profile location", "Unavailable"), row.location)
    }
}
