package com.phishtopia.ja2fieldkit.android

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import com.phishtopia.ja2fieldkit.android.importing.BoundedSaveReader
import com.phishtopia.ja2fieldkit.android.importing.DisplayNameSanitizer
import com.phishtopia.ja2fieldkit.android.importing.SaveTooLargeException
import com.phishtopia.ja2fieldkit.android.importing.SourceMetadata
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
    private val reader = BoundedSaveReader()
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
            showSourceFailure(null, SourceFailureKind.NOT_CONTENT_URI)
            return
        }

        val request = requestSequence.incrementAndGet()
        publish(InspectionScreenState.Loading(sourceName = null))
        executor.execute {
            var source: SourceMetadata? = null
            val nextState = try {
                source = querySourceMetadata(resolver, uri, provenance)
                post(request, InspectionScreenState.Loading(source.displayName))
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    reader.read(input, source.declaredSizeBytes)
                } ?: throw FileNotFoundException()
                InspectionPresentationMapper.map(source, inspector.inspectV01(bytes))
            } catch (_: SaveTooLargeException) {
                InspectionPresentationMapper.sourceFailure(source, SourceFailureKind.SIZE_LIMIT)
            } catch (_: SecurityException) {
                InspectionPresentationMapper.sourceFailure(source, SourceFailureKind.UNAVAILABLE)
            } catch (_: FileNotFoundException) {
                InspectionPresentationMapper.sourceFailure(source, SourceFailureKind.UNAVAILABLE)
            } catch (_: IOException) {
                InspectionPresentationMapper.sourceFailure(source, SourceFailureKind.READ_FAILED)
            } catch (_: RuntimeException) {
                InspectionPresentationMapper.sourceFailure(source, SourceFailureKind.READ_FAILED)
            }
            post(request, nextState)
        }
    }

    fun showSourceFailure(source: SourceMetadata?, kind: SourceFailureKind) {
        requestSequence.incrementAndGet()
        publish(InspectionPresentationMapper.sourceFailure(source, kind))
    }

    private fun querySourceMetadata(
        resolver: ContentResolver,
        uri: Uri,
        provenance: SourceProvenance,
    ): SourceMetadata {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                    size = cursor.longOrNull(OpenableColumns.SIZE)?.takeIf { it >= 0 }
                }
            }
        } catch (_: RuntimeException) {
            // Metadata is optional. Stream access remains the authority.
        }
        return SourceMetadata(
            displayName = DisplayNameSanitizer.sanitize(name),
            declaredSizeBytes = size,
            provenance = provenance,
        )
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

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}
