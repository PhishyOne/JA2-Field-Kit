package com.phishtopia.ja2fieldkit.core.format

import java.security.MessageDigest
import java.util.Collections

/** Known producer-family labels. Layout compatibility never assigns one of these by itself. */
enum class SaveFamily {
    JA2_REBORN,
    STRACCIATELLA,
    CLASSIC,
    UNKNOWN,
}

/** Serialized layout identity, independent of the executable that produced the bytes. */
enum class SaveLayout {
    NORMAL_V103_BUILD_041202_NON_LINUX,
    UNKNOWN,
}

/** Compatibility of the supplied bytes with the current read-only core. */
enum class SaveCompatibility {
    SUPPORTED,
    CANDIDATE,
    TRUNCATED,
    UNSUPPORTED_VARIANT,
    INCONSISTENT,
    UNKNOWN,
}

/** A bounded reason code; messages and input bytes are deliberately not retained. */
enum class SaveDetectionReason {
    SELECTOR_BODY_ROTATION_MATCH,
    ROTATION_DIGEST_ORACLE_MISSING,
    ROTATION_DIGEST_MISMATCH,
    PROFILE_ROTATION_CONSTRAINT_CONFLICT,
    PROFILE_ROTATION_NO_CANDIDATE,
    PROFILE_ROTATION_AMBIGUOUS,
    TOO_SHORT_FOR_HEADER_IDENTITY,
    TRUNCATED_RECOGNIZED_HEADER,
    UNKNOWN_HEADER_IDENTITY,
    CONTRADICTORY_HEADER_IDENTITY,
    UNSUPPORTED_SELECTOR_HEADER,
    TRUNCATED_NORMAL_NON_LINUX_LAYOUT,
    UNSUPPORTED_DYNAMIC_LAPTOP_TAIL,
}

/**
 * Safe structural facts collected before detection stops.
 *
 * No descriptions, payload bytes, plaintext, profile data, rotation bytes, or digests are retained.
 */
data class SaveDetectionFacts(
    val fileSize: Int,
    val hasCompleteHeaderIdentity: Boolean,
    val hasCompleteNormalHeader: Boolean,
    val rawSaveVersion: Long? = null,
    val gameVersion: String? = null,
    val selectorHeaderCompatible: Boolean? = null,
    val selectedRotationIndex: Int? = null,
    val rotationOracleAvailable: Boolean? = null,
    val rotationDigestMatched: Boolean? = null,
    val eventCount: Long? = null,
    val profileStartOffset: Int? = null,
    val profileEndExclusive: Int? = null,
    val requiredEndExclusive: Long? = null,
    val framingStage: ProfileFramingStage? = null,
)

data class SaveFormatDetection(
    val layout: SaveLayout,
    val compatibility: SaveCompatibility,
    val family: SaveFamily,
    val saveVersion: Int? = null,
    /** Normalized product build label. Set only for the exact recognized identity. */
    val buildLabel: String? = null,
    val evidence: List<String> = emptyList(),
    val reason: SaveDetectionReason = SaveDetectionReason.UNKNOWN_HEADER_IDENTITY,
    val facts: SaveDetectionFacts = SaveDetectionFacts(
        fileSize = 0,
        hasCompleteHeaderIdentity = false,
        hasCompleteNormalHeader = false,
    ),
) {
    companion object {
        internal fun create(
            layout: SaveLayout,
            compatibility: SaveCompatibility,
            reason: SaveDetectionReason,
            saveVersion: Int?,
            buildLabel: String?,
            facts: SaveDetectionFacts,
            evidence: List<String>,
            family: SaveFamily = SaveFamily.UNKNOWN,
        ): SaveFormatDetection = SaveFormatDetection(
            layout = layout,
            compatibility = compatibility,
            family = family,
            reason = reason,
            saveVersion = saveVersion,
            buildLabel = buildLabel,
            facts = facts,
            evidence = Collections.unmodifiableList(ArrayList(evidence)),
        )
    }
}

/** Validated, non-secret SHA-256 identity for one rotation table. */
@JvmInline
internal value class RotationTableDigest private constructor(val hexadecimal: String) {
    companion object {
        fun parse(hexadecimal: String): RotationTableDigest {
            require(hexadecimal.matches(Regex("[0-9a-f]{64}"))) {
                "Rotation-table digest must be a lowercase SHA-256 value"
            }
            return RotationTableDigest(hexadecimal)
        }

        fun from(table: SaveRotationTable): RotationTableDigest {
            val digest = MessageDigest.getInstance("SHA-256").digest(table.toByteArray())
            return RotationTableDigest(
                digest.joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            )
        }
    }
}

/** Non-secret identity boundary. Implementations must not expose or retain rotation bytes. */
internal fun interface RotationDigestOracle {
    fun digestFor(index: NormalRotationTableIndex): RotationTableDigest?
}

/**
 * Minimal public-build oracle derived from the exact immutable upstream source.
 *
 * Only indexes 124 and 139 are admitted. The repository stores their digests, never the
 * corresponding 49-byte rows.
 * Provenance: RealTommyGreen/JA2-Reborn commit 743f38a6ca86c81893376c2576277db660320170,
 * src/game/Tactical/Tactical_Save.cc blob 5640f1a623f609f973020b0a617bbc00b2dafe75.
 * Matching either digest establishes selector/body identity, not producer family.
 */
internal object Build041202RotationDigestOracle : RotationDigestOracle {
    private val index124 = RotationTableDigest.parse(
        "384d8f0b52fe4413ea361c3027a3293b54d1763eb9828cc1cb0feb483c964306",
    )
    private val index139 = RotationTableDigest.parse(
        "b9cf6efc03ac27c7c1293f83df845ae077922af388f4cb149e9041edbf5f68bc",
    )

    override fun digestFor(index: NormalRotationTableIndex): RotationTableDigest? =
        when (index.value) {
            124 -> index124
            139 -> index139
            else -> null
        }
}

/** Evidence-based, fail-closed detector for the single currently evidenced layout. */
object SaveFormatDetector {
    private const val NORMALIZED_BUILD_LABEL = "04.12.02"

    fun detect(bytes: ByteArray): SaveFormatDetection =
        detect(bytes, Build041202RotationDigestOracle)

    internal fun detect(
        bytes: ByteArray,
        rotationDigestOracle: RotationDigestOracle,
    ): SaveFormatDetection {
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
                compatibility = SaveCompatibility.UNKNOWN,
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
                compatibility = if (contradictory) {
                    SaveCompatibility.INCONSISTENT
                } else {
                    SaveCompatibility.UNKNOWN
                },
                reason = if (contradictory) {
                    SaveDetectionReason.CONTRADICTORY_HEADER_IDENTITY
                } else {
                    SaveDetectionReason.UNKNOWN_HEADER_IDENTITY
                },
                identity = identity,
                facts = baseFacts,
                evidence = listOf(
                    "The saved-game version and build identity do not jointly match the evidenced layout.",
                ),
            )
        }

        if (bytes.size < SaveLayoutFacts.NORMAL_HEADER_SIZE) {
            return result(
                compatibility = SaveCompatibility.TRUNCATED,
                reason = SaveDetectionReason.TRUNCATED_RECOGNIZED_HEADER,
                identity = identity,
                facts = baseFacts.copy(requiredEndExclusive = SaveLayoutFacts.NORMAL_HEADER_SIZE.toLong()),
                evidence = listOf(
                    "The v103 / Build 04.12.02 identity is recognized, but the 432-byte header is incomplete.",
                ),
            )
        }

        val header = SaveHeaderParser.parseBuild041202(bytes)
        val selectedIndex = try {
            NormalSaveEncryptionSelector.select(
                NormalEncryptionHeaderInputs.fromBuild041202(header),
            )
        } catch (_: InvalidEncryptionHeaderInputException) {
            return result(
                compatibility = SaveCompatibility.UNSUPPORTED_VARIANT,
                reason = SaveDetectionReason.UNSUPPORTED_SELECTOR_HEADER,
                identity = identity,
                facts = baseFacts.copy(selectorHeaderCompatible = false),
                evidence = listOf(
                    "The recognized header contains values outside the evidenced encryption-selector domain.",
                ),
            )
        }

        val selectorFacts = baseFacts.copy(
            selectorHeaderCompatible = true,
            selectedRotationIndex = selectedIndex.value,
        )
        val frame = try {
            NormalNonLinuxProfileFramer.frameBuild041202(bytes)
        } catch (failure: ProfileFramingException) {
            return result(
                compatibility = when (failure.reason) {
                    ProfileFramingFailure.TRUNCATED_INPUT -> SaveCompatibility.TRUNCATED
                    ProfileFramingFailure.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL ->
                        SaveCompatibility.UNSUPPORTED_VARIANT
                },
                reason = when (failure.reason) {
                    ProfileFramingFailure.TRUNCATED_INPUT ->
                        SaveDetectionReason.TRUNCATED_NORMAL_NON_LINUX_LAYOUT
                    ProfileFramingFailure.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL ->
                        SaveDetectionReason.UNSUPPORTED_DYNAMIC_LAPTOP_TAIL
                },
                identity = identity,
                facts = selectorFacts.copy(
                    eventCount = failure.eventCount,
                    requiredEndExclusive = failure.requiredEndExclusive,
                    framingStage = failure.stage,
                ),
                evidence = listOf(
                    "The recognized header did not establish a complete normal non-Linux profile frame.",
                ),
            )
        }

        val framedFacts = selectorFacts.copy(
            eventCount = frame.eventCount,
            profileStartOffset = frame.profileStartOffset,
            profileEndExclusive = frame.profileEndExclusive,
        )
        val recoveredRotation = try {
            NormalProfileRotationRecovery.recoverBuild041202(frame.encryptedProfileBytes)
        } catch (failure: ProfileRotationRecoveryException) {
            return result(
                layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
                compatibility = when (failure.reason) {
                    ProfileRotationRecoveryFailure.AMBIGUOUS -> SaveCompatibility.CANDIDATE
                    ProfileRotationRecoveryFailure.CONFLICTING_RESERVED_CONSTRAINT,
                    ProfileRotationRecoveryFailure.NO_CANDIDATE -> SaveCompatibility.INCONSISTENT
                    ProfileRotationRecoveryFailure.INVALID_BLOCK_SIZE -> error(
                        "The profile framer returned a non-canonical block size",
                    )
                },
                reason = when (failure.reason) {
                    ProfileRotationRecoveryFailure.CONFLICTING_RESERVED_CONSTRAINT ->
                        SaveDetectionReason.PROFILE_ROTATION_CONSTRAINT_CONFLICT
                    ProfileRotationRecoveryFailure.NO_CANDIDATE ->
                        SaveDetectionReason.PROFILE_ROTATION_NO_CANDIDATE
                    ProfileRotationRecoveryFailure.AMBIGUOUS ->
                        SaveDetectionReason.PROFILE_ROTATION_AMBIGUOUS
                    ProfileRotationRecoveryFailure.INVALID_BLOCK_SIZE -> error(
                        "The profile framer returned a non-canonical block size",
                    )
                },
                identity = identity,
                facts = framedFacts,
                evidence = listOf(
                    "The complete frame did not yield one uniquely recoverable profile rotation.",
                ),
            )
        }

        val expectedDigest = rotationDigestOracle.digestFor(selectedIndex)
            ?: return result(
                layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
                compatibility = SaveCompatibility.CANDIDATE,
                reason = SaveDetectionReason.ROTATION_DIGEST_ORACLE_MISSING,
                identity = identity,
                facts = framedFacts.copy(rotationOracleAvailable = false),
                evidence = listOf(
                    "The layout and body are structurally consistent, but the selected index has no admitted digest oracle.",
                ),
            )

        if (RotationTableDigest.from(recoveredRotation) != expectedDigest) {
            return result(
                layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
                compatibility = SaveCompatibility.INCONSISTENT,
                reason = SaveDetectionReason.ROTATION_DIGEST_MISMATCH,
                identity = identity,
                facts = framedFacts.copy(
                    rotationOracleAvailable = true,
                    rotationDigestMatched = false,
                ),
                evidence = listOf(
                    "The recovered body rotation does not match the admitted digest for the header-selected index.",
                ),
            )
        }

        return result(
            layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
            compatibility = SaveCompatibility.SUPPORTED,
            reason = SaveDetectionReason.SELECTOR_BODY_ROTATION_MATCH,
            identity = identity,
            facts = framedFacts.copy(
                rotationOracleAvailable = true,
                rotationDigestMatched = true,
            ),
            evidence = listOf(
                "Matched the v103 / Build 04.12.02 identity and complete normal non-Linux profile frame.",
                "Matched the recovered body rotation to the admitted digest for the header-selected index.",
                "No byte discriminator attributes the producer family.",
            ),
        )
    }

    private fun result(
        compatibility: SaveCompatibility,
        reason: SaveDetectionReason,
        identity: SaveHeaderIdentity,
        facts: SaveDetectionFacts,
        evidence: List<String>,
        layout: SaveLayout = SaveLayout.UNKNOWN,
    ): SaveFormatDetection = SaveFormatDetection.create(
        layout = layout,
        compatibility = compatibility,
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
