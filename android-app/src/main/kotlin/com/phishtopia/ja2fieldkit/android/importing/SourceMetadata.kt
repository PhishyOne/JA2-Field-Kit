package com.phishtopia.ja2fieldkit.android.importing

import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import java.io.InputStream

enum class SourceProvenance {
    DOCUMENT_PICKER,
    OPEN_WITH,
    SHARE_TO,
}

/** Optional metadata reported by a document provider before its stream is accepted. */
data class ProviderSaveMetadata(
    val displayName: String?,
    val declaredSizeBytes: Long?,
    val lastModifiedEpochMillis: Long?,
    val provenance: SourceProvenance,
)

/** Presentation-safe provenance for one completely imported byte snapshot. */
data class ImportedSaveProvenance(
    val displayName: String,
    val provenance: SourceProvenance,
    val declaredSizeBytes: Long?,
    val actualSizeBytes: Long,
    val lastModifiedEpochMillis: Long?,
    val sha256Hex: String,
) {
    init {
        require(displayName == DisplayNameSanitizer.sanitize(displayName))
        require(declaredSizeBytes == null || declaredSizeBytes >= 0)
        require(actualSizeBytes >= 0)
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis > 0)
        require(sha256Hex.matches(Regex("[0-9a-f]{64}")))
    }
}

/**
 * A task-local accepted import. Its mutable JVM byte container is never exposed as state;
 * callers may only lend it directly to the immediate inspection call.
 */
class ImportedSave internal constructor(
    private val exactBytes: ByteArray,
    val provenance: ImportedSaveProvenance,
) {
    internal fun inspectV01With(inspector: Ja2SaveInspector): SaveInspectionV01Result =
        inspector.inspectV01(exactBytes)
}

/** The one source-agnostic stream boundary shared by every Android content-URI entry point. */
class SaveImporter(
    private val reader: BoundedSaveReader = BoundedSaveReader(),
) {
    fun import(input: InputStream, metadata: ProviderSaveMetadata): ImportedSave {
        val declaredSize = metadata.declaredSizeBytes?.takeIf { it >= 0 }
        val complete = reader.read(input, declaredSize)
        return ImportedSave(
            exactBytes = complete.bytes,
            provenance = ImportedSaveProvenance(
                displayName = DisplayNameSanitizer.sanitize(metadata.displayName),
                provenance = metadata.provenance,
                declaredSizeBytes = declaredSize,
                actualSizeBytes = complete.actualSizeBytes,
                lastModifiedEpochMillis = metadata.lastModifiedEpochMillis?.takeIf { it > 0 },
                sha256Hex = complete.sha256Hex,
            ),
        )
    }
}

object DisplayNameSanitizer {
    private const val MAXIMUM_CODE_POINTS = 120

    fun sanitize(providerName: String?): String {
        val leaf = providerName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filterNot { it.isISOControl() }
            ?.trim()
            .orEmpty()
        if (leaf.isEmpty()) return "Selected save"

        val count = leaf.codePointCount(0, leaf.length)
        if (count <= MAXIMUM_CODE_POINTS) return leaf
        val end = leaf.offsetByCodePoints(0, MAXIMUM_CODE_POINTS)
        return leaf.substring(0, end) + "…"
    }
}
