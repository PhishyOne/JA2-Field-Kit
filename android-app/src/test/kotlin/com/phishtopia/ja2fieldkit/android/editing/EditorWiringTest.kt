package com.phishtopia.ja2fieldkit.android.editing

import java.io.File
import kotlin.test.*

class EditorWiringTest {
    private val project = File(checkNotNull(System.getProperty("androidAppProjectDir")))
    private fun source(name: String) = project.resolve("src/main/kotlin/com/phishtopia/ja2fieldkit/android/$name.kt").readText()

    @Test fun downloadsQueryPreservesNullSizeButRequiresBindingColumns() {
        val backend = source("editing/AndroidDownloadsBackend")
        assertTrue(backend.contains("if (it != MediaStore.MediaColumns.SIZE) check(!cursor.isNull(index))"))
        assertTrue(backend.contains("val indexedSize = if (cursor.isNull(2)) null else cursor.getLong(2)"))
        assertTrue(backend.contains("check(ContentUris.withAppendedId(collection, id) == destination)"))
        assertTrue(backend.contains("check(pending == 0 || pending == 1)"))
        assertTrue(backend.contains("cursor.getString(4) == ownerPackage"))
    }

    @Test fun releaseAndApiGateProtectUiExecutionAndProviderConstruction() {
        val model = source("InspectionViewModel")
        assertTrue(model.contains("EditSession.available(BuildConfig.DEBUG, Build.VERSION.SDK_INT)"))
        assertEquals(2, Regex("if \\(!editorAvailable \\|\\| exportBusy\\) return").findAll(model).count())
        val backend = source("editing/AndroidDownloadsBackend")
        assertEquals(2, Regex("check\\(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29\\)").findAll(backend).count())
        assertTrue(backend.contains("context.noBackupFilesDir"))
        assertTrue(backend.contains("Os.fsync(fd)"))
    }

    @Test fun mediaWritesAreFreshOnlyPublicationIsOneColumnAndSourceNeverCrossesBoundary() {
        val backend = source("editing/AndroidDownloadsBackend")
        assertTrue(backend.contains("MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)"))
        assertTrue(backend.contains("check(destination == freshUri && !written)"))
        assertTrue(backend.contains("ParcelFileDescriptor.AutoCloseOutputStream"))
        assertTrue(backend.contains("output.flush()\n            output.fd.sync()"))
        val publication = backend.substringAfter("return resolver.update(").substringBefore("== 1")
        assertEquals(1, Regex("put\\(").findAll(publication).count())
        assertTrue(publication.contains("put(MediaStore.MediaColumns.IS_PENDING, 0)"))
        for (forbidden in listOf("resolver.delete(", "ACTION_CREATE_DOCUMENT", "sourceUri", "takePersistableUriPermission"))
            assertFalse(backend.contains(forbidden))
        val manifest = project.resolve("src/main/AndroidManifest.xml").readText()
        for (permission in listOf("WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE"))
            assertFalse(manifest.contains(permission))
    }

    @Test fun snapshotLifecycleStaleDialogAndNoSavedEditHistory() {
        val model = source("InspectionViewModel")
        assertTrue(model.contains("if (session.merc(merc.profileId) !== merc) return"))
        assertTrue(model.substringAfter("override fun onCleared()").contains("clearEditSession()"))
        assertTrue(model.contains("else nextSession?.clear()"))
        assertTrue(model.substringAfter("fun inspect(").substringBefore("executor.execute").contains("clearEditSession()"))
        val dialog = source("EditDialog")
        assertFalse(dialog.contains("expectedCurrent")); assertFalse(dialog.contains("ExpectedSlot"))
        assertEquals(4, Regex("isSaveEnabled = false").findAll(dialog).count())
        assertFalse(dialog.contains("SavedStateHandle"))
    }
}
