package com.phishtopia.ja2fieldkit.android

import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.SourceFailureKind

internal const val ACTION_VIEW_IMPORT = "android.intent.action.VIEW"
internal const val ACTION_SEND_IMPORT = "android.intent.action.SEND"

internal data class IncomingImportIntent<T>(
    val action: String?,
    val viewSource: T? = null,
    val sharedSource: T? = null,
    val sharedSourceCount: Int = 0,
)

internal sealed interface ImportIntentRequest<out T> {
    data object None : ImportIntentRequest<Nothing>

    data class Inspect<T>(
        val source: T,
        val provenance: SourceProvenance,
    ) : ImportIntentRequest<T>

    data class Reject(val kind: SourceFailureKind) : ImportIntentRequest<Nothing>
}

internal enum class StoredActivityIntent {
    SOURCE_FREE,
}

internal data class ConsumedImportIntent<T>(
    val request: ImportIntentRequest<T>,
    val storedIntent: StoredActivityIntent = StoredActivityIntent.SOURCE_FREE,
)

/** Separates the one-shot import request from the source-free state retained by the activity. */
internal fun <T> consumeImportIntent(incoming: IncomingImportIntent<T>): ConsumedImportIntent<T> {
    val request = when (incoming.action) {
        ACTION_VIEW_IMPORT -> incoming.viewSource
            ?.let { ImportIntentRequest.Inspect(it, SourceProvenance.OPEN_WITH) }
            ?: ImportIntentRequest.Reject(SourceFailureKind.UNAVAILABLE)

        ACTION_SEND_IMPORT -> when {
            incoming.sharedSourceCount > 1 ->
                ImportIntentRequest.Reject(SourceFailureKind.MULTIPLE_ITEMS)

            incoming.sharedSource != null ->
                ImportIntentRequest.Inspect(incoming.sharedSource, SourceProvenance.SHARE_TO)

            else -> ImportIntentRequest.Reject(SourceFailureKind.UNAVAILABLE)
        }

        else -> ImportIntentRequest.None
    }
    return ConsumedImportIntent(request)
}
