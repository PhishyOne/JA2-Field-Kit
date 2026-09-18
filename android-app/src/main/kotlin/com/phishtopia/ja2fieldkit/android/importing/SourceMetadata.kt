package com.phishtopia.ja2fieldkit.android.importing

enum class SourceProvenance {
    DOCUMENT_PICKER,
    OPEN_WITH,
    SHARE_TO,
}

data class SourceMetadata(
    val displayName: String,
    val declaredSizeBytes: Long?,
    val provenance: SourceProvenance,
)

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
