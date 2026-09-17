package com.phishtopia.ja2fieldkit.core.format

import java.util.Collections

/** Known family labels. Their presence here does not imply detector support. */
enum class SaveFamily {
    JA2_REBORN,
    STRACCIATELLA,
    CLASSIC,
    UNKNOWN,
}

/** Family confidence retained for source compatibility; partial matches never use PROBABLE. */
enum class DetectionConfidence {
    CONFIRMED,
    PROBABLE,
    UNKNOWN,
}

/** Compatibility of the supplied bytes with the current read-only core. */
enum class SaveDetectionState {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    CONTRADICTORY,
    UNKNOWN,
}

/** A bounded reason code; messages and input bytes are deliberately not retained. */
enum class SaveDetectionReason {
    MATCHED_REBORN_BUILD_041202_NORMAL_NON_LINUX,
    TOO_SHORT_FOR_HEADER_IDENTITY,
    TRUNCATED_RECOGNIZED_HEADER,
    UNKNOWN_HEADER_IDENTITY,
    CONTRADICTORY_HEADER_IDENTITY,
    UNSUPPORTED_SELECTOR_HEADER,
    TRUNCATED_NORMAL_NON_LINUX_LAYOUT,
    UNSUPPORTED_DYNAMIC_LAPTOP_TAIL,
    PROFILE_ROTATION_NOT_CONFIRMED,
}

/**
 * Safe structural facts collected before detection stops.
 *
 * No descriptions, payload bytes, plaintext, profile data, or rotation material are retained.
 */
data class SaveDetectionFacts(
    val fileSize: Int,
    val hasCompleteHeaderIdentity: Boolean,
    val hasCompleteNormalHeader: Boolean,
    val rawSaveVersion: Long? = null,
    val gameVersion: String? = null,
    val selectorHeaderCompatible: Boolean? = null,
    val eventCount: Long? = null,
    val profileStartOffset: Int? = null,
    val profileEndExclusive: Int? = null,
    val requiredEndExclusive: Long? = null,
    val framingStage: ProfileFramingStage? = null,
)

data class SaveFormatDetection(
    val family: SaveFamily,
    val saveVersion: Int? = null,
    /** Normalized product build label. Set only for the exact recognized identity. */
    val buildLabel: String? = null,
    val confidence: DetectionConfidence = DetectionConfidence.UNKNOWN,
    val evidence: List<String> = emptyList(),
    val state: SaveDetectionState = SaveDetectionState.UNKNOWN,
    val reason: SaveDetectionReason = SaveDetectionReason.UNKNOWN_HEADER_IDENTITY,
    val facts: SaveDetectionFacts = SaveDetectionFacts(
        fileSize = 0,
        hasCompleteHeaderIdentity = false,
        hasCompleteNormalHeader = false,
    ),
) {
    companion object {
        internal fun create(
            family: SaveFamily,
            state: SaveDetectionState,
            reason: SaveDetectionReason,
            saveVersion: Int?,
            buildLabel: String?,
            facts: SaveDetectionFacts,
            evidence: List<String>,
        ): SaveFormatDetection = SaveFormatDetection(
            family = family,
            state = state,
            reason = reason,
            saveVersion = saveVersion,
            buildLabel = buildLabel,
            confidence = if (family == SaveFamily.JA2_REBORN) {
                DetectionConfidence.CONFIRMED
            } else {
                DetectionConfidence.UNKNOWN
            },
            facts = facts,
            evidence = Collections.unmodifiableList(ArrayList(evidence)),
        )
    }
}

/** Evidence-based, fail-closed detector for the single currently supported path. */
object SaveFormatDetector {
    private const val NORMALIZED_BUILD_LABEL = "04.12.02"

    fun detect(bytes: ByteArray): SaveFormatDetection {
        val identity = SaveHeaderParser.probeIdentity(bytes)
        val baseFacts = SaveDetectionFacts(
            fileSize = bytes.size,
            hasCompleteHeaderIdentity = identity.hasCompleteIdentity,
            hasCompleteNormalHeader = bytes.size >= SaveLayoutFacts.NORMAL_HEADER_SIZE,
            rawSaveVersion = identity.saveVersion,
            gameVersion = identity.gameVersion?.takeIf { value ->
                value.all { character -> character.code in 0x20..0x7e }
            },
        )

        if (!identity.hasCompleteIdentity) {
            return result(
                state = SaveDetectionState.UNKNOWN,
                reason = SaveDetectionReason.TOO_SHORT_FOR_HEADER_IDENTITY,
                identity = identity,
                facts = baseFacts,
                evidence = listOf("The 20-byte version/build identity is incomplete."),
            )
        }

        val versionMatches = identity.saveVersion == SaveHeaderParser.SUPPORTED_SAVE_VERSION
        val buildMatches = identity.gameVersion == SaveHeaderParser.SUPPORTED_GAME_VERSION
        if (!versionMatches || !buildMatches) {
            val contradictory = versionMatches != buildMatches
            return result(
                state = if (contradictory) SaveDetectionState.CONTRADICTORY else SaveDetectionState.UNKNOWN,
                reason = if (contradictory) {
                    SaveDetectionReason.CONTRADICTORY_HEADER_IDENTITY
                } else {
                    SaveDetectionReason.UNKNOWN_HEADER_IDENTITY
                },
                identity = identity,
                facts = baseFacts,
                evidence = listOf(
                    "The saved-game version and build identity do not jointly match the supported path.",
                ),
            )
        }

        if (bytes.size < SaveLayoutFacts.NORMAL_HEADER_SIZE) {
            return result(
                state = SaveDetectionState.PARTIALLY_SUPPORTED,
                reason = SaveDetectionReason.TRUNCATED_RECOGNIZED_HEADER,
                identity = identity,
                facts = baseFacts.copy(requiredEndExclusive = SaveLayoutFacts.NORMAL_HEADER_SIZE.toLong()),
                evidence = listOf(
                    "The v103 / Build 04.12.02 identity is recognized, but the 432-byte header is incomplete.",
                ),
            )
        }

        val header = SaveHeaderParser.parseBuild041202(bytes)
        try {
            NormalEncryptionHeaderInputs.fromBuild041202(header)
        } catch (_: InvalidEncryptionHeaderInputException) {
            return result(
                state = SaveDetectionState.PARTIALLY_SUPPORTED,
                reason = SaveDetectionReason.UNSUPPORTED_SELECTOR_HEADER,
                identity = identity,
                facts = baseFacts.copy(selectorHeaderCompatible = false),
                evidence = listOf(
                    "The recognized header contains values outside the evidenced encryption-selector domain.",
                ),
            )
        }

        val frame = try {
            NormalNonLinuxProfileFramer.frameBuild041202(bytes)
        } catch (failure: ProfileFramingException) {
            return result(
                state = SaveDetectionState.PARTIALLY_SUPPORTED,
                reason = when (failure.reason) {
                    ProfileFramingFailure.TRUNCATED_INPUT ->
                        SaveDetectionReason.TRUNCATED_NORMAL_NON_LINUX_LAYOUT
                    ProfileFramingFailure.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL ->
                        SaveDetectionReason.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL
                },
                identity = identity,
                facts = baseFacts.copy(
                    selectorHeaderCompatible = true,
                    eventCount = failure.eventCount,
                    requiredEndExclusive = failure.requiredEndExclusive,
                    framingStage = failure.stage,
                ),
                evidence = listOf(
                    "The recognized header did not establish the complete supported normal non-Linux prefix.",
                ),
            )
        }

        try {
            NormalProfileRotationRecovery.recoverBuild041202(frame.encryptedProfileBytes)
        } catch (_: ProfileRotationRecoveryException) {
            return result(
                state = SaveDetectionState.PARTIALLY_SUPPORTED,
                reason = SaveDetectionReason.PROFILE_ROTATION_NOT_CONFIRMED,
                identity = identity,
                facts = baseFacts.copy(
                    selectorHeaderCompatible = true,
                    eventCount = frame.eventCount,
                    profileStartOffset = frame.profileStartOffset,
                    profileEndExclusive = frame.profileEndExclusive,
                ),
                evidence = listOf(
                    "Header and layout facts match, but bounded profile consistency did not confirm the Reborn path.",
                ),
            )
        }

        return result(
            family = SaveFamily.JA2_REBORN,
            state = SaveDetectionState.SUPPORTED,
            reason = SaveDetectionReason.MATCHED_REBORN_BUILD_041202_NORMAL_NON_LINUX,
            identity = identity,
            facts = baseFacts.copy(
                selectorHeaderCompatible = true,
                eventCount = frame.eventCount,
                profileStartOffset = frame.profileStartOffset,
                profileEndExclusive = frame.profileEndExclusive,
            ),
            evidence = listOf(
                "Matched v103 / Build 04.12.02 header identity.",
                "Matched the selector-compatible normal non-Linux prefix and profile-table frame.",
                "Confirmed unique save-derived profile rotation through bounded structural checksums.",
            ),
        )
    }

    private fun result(
        state: SaveDetectionState,
        reason: SaveDetectionReason,
        identity: SaveHeaderIdentity,
        facts: SaveDetectionFacts,
        evidence: List<String>,
        family: SaveFamily = SaveFamily.UNKNOWN,
    ): SaveFormatDetection = SaveFormatDetection.create(
        family = family,
        state = state,
        reason = reason,
        saveVersion = identity.saveVersion?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
        buildLabel = if (
            identity.saveVersion == SaveHeaderParser.SUPPORTED_SAVE_VERSION &&
            identity.gameVersion == SaveHeaderParser.SUPPORTED_GAME_VERSION
        ) NORMALIZED_BUILD_LABEL else null,
        facts = facts,
        evidence = evidence,
    )
}
