package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.report.CompatibilityReportFactory
import com.phishtopia.ja2fieldkit.android.report.CompatibilityReportInputs
import com.phishtopia.ja2fieldkit.android.report.CompatibilityReportPreview
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.LiveMercState
import com.phishtopia.ja2fieldkit.core.model.InventorySlotRole
import com.phishtopia.ja2fieldkit.core.model.LiveMercStateInspectionResult
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionDiagnostic
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailure
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
        val selectedProfileIndex: Int? = roster.firstOrNull()?.profileIndex,
    ) : InspectionScreenState {
        val selectedMerc: MercPresentation?
            get() = roster.firstOrNull { it.profileIndex == selectedProfileIndex } ?: roster.firstOrNull()
    }

    data class Failure(
        val source: ImportedSaveProvenance?,
        val title: String,
        val failureKind: String,
        val diagnostic: String,
        val format: FormatPresentation?,
        val compatibilityReportInputs: CompatibilityReportInputs?,
        val compatibilityReportPreview: CompatibilityReportPreview? = null,
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
    val profileIndex: Int,
    val name: String,
    val nickname: String?,
    val profileStats: List<StatPresentation>,
    val liveStats: List<StatPresentation>,
    val inventory: List<InventorySlotPresentation>,
)

data class StatPresentation(val label: String, val value: String)

data class InventorySlotPresentation(
    val label: String,
    val contents: InventoryContentsPresentation,
)

sealed interface InventoryContentsPresentation {
    data object Empty : InventoryContentsPresentation
    data class Occupied(val itemId: Int, val objectCount: Int) : InventoryContentsPresentation
}

object InspectionPresentationMapper {
    fun map(
        source: ImportedSaveProvenance,
        result: SaveInspectionV01Result,
        liveResult: LiveMercStateInspectionResult,
    ): InspectionScreenState = when (result) {
        is SaveInspectionV01Result.Success -> mapSuccess(source, result, liveResult)

        is SaveInspectionV01Result.Failure -> mapFailure(source, result.format, result.failure)
    }

    private fun mapSuccess(
        source: ImportedSaveProvenance,
        result: SaveInspectionV01Result.Success,
        liveResult: LiveMercStateInspectionResult,
    ): InspectionScreenState {
        if (liveResult is LiveMercStateInspectionResult.Failure) {
            return mapFailure(source, liveResult.format, liveResult.failure)
        }
        liveResult as LiveMercStateInspectionResult.Success
        val rosterIds = result.roster.map { it.profileIndex }
        val liveIds = liveResult.mercs.map { it.profileIndex }
        val canonicalRoles = InventorySlotRole.entries
        val coherent = result.format == liveResult.format &&
            rosterIds.size == rosterIds.toSet().size &&
            liveIds.size == liveIds.toSet().size &&
            rosterIds.toSet() == liveIds.toSet() &&
            liveResult.mercs.all { entry ->
                entry.slots.map { it.role } == canonicalRoles
            }
        if (!coherent) return coherenceFailure(source, result.format)

        val liveByProfile = liveResult.mercs.associateBy { it.profileIndex }
        return InspectionScreenState.Success(
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
            roster = result.roster.map { merc ->
                mapMerc(merc, liveByProfile.getValue(merc.profileIndex))
            },
        )
    }

    private fun coherenceFailure(
        source: ImportedSaveProvenance,
        format: SaveInspectionFormat,
    ): InspectionScreenState = mapFailure(
        source,
        format,
        SaveInspectionFailure(
            SaveInspectionFailureKind.INCONSISTENT_INPUT,
            SaveInspectionDiagnostic.CONTENT_INCONSISTENT,
        ),
    )

    private fun mapFailure(
        source: ImportedSaveProvenance,
        inspectionFormat: SaveInspectionFormat,
        failure: SaveInspectionFailure,
    ): InspectionScreenState.Failure {
        val format = mapFormat(inspectionFormat)
        val failureKind = failure.kind.name
        val diagnostic = failure.diagnostic.name
        return InspectionScreenState.Failure(
                source = source,
                title = when (failure.kind) {
                    SaveInspectionFailureKind.UNKNOWN_FORMAT -> "Unknown save format"
                    SaveInspectionFailureKind.UNSUPPORTED_VARIANT -> "Unsupported save variant"
                    SaveInspectionFailureKind.TRUNCATED_INPUT -> "Truncated save"
                    SaveInspectionFailureKind.INCONSISTENT_INPUT -> "Inconsistent save"
                    SaveInspectionFailureKind.CORRUPT_INPUT -> "Corrupt save"
                },
                failureKind = failureKind,
                diagnostic = diagnostic,
                format = format,
                compatibilityReportInputs = CompatibilityReportFactory.captureInputs(
                    source = source,
                    format = format,
                    failureKind = failureKind,
                    failureDiagnostic = diagnostic,
                ),
            )
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
        compatibilityReportInputs = null,
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

    private fun mapMerc(
        merc: MercRosterEntry,
        live: LiveMercState,
    ): MercPresentation = MercPresentation(
        profileIndex = merc.profileIndex,
        name = PresentationTextSanitizer.sanitize(merc.name)
            .takeUnless(String::isBlank)
            ?: "Unknown merc",
        nickname = merc.nickname?.let(PresentationTextSanitizer::sanitize),
        profileStats = listOf(
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
        liveStats = listOf(
            StatPresentation("Life", live.stats.life.toString()),
            StatPresentation("Max life", live.stats.lifeMax.toString()),
            StatPresentation("Agility", live.stats.agility.toString()),
            StatPresentation("Dexterity", live.stats.dexterity.toString()),
            StatPresentation("Strength", live.stats.strength.toString()),
            StatPresentation("Experience", live.stats.experienceLevel.toString()),
            StatPresentation("Marksmanship", live.stats.marksmanship.toString()),
            StatPresentation("Mechanical", live.stats.mechanical.toString()),
            StatPresentation("Explosives", live.stats.explosives.toString()),
            StatPresentation("Medical", live.stats.medical.toString()),
        ),
        inventory = live.slots.map { slot ->
            InventorySlotPresentation(
                label = slot.role.displayLabel(),
                contents = if (slot.itemId == 0 && slot.objectCount == 0) {
                    InventoryContentsPresentation.Empty
                } else {
                    InventoryContentsPresentation.Occupied(
                        itemId = slot.itemId,
                        objectCount = slot.objectCount,
                    )
                },
            )
        },
    )

    private fun Int?.display(): String = this?.toString() ?: "Unknown"

    private fun InventorySlotRole.displayLabel(): String = when (this) {
        InventorySlotRole.HELMET -> "Helmet"
        InventorySlotRole.VEST -> "Vest"
        InventorySlotRole.LEGS -> "Legs"
        InventorySlotRole.HEAD_1 -> "Head 1"
        InventorySlotRole.HEAD_2 -> "Head 2"
        InventorySlotRole.MAIN_HAND -> "Main hand"
        InventorySlotRole.OFF_HAND -> "Off hand"
        InventorySlotRole.BIG_POCKET_1 -> "Big pocket 1"
        InventorySlotRole.BIG_POCKET_2 -> "Big pocket 2"
        InventorySlotRole.BIG_POCKET_3 -> "Big pocket 3"
        InventorySlotRole.BIG_POCKET_4 -> "Big pocket 4"
        InventorySlotRole.SMALL_POCKET_1 -> "Small pocket 1"
        InventorySlotRole.SMALL_POCKET_2 -> "Small pocket 2"
        InventorySlotRole.SMALL_POCKET_3 -> "Small pocket 3"
        InventorySlotRole.SMALL_POCKET_4 -> "Small pocket 4"
        InventorySlotRole.SMALL_POCKET_5 -> "Small pocket 5"
        InventorySlotRole.SMALL_POCKET_6 -> "Small pocket 6"
        InventorySlotRole.SMALL_POCKET_7 -> "Small pocket 7"
        InventorySlotRole.SMALL_POCKET_8 -> "Small pocket 8"
    }
}

/** Selection consumes only retained safe presentation facts, never save bytes or an inspector. */
fun InspectionScreenState.withSelectedMerc(profileIndex: Int): InspectionScreenState {
    if (this !is InspectionScreenState.Success) return this
    val nextId = roster.firstOrNull { it.profileIndex == profileIndex }?.profileIndex
        ?: selectedMerc?.profileIndex
    return if (nextId == selectedProfileIndex) this else copy(selectedProfileIndex = nextId)
}

fun InspectionScreenState.withCompatibilityReportPreview(appVersion: String): InspectionScreenState {
    if (this !is InspectionScreenState.Failure) return this
    if (compatibilityReportPreview != null) return this
    val inputs = compatibilityReportInputs ?: return this
    val text = CompatibilityReportFactory.create(appVersion, inputs).toJson()
    return copy(compatibilityReportPreview = CompatibilityReportPreview(text))
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
