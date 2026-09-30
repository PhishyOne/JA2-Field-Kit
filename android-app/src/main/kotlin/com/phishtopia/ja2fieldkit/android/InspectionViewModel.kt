package com.phishtopia.ja2fieldkit.android

import com.phishtopia.ja2fieldkit.android.importing.CatalogReader
import com.phishtopia.ja2fieldkit.android.presentation.CatalogSession
import android.content.Context
import android.os.Build
import com.phishtopia.ja2fieldkit.android.editing.*
import com.phishtopia.ja2fieldkit.core.SaveEditResult
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import com.phishtopia.ja2fieldkit.android.importing.DisplayNameSanitizer
import com.phishtopia.ja2fieldkit.android.importing.OpenableSourceMetadata
import com.phishtopia.ja2fieldkit.android.importing.ProviderMetadataReader
import com.phishtopia.ja2fieldkit.android.importing.ProviderSaveMetadata
import com.phishtopia.ja2fieldkit.android.importing.SaveImporter
import com.phishtopia.ja2fieldkit.android.importing.SaveTooLargeException
import com.phishtopia.ja2fieldkit.android.importing.SourceMetadataProvider
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.InspectionPresentationMapper
import com.phishtopia.ja2fieldkit.android.presentation.InspectionScreenState
import com.phishtopia.ja2fieldkit.android.presentation.SourceFailureKind
import com.phishtopia.ja2fieldkit.android.presentation.withCompatibilityReportPreview
import com.phishtopia.ja2fieldkit.android.presentation.withSelectedMerc
import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Holds presentation and one ephemeral debug edit snapshot across configuration changes. */
class InspectionViewModel : ViewModel() {
    private val catalogSession = CatalogSession()
    private val inspector = Ja2SaveInspector()
    private val importer = SaveImporter()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSequence = AtomicLong()
    private var state: InspectionScreenState = InspectionScreenState.Initial
    private var observer: ((InspectionScreenState) -> Unit)? = null

    private var editSession: EditSession? = null
    private var exportBusy = false
    internal var exportStatus: String? = null
        private set
    internal val editorAvailable: Boolean get() = EditSession.available(BuildConfig.DEBUG, Build.VERSION.SDK_INT)
    internal fun editMerc(profileId: Int): EditMerc? =
        if (!editorAvailable || exportBusy) null else editSession?.merc(profileId)

    internal fun exportEdit(context: Context, merc: EditMerc, choice: EditChoice) {
        if (!editorAvailable || exportBusy) return
        val session = editSession ?: return
        if (session.merc(merc.profileId) !== merc) return
        exportBusy = true
        exportStatus = "Verifying one edit and creating a new save…"
        publish(state)
        executor.execute {
            val message = try {
                when (val result = session.generate(merc.profileId, choice)) {
                    is SaveEditResult.Failure -> "Edit refused: ${result.reason()}. Original untouched."
                    is SaveEditResult.VerifiedCandidate -> {
                        // Once placement starts, reopen a save before another operation.
                        session.consume()
                        DebugPlacement.get(context).place(result).text()
                    }
                }
            } catch (_: Exception) { "Export refused. Original untouched; check export status before another attempt." }
            mainHandler.post {
                exportBusy = false
                exportStatus = message
                publish(state)
            }
        }
    }

    internal fun reconcileExport(context: Context) {
        if (!editorAvailable || exportBusy) return
        exportBusy = true
        executor.execute {
            val message = try { DebugPlacement.get(context).reconcile().text() }
                catch (_: Exception) { "UNCERTAIN: export status unavailable; export blocked" }
            mainHandler.post {
                exportBusy = false
                exportStatus = message
                publish(state)
            }
        }
    }

    private fun clearEditSession() { editSession?.clear(); editSession = null }

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
        clearEditSession()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            showSourceFailure(SourceFailureKind.NOT_CONTENT_URI)
            return
        }

        val request = requestSequence.incrementAndGet()
        publish(InspectionScreenState.Loading(sourceName = null))
        executor.execute {
            var nextSession: EditSession? = null
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
                imported.inspectWith(inspector) { inspection, inventory ->
                    InspectionPresentationMapper.map(imported.provenance, inspection, inventory)
                }.also { mapped ->
                    if (editorAvailable && mapped is InspectionScreenState.Success) nextSession = imported.takeEditSession()
                }
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
            mainHandler.post {
                if (requestSequence.get() == request) {
                    clearEditSession()
                    editSession = nextSession
                    publish(nextState)
                } else nextSession?.clear()
            }
        }
    }

    fun showSourceFailure(kind: SourceFailureKind) {
        clearEditSession()
        requestSequence.incrementAndGet()
        publish(InspectionPresentationMapper.sourceFailure(kind))
    }

    fun showCompatibilityReportPreview() {
        val next = state.withCompatibilityReportPreview(BuildConfig.VERSION_NAME)
        if (next !== state) publish(next)
    }

    fun selectMerc(profileIndex: Int) {
        val next = state.withSelectedMerc(profileIndex)
        if (next !== state) publish(next)
    }

    private fun withCatalog(next: InspectionScreenState): InspectionScreenState =
        if (next is InspectionScreenState.Success) next.copy(catalog = catalogSession.presentation) else next

    fun loadItemNames(resolver: ContentResolver, uri: Uri) {
        val request = catalogSession.begin()
        publish(state)
        executor.execute {
            val result = CatalogReader.read(uri.scheme) { resolver.openInputStream(uri) }
            mainHandler.post {
                if (catalogSession.complete(request, result)) publish(state)
            }
        }
    }

    private fun querySourceMetadata(
        resolver: ContentResolver,
        uri: Uri,
        provenance: SourceProvenance,
    ): ProviderSaveMetadata = ProviderMetadataReader.read(
        provider = object : SourceMetadataProvider {
            override fun queryOpenableMetadata(): OpenableSourceMetadata? = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                OpenableSourceMetadata(
                    displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                    declaredSizeBytes = cursor.integerOrNull(OpenableColumns.SIZE),
                )
            }

            // This document-only column is deliberately isolated from standard openable metadata.
            override fun queryLastModifiedValue(): Any? = resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.integerOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            }
        },
        provenance = provenance,
    )

    private fun post(request: Long, next: InspectionScreenState) {
        mainHandler.post {
            if (requestSequence.get() == request) publish(next)
        }
    }

    private fun publish(next: InspectionScreenState) {
        state = withCatalog(next)
        observer?.invoke(state)
    }

    override fun onCleared() {
        requestSequence.incrementAndGet()
        clearEditSession()
        observer = null
        catalogSession.clear()
        executor.shutdownNow()
    }
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.integerOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || getType(index) != Cursor.FIELD_TYPE_INTEGER) null else getLong(index)
}
