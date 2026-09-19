package com.phishtopia.ja2fieldkit.android.importing

internal data class OpenableSourceMetadata(
    val displayName: String?,
    val declaredSizeBytes: Long?,
)

/** Keeps independently optional provider capabilities from invalidating each other. */
internal interface SourceMetadataProvider {
    fun queryOpenableMetadata(): OpenableSourceMetadata?

    /** Returns the untrusted provider value so non-integer column types can be rejected. */
    fun queryLastModifiedValue(): Any?
}

internal object ProviderMetadataReader {
    fun read(
        provider: SourceMetadataProvider,
        provenance: SourceProvenance,
    ): ProviderSaveMetadata {
        val openable = try {
            provider.queryOpenableMetadata()
        } catch (_: RuntimeException) {
            null
        }
        val lastModified = try {
            (provider.queryLastModifiedValue() as? Long)?.takeIf { it > 0 }
        } catch (_: RuntimeException) {
            null
        }

        return ProviderSaveMetadata(
            displayName = DisplayNameSanitizer.sanitize(openable?.displayName),
            declaredSizeBytes = openable?.declaredSizeBytes?.takeIf { it >= 0 },
            lastModifiedEpochMillis = lastModified,
            provenance = provenance,
        )
    }
}
