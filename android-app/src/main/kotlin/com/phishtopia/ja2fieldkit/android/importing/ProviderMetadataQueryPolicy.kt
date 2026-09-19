package com.phishtopia.ja2fieldkit.android.importing

internal enum class ProviderMetadataProjection {
    STANDARD,
    LAST_MODIFIED,
}

internal data class QueriedProviderMetadata(
    val displayName: String? = null,
    val declaredSizeBytes: Long? = null,
    val lastModifiedEpochMillis: Long? = null,
)

/** Keeps optional document metadata from becoming a prerequisite for openable metadata. */
internal object ProviderMetadataQueryPolicy {
    fun query(
        provenance: SourceProvenance,
        query: (ProviderMetadataProjection) -> QueriedProviderMetadata?,
    ): ProviderSaveMetadata {
        val standard = queryOrNull(ProviderMetadataProjection.STANDARD, query)
        val timestamp = queryOrNull(ProviderMetadataProjection.LAST_MODIFIED, query)
        return ProviderSaveMetadata(
            displayName = DisplayNameSanitizer.sanitize(standard?.displayName),
            declaredSizeBytes = standard?.declaredSizeBytes?.takeIf { it >= 0 },
            lastModifiedEpochMillis = timestamp?.lastModifiedEpochMillis?.takeIf { it > 0 },
            provenance = provenance,
        )
    }

    private fun queryOrNull(
        projection: ProviderMetadataProjection,
        query: (ProviderMetadataProjection) -> QueriedProviderMetadata?,
    ): QueriedProviderMetadata? = try {
        query(projection)
    } catch (_: RuntimeException) {
        null
    }
}
