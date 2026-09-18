package com.phishtopia.ja2fieldkit.core.model

import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveFamily
import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetection
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import java.util.Collections

data class CampaignSummary(
    val day: Int?,
    val clockMinutes: Int?,
    val sector: String?,
    val playerMercCount: Int?,
    val balance: Long?,
)

data class MercStats(
    val health: Int?,
    val agility: Int?,
    val dexterity: Int?,
    val strength: Int?,
    val leadership: Int?,
    val wisdom: Int?,
    val experienceLevel: Int?,
    val marksmanship: Int?,
    val mechanical: Int?,
    val explosives: Int?,
    val medical: Int?,
)

data class MercRosterEntry(
    val profileIndex: Int,
    val name: String,
    val nickname: String?,
    val stats: MercStats,
)

data class SaveInspection(
    val format: SaveFormatDetection,
    val campaign: CampaignSummary?,
    val roster: List<MercRosterEntry>,
)

/** Presentation-safe format facts for the read-only v0.1 inspection facade. */
data class SaveInspectionFormat(
    val layout: SaveLayout,
    val compatibility: SaveCompatibility,
    val family: SaveFamily,
    val saveVersion: Int?,
    val buildLabel: String?,
)

data class CampaignSector(
    val x: Int,
    val y: Int,
    val z: Int,
)

/** Header-backed campaign facts; no tactical state is inferred. */
data class CampaignSummaryV01(
    val day: Long,
    val hour: Int,
    val minute: Int,
    val sector: CampaignSector,
    val playerMercCount: Int,
    val balance: Int,
)

/** Coarse failure classes intended for presentation and exhaustive handling. */
enum class SaveInspectionFailureKind {
    UNKNOWN_FORMAT,
    UNSUPPORTED_VARIANT,
    TRUNCATED_INPUT,
    INCONSISTENT_INPUT,
    CORRUPT_INPUT,
}

/** Bounded diagnostics which disclose no payload, offsets, keys, or encryption metadata. */
enum class SaveInspectionDiagnostic {
    IDENTITY_INCOMPLETE,
    IDENTITY_UNKNOWN,
    IDENTITY_INCONSISTENT,
    HEADER_TRUNCATED,
    LAYOUT_TRUNCATED,
    VARIANT_UNSUPPORTED,
    CONTENT_AMBIGUOUS,
    HEADER_BODY_MISMATCH,
    CONTENT_INCONSISTENT,
    CONTENT_CORRUPT,
    DETECTION_FAILED,
}

data class SaveInspectionFailure(
    val kind: SaveInspectionFailureKind,
    val diagnostic: SaveInspectionDiagnostic,
)

/**
 * Complete result of one read-only v0.1 inspection attempt.
 *
 * Implementations snapshot the input and never retain it in this result. Success lists are
 * immutable snapshots. Failure values contain only stable, sanitized codes.
 */
sealed interface SaveInspectionV01Result {
    val format: SaveInspectionFormat

    class Success private constructor(
        override val format: SaveInspectionFormat,
        val campaign: CampaignSummaryV01,
        roster: Collection<MercRosterEntry>,
    ) : SaveInspectionV01Result {
        val roster: List<MercRosterEntry> =
            Collections.unmodifiableList(ArrayList(roster))

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Success &&
                format == other.format &&
                campaign == other.campaign &&
                roster == other.roster

        override fun hashCode(): Int =
            31 * (31 * format.hashCode() + campaign.hashCode()) + roster.hashCode()

        override fun toString(): String =
            "Success(format=$format, campaign=$campaign, roster=$roster)"

        internal companion object {
            fun create(
                format: SaveInspectionFormat,
                campaign: CampaignSummaryV01,
                roster: Collection<MercRosterEntry>,
            ): Success = Success(
                format = format,
                campaign = campaign,
                roster = roster,
            )
        }
    }

    data class Failure(
        override val format: SaveInspectionFormat,
        val failure: SaveInspectionFailure,
    ) : SaveInspectionV01Result
}
