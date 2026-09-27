package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.catalog.BaseItemCatalog
import com.phishtopia.ja2fieldkit.core.catalog.CatalogFailure
import com.phishtopia.ja2fieldkit.core.catalog.CatalogResult
import java.util.Collections

/** Names are separately retained display decoration, never merc identity. */
class CatalogNames(catalog: BaseItemCatalog) {
    val label = catalog.label
    val names: Map<Int, String> = Collections.unmodifiableMap(catalog.names.mapValues {
        PresentationTextSanitizer.sanitize(it.value)
    }.filterValues { it.isNotBlank() })
}

data class CatalogPresentation(
    val active: CatalogNames? = null,
    val loading: Boolean = false,
    val rejection: CatalogFailure? = null,
) {
    val status: String get() {
        val current = active?.let { "Base catalog: ${it.label}." } ?: "Item names not loaded; numeric IDs only."
        return when {
            loading -> "$current Loading item names…"
            rejection != null -> "$current Item names rejected: ${rejection.text()}" +
                if (active != null) " Previous catalog remains active." else ""
            else -> current
        }
    }
}

private fun CatalogFailure.text(): String = when (this) {
    CatalogFailure.WRONG_SIZE -> "incorrect archive size."
    CatalogFailure.WRONG_IDENTITY -> "not the supported GOG English archive."
    CatalogFailure.MALFORMED_ARCHIVE -> "invalid archive structure."
    CatalogFailure.TARGET_NOT_UNIQUE -> "expected one current item description resource."
    CatalogFailure.MALFORMED_ITEMDESC -> "invalid item description layout."
    CatalogFailure.NOT_CONTENT_URI -> "select a document provider source."
    CatalogFailure.UNAVAILABLE -> "document unavailable."
    CatalogFailure.READ_FAILED -> "document could not be read."
}

/** Main-thread owner; independent of save requests and Activity observer lifetimes. */
internal class CatalogSession {
    private var sequence = 0L
    var presentation = CatalogPresentation()
        private set

    fun begin(): Long {
        sequence++
        presentation = presentation.copy(loading = true, rejection = null)
        return sequence
    }

    fun complete(request: Long, result: CatalogResult): Boolean {
        if (request != sequence) return false
        presentation = when (result) {
            is CatalogResult.Loaded -> CatalogPresentation(active = CatalogNames(result.catalog))
            is CatalogResult.Rejected -> presentation.copy(loading = false, rejection = result.reason)
        }
        return true
    }

    fun clear() {
        sequence++
        presentation = CatalogPresentation()
    }
}
