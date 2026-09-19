package com.phishtopia.ja2fieldkit.android.importing

import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** The narrow cursor surface needed to read optional provider metadata. */
internal interface ProviderMetadataRow {
    fun stringOrNull(column: String): String?

    fun longOrNull(column: String): Long?
}

/** Keeps the Android cursor lifetime inside the caller while allowing provider behavior to be tested. */
internal fun interface ProviderMetadataQuery {
    fun query(projection: Array<String>, consume: (ProviderMetadataRow) -> Unit)
}

/**
 * Reads broadly supported openable metadata independently from the optional document timestamp.
 * A provider failure is local to its query; stream access remains authoritative.
 */
internal object ProviderMetadataReader {
    private const val DISPLAY_NAME = OpenableColumns.DISPLAY_NAME
    private const val SIZE = OpenableColumns.SIZE
    private const val LAST_MODIFIED = DocumentsContract.Document.COLUMN_LAST_MODIFIED

    fun read(
        query: ProviderMetadataQuery,
        provenance: SourceProvenance,
    ): ProviderSaveMetadata {
        var name: String? = null
        var size: Long? = null
        try {
            query.query(arrayOf(DISPLAY_NAME, SIZE)) { row ->
                name = row.stringOrNull(DISPLAY_NAME)
                size = row.longOrNull(SIZE)?.takeIf { it >= 0 }
            }
        } catch (_: RuntimeException) {
            return fallback(provenance)
        }

        var lastModified: Long? = null
        try {
            query.query(arrayOf(LAST_MODIFIED)) { row ->
                lastModified = row.longOrNull(LAST_MODIFIED)?.takeIf { it > 0 }
            }
        } catch (_: RuntimeException) {
            // DocumentsContract metadata is optional and is not implemented by every provider.
        }

        return ProviderSaveMetadata(
            displayName = DisplayNameSanitizer.sanitize(name),
            declaredSizeBytes = size,
            lastModifiedEpochMillis = lastModified,
            provenance = provenance,
        )
    }

    private fun fallback(provenance: SourceProvenance) = ProviderSaveMetadata(
        displayName = DisplayNameSanitizer.sanitize(null),
        declaredSizeBytes = null,
        lastModifiedEpochMillis = null,
        provenance = provenance,
    )
}
