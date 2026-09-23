package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InspectionPresentationMapperTest {
    private val source = ImportedSaveProvenance(
        displayName = "slot01.sav",
        provenance = SourceProvenance.DOCUMENT_PICKER,
        declaredSizeBytes = 2_563_321,
        actualSizeBytes = 2_563_321,
        lastModifiedEpochMillis = null,
        sha256Hex = "00".repeat(32),
    )

    @Test
    fun mapsSuccessToCampaignRosterAndEveryCoreStat() {
        val result = successResult()

        val state = assertIs<InspectionScreenState.Success>(
            map(result),
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
    fun mappedPresentationStateHasNoSaveByteContainer() {
        val state = assertIs<InspectionScreenState.Success>(
            map(successResult()),
        )

        assertFalse(state.javaClass.declaredFields.any { it.type == ByteArray::class.java })
        assertFalse(state.source.javaClass.declaredFields.any { it.type == ByteArray::class.java })
        assertFalse(state.toString().contains("exactBytes"))
    }

    @Test
    fun sanitizesControlAndBidiCharactersBeforeRetainingMercPresentation() {
        val rawName = "I\n\r\t\u202Era\u2066\u2069"
        val rawNickname = "F\u202Eox\n\u2066\u2069"

        val state = assertIs<InspectionScreenState.Success>(
            map(successResult(rawName, rawNickname)),
        )

        assertEquals("I    ra  ", state.roster.single().name)
        assertEquals("F ox   ", state.roster.single().nickname)
        assertEquals(false, state.toString().contains(rawName))
        assertEquals(false, state.toString().contains(rawNickname))
        assertEquals(false, state.toString().any(::isUnsafePresentationCharacter))
    }

    @Test
    fun preservesOrdinaryAccentedAndNonLatinMercText() {
        val state = assertIs<InspectionScreenState.Success>(
            map(successResult("Zoë 李", "Éclair・猫")),
        )

        assertEquals("Zoë 李", state.roster.single().name)
        assertEquals("Éclair・猫", state.roster.single().nickname)
    }

    @Test
    fun substitutesFallbackForBlankOrAllUnsafeMercNames() {
        val unsafe = assertIs<InspectionScreenState.Success>(
            map(successResult("\n\u202E\u2066\u2069", "\t")),
        )
        val blank = assertIs<InspectionScreenState.Success>(
            map(successResult("   ", null)),
        )

        assertEquals("Unknown merc", unsafe.roster.single().name)
        assertEquals(" ", unsafe.roster.single().nickname)
        assertEquals("Unknown merc", blank.roster.single().name)
        assertEquals(null, blank.roster.single().nickname)
    }

    @Test
    fun sanitizesSaveDerivedBuildLabelWithDeterministicFallback() {
        val sanitized = assertIs<InspectionScreenState.Success>(
            map(
                successResult(buildLabel = "04.12\n\u202E\u2066\u2069.02"),
            ),
        )
        val fallback = assertIs<InspectionScreenState.Success>(
            map(successResult(buildLabel = "\r\t\u202E")),
        )

        assertEquals("04.12    .02", sanitized.format.build)
        assertEquals("Unknown", fallback.format.build)
        assertEquals(false, sanitized.toString().any(::isUnsafePresentationCharacter))
    }

    @Test
    fun unsupportedTruncatedAndCorruptFailuresRemainDistinct() {
        val cases = listOf(
            SaveInspectionFailureKind.UNSUPPORTED_VARIANT to SaveInspectionDiagnostic.VARIANT_UNSUPPORTED,
            SaveInspectionFailureKind.TRUNCATED_INPUT to SaveInspectionDiagnostic.LAYOUT_TRUNCATED,
            SaveInspectionFailureKind.CORRUPT_INPUT to SaveInspectionDiagnostic.CONTENT_CORRUPT,
        ).map { (kind, diagnostic) ->
            assertIs<InspectionScreenState.Failure>(
                map(failure(kind, diagnostic)),
            )
        }

        assertEquals(3, cases.map { it.title }.toSet().size)
        assertEquals(3, cases.map { it.failureKind }.toSet().size)
        assertEquals(3, cases.map { it.diagnostic }.toSet().size)
        assertNotEquals(cases[0], cases[1])
        cases.forEach {
            assertNotNull(it.compatibilityReportInputs)
            assertNull(it.compatibilityReportPreview)
        }
    }

    @Test
    fun unknownUnsupportedTruncatedAndCorruptReportsUseTheirExactBoundedCodes() {
        val cases = listOf(
            SaveInspectionFailureKind.UNKNOWN_FORMAT to SaveInspectionDiagnostic.IDENTITY_UNKNOWN,
            SaveInspectionFailureKind.UNSUPPORTED_VARIANT to SaveInspectionDiagnostic.VARIANT_UNSUPPORTED,
            SaveInspectionFailureKind.TRUNCATED_INPUT to SaveInspectionDiagnostic.LAYOUT_TRUNCATED,
            SaveInspectionFailureKind.CORRUPT_INPUT to SaveInspectionDiagnostic.CONTENT_CORRUPT,
        )

        cases.forEach { (kind, diagnostic) ->
            val state = assertIs<InspectionScreenState.Failure>(
                map(failure(kind, diagnostic)),
            )
            assertNull(state.compatibilityReportPreview)
            val preview = assertIs<InspectionScreenState.Failure>(
                state.withCompatibilityReportPreview("0.1.0"),
            )
            val report = assertNotNull(preview.compatibilityReportPreview).text
            assertTrue(report.contains("\"kind\": \"${kind.name.lowercase()}\""))
            assertTrue(report.contains("\"diagnostic\": \"${diagnostic.name.lowercase()}\""))
        }
    }

    @Test
    fun reportActionIsUnavailableWithoutCompleteSafeFailureContext() {
        SourceFailureKind.entries.forEach { kind ->
            val state = InspectionPresentationMapper.sourceFailure(kind)
            assertNull(state.compatibilityReportInputs)
            assertNull(state.compatibilityReportPreview)
            assertSame(state, state.withCompatibilityReportPreview("0.1.0"))
        }
        val success = map(successResult())
        assertSame(success, success.withCompatibilityReportPreview("0.1.0"))
    }

    @Test
    fun reportPreviewBecomesVisibleOnlyAfterExplicitTransition() {
        val initial = assertIs<InspectionScreenState.Failure>(
            map(
                failure(SaveInspectionFailureKind.TRUNCATED_INPUT, SaveInspectionDiagnostic.LAYOUT_TRUNCATED),
            ),
        )

        assertNotNull(initial.compatibilityReportInputs)
        assertNull(initial.compatibilityReportPreview)
        assertFalse(initial.toString().contains("schema_version"))

        val preview = assertIs<InspectionScreenState.Failure>(
            initial.withCompatibilityReportPreview("0.1.0"),
        )
        val generatedPreview = assertNotNull(preview.compatibilityReportPreview)
        val text = generatedPreview.text
        assertTrue(text.contains("\"schema_version\": \"ja2-field-kit.compatibility-report/0.1\""))
        assertEquals(text, generatedPreview.clipboardText)
        assertEquals(text, generatedPreview.share.text)
        assertSame(preview, preview.withCompatibilityReportPreview("different-version"))
    }

    @Test
    fun failureReportStateContainsNoRawBytesMercOrCampaignPresentation() {
        val state = assertIs<InspectionScreenState.Failure>(
            map(
                failure(SaveInspectionFailureKind.CORRUPT_INPUT, SaveInspectionDiagnostic.CONTENT_CORRUPT),
                source.copy(displayName = "Ira-secret-campaign.sav"),
            ),
        )
        val inputs = assertNotNull(state.compatibilityReportInputs)

        assertFalse(state.javaClass.declaredFields.any { it.type == ByteArray::class.java })
        assertFalse(inputs.javaClass.declaredFields.any { it.type == ByteArray::class.java })
        assertFalse(inputs.toString().contains("Ira"))
        assertFalse(inputs.toString().contains("campaign"))
        assertFalse(inputs.toString().contains("slot01.sav"))
        assertFalse(inputs.toString().contains("9999999"))
        assertFalse(inputs.toString().contains("1725000000000"))
    }

    @Test
    fun sourceSizeFailureDisclosesOnlyBoundedAppCode() {
        val state = InspectionPresentationMapper.sourceFailure(SourceFailureKind.SIZE_LIMIT)

        assertEquals("SOURCE_SIZE_LIMIT", state.failureKind)
        assertEquals("MAXIMUM_16_MIB", state.diagnostic)
        assertEquals(null, state.format)
        assertEquals(null, state.compatibilityReportInputs)
        assertEquals(null, state.compatibilityReportPreview)
    }

    private fun failure(
        kind: SaveInspectionFailureKind,
        diagnostic: SaveInspectionDiagnostic,
    ) = SaveInspectionV01Result.Failure(
        format = format(SaveCompatibility.UNSUPPORTED_VARIANT),
        failure = SaveInspectionFailure(kind, diagnostic),
    )

    private fun map(
        result: SaveInspectionV01Result,
        mappedSource: ImportedSaveProvenance = source,
    ): InspectionScreenState = InspectionPresentationMapper.map(
        mappedSource,
        result,
        inventorySuccess(result),
    )

    private fun successResult(
        name: String = "Ira",
        nickname: String? = "Ira",
        buildLabel: String? = "04.12.02",
    ): SaveInspectionV01Result.Success {
        val constructor = SaveInspectionV01Result.Success::class.java.declaredConstructors
            .single { it.parameterCount == 3 }
            .apply { isAccessible = true }
        return constructor.newInstance(
            format(SaveCompatibility.SUPPORTED, buildLabel),
            CampaignSummaryV01(12, 7, 5, CampaignSector(9, 4, 0), 1, 45_000),
            listOf(
                MercRosterEntry(
                    profileIndex = 1,
                    name = name,
                    nickname = nickname,
                    stats = MercStats(80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90),
                ),
            ),
        ) as SaveInspectionV01Result.Success
    }

    private fun format(
        compatibility: SaveCompatibility,
        buildLabel: String? = "04.12.02",
    ) = SaveInspectionFormat(
        layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
        compatibility = compatibility,
        family = SaveFamily.UNKNOWN,
        saveVersion = 103,
        buildLabel = buildLabel,
    )

    private fun isUnsafePresentationCharacter(character: Char): Boolean = when (
        Character.getType(character)
    ) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        -> true
        else -> false
    }
}
