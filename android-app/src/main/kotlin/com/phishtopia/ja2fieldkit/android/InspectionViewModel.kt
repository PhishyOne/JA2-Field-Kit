package com.phishtopia.ja2fieldkit.android

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import com.phishtopia.ja2fieldkit.android.importing.DisplayNameSanitizer
import com.phishtopia.ja2fieldkit.android.importing.ProviderMetadataProjection
import com.phishtopia.ja2fieldkit.android.importing.ProviderMetadataQueryPolicy
import com.phishtopia.ja2fieldkit.android.importing.ProviderSaveMetadata
import com.phishtopia.ja2fieldkit.android.importing.QueriedProviderMetadata
import com.phishtopia.ja2fieldkit.android.importing.SaveImporter
import com.phishtopia.ja2fieldkit.android.importing.SaveTooLargeException
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.InspectionPresentationMapper
import com.phishtopia.ja2fieldkit.android.presentation.InspectionScreenState
import com.phishtopia.ja2fieldkit.android.presentation.SourceFailureKind
import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Retains only presentation state across configuration changes; save bytes stay task-local. */
class InspectionViewModel : ViewModel() {
    private val inspector = Ja2SaveInspector()
    private val importer = SaveImporter()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSequence = AtomicLong()
    private var state: InspectionScreenState = InspectionScreenState.Initial
    private var observer: ((InspectionScreenState) -> Unit)? = null

    fun attach(nextObserver: (InspectionScreenState) -> Unit) {
        observer = nextObserver
        nextObserver(state)
    }

    fun detach(currentObserver: (InspectionScreenState) -> Unit) {
        if (observer === currentObserver) observer = null
    }

    fun inspect(
        resolver: ContentResolver,
        uri: Uri,
        provenance: SourceProvenance,
    ) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            showSourceFailure(SourceFailureKind.NOT_CONTENT_URI)
            return
        }

        val request = requestSequence.incrementAndGet()
        publish(InspectionScreenState.Loading(sourceName = null))
        executor.execute {
            val nextState = try {
                val providerMetadata = querySourceMetadata(resolver, uri, provenance)
                post(
                    request,
                    InspectionScreenState.Loading(
                        DisplayNameSanitizer.sanitize(providerMetadata.displayName),
                    ),
                )
                val imported = resolver.openInputStream(uri)?.use { input ->
                    importer.import(input, providerMetadata)
                } ?: throw FileNotFoundException()
                InspectionPresentationMapper.map(
                    imported.provenance,
                    imported.inspectV01With(inspector),
                )
            } catch (_: SaveTooLargeException) {
                InspectionPresentationMapper.sourceFailure(SourceFailureKind.SIZE_LIMIT)
            } catch (_: SecurityException) {
                InspectionPresentationMapper.sourceFailure(SourceFailureKind.UNAVAILABLE)
            } catch (_: FileNotFoundException) {
                InspectionPresentationMapper.sourceFailure(SourceFailureKind.UNAVAILABLE)
            } catch (_: IOException) {
                InspectionPresentationMapper.sourceFailure(SourceFailureKind.READ_FAILED)
            } catch (_: RuntimeException) {
                InspectionPresentationMapper.sourceFailure(SourceFailureKind.READ_FAILED)
            }
            post(request, nextState)
        }
    }

    fun showSourceFailure(kind: SourceFailureKind) {
        requestSequence.incrementAndGet()
        publish(InspectionPresentationMapper.sourceFailure(kind))
    }

    private fun querySourceMetadata(
        resolver: ContentResolver,
        uri: Uri,
        provenance: SourceProvenance,
    ): ProviderSaveMetadata = ProviderMetadataQueryPolicy.query(provenance) { projection ->
        when (projection) {
            ProviderMetadataProjection.STANDARD -> resolver.queryFirst(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            ) { cursor ->
                QueriedProviderMetadata(
                    displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                    declaredSizeBytes = cursor.longOrNull(OpenableColumns.SIZE),
                )
            }

            ProviderMetadataProjection.LAST_MODIFIED -> resolver.queryFirst(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            ) { cursor ->
                QueriedProviderMetadata(
                    lastModifiedEpochMillis = cursor.longOrNull(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                )
            }
        }
    }

    private fun post(request: Long, next: InspectionScreenState) {
        mainHandler.post {
            if (requestSequence.get() == request) publish(next)
        }
    }

    private fun publish(next: InspectionScreenState) {
        state = next
        observer?.invoke(next)
    }

    override fun onCleared() {
        requestSequence.incrementAndGet()
        executor.shutdownNow()
    }
}

private fun <T> ContentResolver.queryFirst(
    uri: Uri,
    projection: Array<String>,
    read: (Cursor) -> T,
): T? = query(uri, projection, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) read(cursor) else null
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index) || getType(index) != Cursor.FIELD_TYPE_STRING) {
        null
    } else {
        getString(index)
    }
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index) || getType(index) != Cursor.FIELD_TYPE_INTEGER) {
        null
    } else {
        getLong(index)
    }
}
