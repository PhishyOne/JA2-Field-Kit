package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.android.importing.SourceMetadata
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.CampaignSector
import com.phishtopia.ja2fieldkit.core.model.CampaignSummaryV01
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.MercStats
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionDiagnostic
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailure
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailureKind
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class InspectionPresentationMapperTest {
    private val source = SourceMetadata("slot01.sav", 2_563_321, SourceProvenance.DOCUMENT_PICKER)

    @Test
    fun mapsSuccessToCampaignRosterAndEveryCoreStat() {
        val result = successResult()

        val state = assertIs<InspectionScreenState.Success>(
            InspectionPresentationMapper.map(source, result),
        )

        assertEquals("slot01.sav", state.source.displayName)
        assertEquals("103", state.format.version)
        assertEquals("04.12.02", state.format.build)
        assertEquals("Day 12, 07:05", state.campaign.dayAndTime)
        assertEquals("9, 4, level 0", state.campaign.sector)
        assertEquals("1 shown (1 recorded)", state.campaign.rosterCount)
        assertEquals("Ira", state.roster.single().name)
        assertEquals(
            listOf(
                "Health", "Agility", "Dexterity", "Strength", "Leadership", "Wisdom",
                "Experience", "Marksmanship", "Mechanical", "Explosives", "Medical",
            ),
            state.roster.single().stats.map { it.label },
        )
        assertEquals((80..90).map(Int::toString), state.roster.single().stats.map { it.value })
    }

    @Test
    fun unsupportedTruncatedAndCorruptFailuresRemainDistinct() {
        val cases = listOf(
            SaveInspectionFailureKind.UNSUPPORTED_VARIANT to SaveInspectionDiagnostic.VARIANT_UNSUPPORTED,
            SaveInspectionFailureKind.TRUNCATED_INPUT to SaveInspectionDiagnostic.LAYOUT_TRUNCATED,
            SaveInspectionFailureKind.CORRUPT_INPUT to SaveInspectionDiagnostic.CONTENT_CORRUPT,
        ).map { (kind, diagnostic) ->
            assertIs<InspectionScreenState.Failure>(
                InspectionPresentationMapper.map(source, failure(kind, diagnostic)),
            )
        }

        assertEquals(3, cases.map { it.title }.toSet().size)
        assertEquals(3, cases.map { it.failureKind }.toSet().size)
        assertEquals(3, cases.map { it.diagnostic }.toSet().size)
        assertNotEquals(cases[0], cases[1])
    }

    @Test
    fun sourceSizeFailureDisclosesOnlyBoundedAppCode() {
        val state = InspectionPresentationMapper.sourceFailure(source, SourceFailureKind.SIZE_LIMIT)

        assertEquals("SOURCE_SIZE_LIMIT", state.failureKind)
        assertEquals("MAXIMUM_16_MIB", state.diagnostic)
        assertEquals(null, state.format)
    }

    private fun failure(
        kind: SaveInspectionFailureKind,
        diagnostic: SaveInspectionDiagnostic,
    ) = SaveInspectionV01Result.Failure(
        format = format(SaveCompatibility.UNSUPPORTED_VARIANT),
        failure = SaveInspectionFailure(kind, diagnostic),
    )

    private fun successResult(): SaveInspectionV01Result.Success {
        val constructor = SaveInspectionV01Result.Success::class.java.declaredConstructors
            .single { it.parameterCount == 3 }
            .apply { isAccessible = true }
        return constructor.newInstance(
            format(SaveCompatibility.SUPPORTED),
            CampaignSummaryV01(12, 7, 5, CampaignSector(9, 4, 0), 1, 45_000),
            listOf(
                MercRosterEntry(
                    profileIndex = 1,
                    name = "Ira",
                    nickname = "Ira",
                    stats = MercStats(80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90),
                ),
            ),
        ) as SaveInspectionV01Result.Success
    }

    private fun format(compatibility: SaveCompatibility) = SaveInspectionFormat(
        layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
        compatibility = compatibility,
        family = SaveFamily.UNKNOWN,
        saveVersion = 103,
        buildLabel = "04.12.02",
    )
}
