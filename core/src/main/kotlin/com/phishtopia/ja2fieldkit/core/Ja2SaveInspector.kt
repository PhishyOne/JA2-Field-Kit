package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.EncryptedProfileFrame
import com.phishtopia.ja2fieldkit.core.format.HeaderProbe
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileDecoder
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileFramer
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxRosterDecoder
import com.phishtopia.ja2fieldkit.core.format.RotationDigestOracle
import com.phishtopia.ja2fieldkit.core.format.SaveCompatibility
import com.phishtopia.ja2fieldkit.core.format.SaveDetectionReason
import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetection
import com.phishtopia.ja2fieldkit.core.format.SaveFormatDetector
import com.phishtopia.ja2fieldkit.core.format.SaveHeader
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderParser
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderProbe
import com.phishtopia.ja2fieldkit.core.format.SaveLayout
import com.phishtopia.ja2fieldkit.core.model.MercProfile
import com.phishtopia.ja2fieldkit.core.model.MercRosterEntry

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
    }

    fun probe(bytes: ByteArray): HeaderProbe = SaveHeaderProbe.probe(bytes)

    /** Detect compatibility without retaining or mutating caller-owned bytes. */
    fun detect(bytes: ByteArray): SaveFormatDetection = detector(bytes)

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
