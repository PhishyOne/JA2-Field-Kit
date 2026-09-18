package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.EncryptedProfileFrame
import com.phishtopia.ja2fieldkit.core.format.HeaderProbe
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileDecoder
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileFramer
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxRosterDecoder
import com.phishtopia.ja2fieldkit.core.format.RosterMembershipException
import com.phishtopia.ja2fieldkit.core.format.RosterMembershipFailure
import com.phishtopia.ja2fieldkit.core.format.RotationDigestOracle
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveDetectionReason
import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetection
import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetector
import com.phishtopia.ja2fieldkit.core.format.SaveHeader
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderParser
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderProbe
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.CampaignSector
import com.phishtopia.ja2fieldkit.core.model.CampaignSummaryV01
import com.phishtopia.ja2fieldkit.core.model.MercProfile
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionDiagnostic
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailure
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFailureKind
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionFormat
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result

/**
 * Public read-only entry point for the parser core.
 *
 * Automatic detection and every layout-specific interpretation are fail-closed.
 * Lower-level format primitives remain available for focused research and tests.
 */
class Ja2SaveInspector private constructor(
    private val detector: (ByteArray) -> SaveFormatDetection,
) {
    constructor() : this(SaveFormatDetector::detect)

    internal companion object {
        /** Test-only access to the production detector with admitted synthetic digest evidence. */
        @JvmSynthetic
        fun withRotationDigestOracleForTesting(
            rotationDigestOracle: RotationDigestOracle,
        ): Ja2SaveInspector = Ja2SaveInspector { bytes ->
            SaveFormatDetector.detect(bytes, rotationDigestOracle)
        }

        @JvmSynthetic
        fun withDetectorForTesting(
            detector: (ByteArray) -> SaveFormatDetection,
        ): Ja2SaveInspector = Ja2SaveInspector(detector)
    }

    fun probe(bytes: ByteArray): HeaderProbe = SaveHeaderProbe.probe(bytes)

    /** Detect compatibility without retaining or mutating caller-owned bytes. */
    fun detect(bytes: ByteArray): SaveFormatDetection = detector(bytes)

    /**
     * Run the complete read-only v0.1 flow against one private snapshot of [bytes].
     *
     * Detection runs once. Only the exact admitted layout is interpreted, and all input-driven
     * failures are returned as bounded presentation codes rather than parser exceptions.
     */
    fun inspectV01(bytes: ByteArray): SaveInspectionV01Result {
        val snapshot = bytes.copyOf()
        val detection = try {
            detector(snapshot)
        } catch (_: RuntimeException) {
            return SaveInspectionV01Result.Failure(
                format = unknownInspectionFormat(),
                failure = SaveInspectionFailure(
                    kind = SaveInspectionFailureKind.CORRUPT_INPUT,
                    diagnostic = SaveInspectionDiagnostic.DETECTION_FAILED,
                ),
            )
        }
        val format = detection.toInspectionFormat()
        if (
            detection.compatibility != SaveCompatibility.SUPPORTED ||
            detection.layout != SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX
        ) {
            return SaveInspectionV01Result.Failure(
                format = format,
                failure = detection.toInspectionFailure(),
            )
        }

        return try {
            val header = SaveHeaderParser.parseBuild041202(snapshot)
            val roster = NormalNonLinuxRosterDecoder.decodeBuild041202(snapshot)
            SaveInspectionV01Result.Success.create(
                format = format,
                campaign = CampaignSummaryV01(
                    day = header.day,
                    hour = header.hour,
                    minute = header.minute,
                    sector = CampaignSector(
                        x = header.sector.x,
                        y = header.sector.y,
                        z = header.sector.z,
                    ),
                    playerMercCount = header.playerMercCount,
                    balance = header.balance,
                ),
                roster = roster,
            )
        } catch (error: RosterMembershipException) {
            SaveInspectionV01Result.Failure(
                format = format,
                failure = error.toInspectionFailure(),
            )
        } catch (_: RuntimeException) {
            SaveInspectionV01Result.Failure(
                format = format,
                failure = SaveInspectionFailure(
                    kind = SaveInspectionFailureKind.CORRUPT_INPUT,
                    diagnostic = SaveInspectionDiagnostic.CONTENT_CORRUPT,
                ),
            )
        }
    }

    /** Parse the evidenced header layout without identifying a save family. */
    fun parseBuild041202Header(bytes: ByteArray): SaveHeader = admitted(bytes) { snapshot ->
        SaveHeaderParser.parseBuild041202(snapshot)
    }

    /** Frame the encrypted profiles in the evidenced normal non-Linux layout without decrypting. */
    fun frameBuild041202NormalNonLinuxProfiles(bytes: ByteArray): EncryptedProfileFrame =
        admitted(bytes) { snapshot ->
            NormalNonLinuxProfileFramer.frameBuild041202(snapshot)
        }

    /**
     * Frame, recover, decrypt, and parse all 170 profiles in the evidenced normal non-Linux layout.
     * The returned table is not a hired-player roster.
     */
    fun parseBuild041202NormalNonLinuxProfiles(bytes: ByteArray): List<MercProfile> =
        admitted(bytes) { snapshot ->
            NormalNonLinuxProfileDecoder.decodeBuild041202(snapshot)
        }

    /**
     * Return current non-vehicle player-team mercs for the evidenced normal non-Linux layout.
     * Membership is joined to profiles by the validated serialized profile index.
     */
    fun parseBuild041202NormalNonLinuxRoster(bytes: ByteArray): List<MercRosterEntry> =
        admitted(bytes) { snapshot ->
            NormalNonLinuxRosterDecoder.decodeBuild041202(snapshot)
        }

    private fun <T> admitted(bytes: ByteArray, parse: (ByteArray) -> T): T {
        val snapshot = bytes.copyOf()
        val detection = detector(snapshot)
        if (
            detection.compatibility != SaveCompatibility.SUPPORTED ||
            detection.layout != SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX
        ) {
            throw SaveInterpretationAdmissionException(
                compatibility = detection.compatibility,
                layout = detection.layout,
                reason = detection.reason,
            )
        }
        return parse(snapshot)
    }

    private fun SaveFormatDetection.toInspectionFormat(): SaveInspectionFormat =
        SaveInspectionFormat(
            layout = layout,
            compatibility = compatibility,
            family = family,
            saveVersion = saveVersion,
            buildLabel = buildLabel,
        )

    private fun SaveFormatDetection.toInspectionFailure(): SaveInspectionFailure =
        SaveInspectionFailure(
            kind = when (compatibility) {
                SaveCompatibility.UNKNOWN -> SaveInspectionFailureKind.UNKNOWN_FORMAT
                SaveCompatibility.CANDIDATE,
                SaveCompatibility.UNSUPPORTED_VARIANT ->
                    SaveInspectionFailureKind.UNSUPPORTED_VARIANT
                SaveCompatibility.TRUNCATED -> SaveInspectionFailureKind.TRUNCATED_INPUT
                SaveCompatibility.INCONSISTENT -> SaveInspectionFailureKind.INCONSISTENT_INPUT
                SaveCompatibility.SUPPORTED -> SaveInspectionFailureKind.INCONSISTENT_INPUT
            },
            diagnostic = when (reason) {
                SaveDetectionReason.TOO_SHORT_FOR_HEADER_IDENTITY ->
                    SaveInspectionDiagnostic.IDENTITY_INCOMPLETE
                SaveDetectionReason.UNKNOWN_HEADER_IDENTITY ->
                    SaveInspectionDiagnostic.IDENTITY_UNKNOWN
                SaveDetectionReason.CONTRADICTORY_HEADER_IDENTITY ->
                    SaveInspectionDiagnostic.IDENTITY_INCONSISTENT
                SaveDetectionReason.TRUNCATED_RECOGNIZED_HEADER ->
                    SaveInspectionDiagnostic.HEADER_TRUNCATED
                SaveDetectionReason.TRUNCATED_NORMAL_NON_LINUX_LAYOUT ->
                    SaveInspectionDiagnostic.LAYOUT_TRUNCATED
                SaveDetectionReason.UNSUPPORTED_SELECTOR_HEADER,
                SaveDetectionReason.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL,
                SaveDetectionReason.ROTATION_DIGEST_ORACLE_MISSING ->
                    SaveInspectionDiagnostic.VARIANT_UNSUPPORTED
                SaveDetectionReason.PROFILE_ROTATION_AMBIGUOUS ->
                    SaveInspectionDiagnostic.CONTENT_AMBIGUOUS
                SaveDetectionReason.ROTATION_DIGEST_MISMATCH ->
                    SaveInspectionDiagnostic.HEADER_BODY_MISMATCH
                SaveDetectionReason.PROFILE_ROTATION_CONSTRAINT_CONFLICT,
                SaveDetectionReason.PROFILE_ROTATION_NO_CANDIDATE ->
                    SaveInspectionDiagnostic.CONTENT_INCONSISTENT
                SaveDetectionReason.SELECTOR_BODY_ROTATION_MATCH ->
                    SaveInspectionDiagnostic.CONTENT_INCONSISTENT
            },
        )

    private fun RosterMembershipException.toInspectionFailure(): SaveInspectionFailure =
        if (
            reason == RosterMembershipFailure.TRUNCATED_ACTIVE_MARKER ||
            reason == RosterMembershipFailure.TRUNCATED_SOLDIER_RECORD ||
            reason == RosterMembershipFailure.TRUNCATED_PATH_COUNT ||
            reason == RosterMembershipFailure.TRUNCATED_PATH_DATA ||
            reason == RosterMembershipFailure.TRUNCATED_KEYRING_MARKER ||
            reason == RosterMembershipFailure.TRUNCATED_KEYRING_DATA
        ) {
            SaveInspectionFailure(
                kind = SaveInspectionFailureKind.TRUNCATED_INPUT,
                diagnostic = SaveInspectionDiagnostic.LAYOUT_TRUNCATED,
            )
        } else {
            SaveInspectionFailure(
                kind = SaveInspectionFailureKind.CORRUPT_INPUT,
                diagnostic = SaveInspectionDiagnostic.CONTENT_CORRUPT,
            )
        }

    private fun unknownInspectionFormat(): SaveInspectionFormat = SaveInspectionFormat(
        layout = SaveLayout.UNKNOWN,
        compatibility = SaveCompatibility.UNKNOWN,
        family = com.phishtopia.ja2fieldkit.core.format.SaveFamily.UNKNOWN,
        saveVersion = null,
        buildLabel = null,
    )
}

/** Sanitized detector result for a rejected public interpretation request. */
class SaveInterpretationAdmissionException(
    val compatibility: SaveCompatibility,
    val layout: SaveLayout,
    val reason: SaveDetectionReason,
) : IllegalArgumentException(
    "Save interpretation was not admitted " +
        "(compatibility=$compatibility, layout=$layout, reason=$reason)",
)
