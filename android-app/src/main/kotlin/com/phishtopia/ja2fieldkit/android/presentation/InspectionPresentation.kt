package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.report.CompatibilityReportFactory
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailureKind
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import java.util.Locale

sealed interface InspectionScreenState {
    data object Initial : InspectionScreenState

    data class Loading(val sourceName: String?) : InspectionScreenState

    data class Success(
        val source: ImportedSaveProvenance,
        val format: FormatPresentation,
        val campaign: CampaignPresentation,
        val roster: List<MercPresentation>,
    ) : InspectionScreenState

    data class Failure(
        val source: ImportedSaveProvenance?,
        val title: String,
        val failureKind: String,
        val diagnostic: String,
        val format: FormatPresentation?,
        val compatibilityReportText: String?,
        val reportPreviewVisible: Boolean = false,
    ) : InspectionScreenState
}

data class FormatPresentation(
    val version: String,
    val build: String,
    val layout: String,
    val compatibility: String,
    val producer: String,
)

data class CampaignPresentation(
    val dayAndTime: String,
    val sector: String,
    val rosterCount: String,
    val balance: String,
)

data class MercPresentation(
    val name: String,
    val nickname: String?,
    val stats: List<StatPresentation>,
)

data class StatPresentation(val label: String, val value: String)

object InspectionPresentationMapper {
    fun map(
        source: ImportedSaveProvenance,
        result: SaveInspectionV01Result,
        appVersion: String = "0.1.0",
    ): InspectionScreenState = when (result) {
        is SaveInspectionV01Result.Success -> InspectionScreenState.Success(
            source = source,
            format = mapFormat(result.format),
            campaign = CampaignPresentation(
                dayAndTime = "Day ${result.campaign.day}, " +
                    String.format(Locale.ROOT, "%02d:%02d", result.campaign.hour, result.campaign.minute),
                sector = "${result.campaign.sector.x}, ${result.campaign.sector.y}, " +
                    "level ${result.campaign.sector.z}",
                rosterCount = "${result.roster.size} shown " +
                    "(${result.campaign.playerMercCount} recorded)",
                balance = result.campaign.balance.toString(),
            ),
            roster = result.roster.map(::mapMerc),
        )

        is SaveInspectionV01Result.Failure -> {
            val format = mapFormat(result.format)
            val failureKind = result.failure.kind.name
            val diagnostic = result.failure.diagnostic.name
            InspectionScreenState.Failure(
                source = source,
                title = when (result.failure.kind) {
                    SaveInspectionFailureKind.UNKNOWN_FORMAT -> "Unknown save format"
                    SaveInspectionFailureKind.UNSUPPORTED_VARIANT -> "Unsupported save variant"
                    SaveInspectionFailureKind.TRUNCATED_INPUT -> "Truncated save"
                    SaveInspectionFailureKind.INCONSISTENT_INPUT -> "Inconsistent save"
                    SaveInspectionFailureKind.CORRUPT_INPUT -> "Corrupt save"
                },
                failureKind = failureKind,
                diagnostic = diagnostic,
                format = format,
                compatibilityReportText = CompatibilityReportFactory.create(
                    appVersion = appVersion,
                    source = source,
                    format = format,
                    failureKind = failureKind,
                    failureDiagnostic = diagnostic,
                ).toJson(),
            )
        }
    }

    fun sourceFailure(
        kind: SourceFailureKind,
    ): InspectionScreenState.Failure = InspectionScreenState.Failure(
        source = null,
        title = when (kind) {
            SourceFailureKind.NOT_CONTENT_URI -> "Unsupported source"
            SourceFailureKind.MULTIPLE_ITEMS -> "Share one save at a time"
            SourceFailureKind.SIZE_LIMIT -> "Save is too large"
            SourceFailureKind.UNAVAILABLE -> "Save could not be opened"
            SourceFailureKind.READ_FAILED -> "Save could not be read"
        },
        failureKind = "SOURCE_${kind.name}",
        diagnostic = when (kind) {
            SourceFailureKind.SIZE_LIMIT -> "MAXIMUM_16_MIB"
            else -> "CONTENT_URI_${kind.name}"
        },
        format = null,
        compatibilityReportText = null,
    )

    private fun mapFormat(format: SaveInspectionFormat): FormatPresentation =
        FormatPresentation(
            version = format.saveVersion?.toString() ?: "Unknown",
            build = format.buildLabel
                ?.let(PresentationTextSanitizer::sanitize)
                ?.takeUnless(String::isBlank)
                ?: "Unknown",
            layout = when (format.layout) {
                SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX ->
                    "Normal non-Linux v103"
                SaveLayout.UNKNOWN -> "Unknown"
            },
            compatibility = when (format.compatibility) {
                SaveCompatibility.SUPPORTED -> "Supported"
                SaveCompatibility.CANDIDATE -> "Candidate"
                SaveCompatibility.TRUNCATED -> "Truncated"
                SaveCompatibility.UNSUPPORTED_VARIANT -> "Unsupported variant"
                SaveCompatibility.INCONSISTENT -> "Inconsistent"
                SaveCompatibility.UNKNOWN -> "Unknown"
            },
            producer = when (format.family) {
                SaveFamily.JA2_REBORN -> "JA2 Reborn"
                SaveFamily.STRACCIATELLA -> "JA2 Stracciatella"
                SaveFamily.CLASSIC -> "Classic JA2"
                SaveFamily.UNKNOWN -> "Not established"
            },
        )

    private fun mapMerc(merc: MercRosterEntry): MercPresentation = MercPresentation(
        name = PresentationTextSanitizer.sanitize(merc.name)
            .takeUnless(String::isBlank)
            ?: "Unknown merc",
        nickname = merc.nickname?.let(PresentationTextSanitizer::sanitize),
        stats = listOf(
            StatPresentation("Health", merc.stats.health.display()),
            StatPresentation("Agility", merc.stats.agility.display()),
            StatPresentation("Dexterity", merc.stats.dexterity.display()),
            StatPresentation("Strength", merc.stats.strength.display()),
            StatPresentation("Leadership", merc.stats.leadership.display()),
            StatPresentation("Wisdom", merc.stats.wisdom.display()),
            StatPresentation("Experience", merc.stats.experienceLevel.display()),
            StatPresentation("Marksmanship", merc.stats.marksmanship.display()),
            StatPresentation("Mechanical", merc.stats.mechanical.display()),
            StatPresentation("Explosives", merc.stats.explosives.display()),
            StatPresentation("Medical", merc.stats.medical.display()),
        ),
    )

    private fun Int?.display(): String = this?.toString() ?: "Unknown"
}

fun InspectionScreenState.withCompatibilityReportPreview(): InspectionScreenState =
    if (this is InspectionScreenState.Failure && compatibilityReportText != null) {
        copy(reportPreviewVisible = true)
    } else {
        this
    }

/** Removes text controls at the boundary where save data becomes retained screen state. */
private object PresentationTextSanitizer {
    fun sanitize(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (isUnsafe(codePoint)) {
                append(' ')
            } else {
                appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isUnsafe(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        -> true
        else -> false
    }
}

enum class SourceFailureKind {
    NOT_CONTENT_URI,
    MULTIPLE_ITEMS,
    SIZE_LIMIT,
    UNAVAILABLE,
    READ_FAILED,
}
