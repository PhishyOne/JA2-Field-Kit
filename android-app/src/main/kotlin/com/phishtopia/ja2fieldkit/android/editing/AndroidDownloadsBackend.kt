package com.phishtopia.ja2fieldkit.android.editing

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import com.phishtopia.ja2fieldkit.android.BuildConfig
import java.io.InputStream

/** Only instantiated behind the independent runtime/build gate. No source Uri is accepted. */
@TargetApi(29)
internal class AndroidDownloadsBackend(
    private val resolver: ContentResolver,
    private val ownerPackage: String,
) : PlacementBackend {
    init { check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29) }
    override val apiLevel: Int get() = Build.VERSION.SDK_INT
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private var freshUri: Uri? = null
    private var written = false

    private fun exactUri(value: String): Uri {
        val uri = Uri.parse(value)
        val id = ContentUris.parseId(uri)
        check(id > 0 && ContentUris.withAppendedId(collection, id) == uri)
        return uri
    }

    override fun createPending(displayName: String): String {
        check(validOutputName(displayName))
        freshUri = null
        written = false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, OUTPUT_DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values))
        exactUri(uri.toString())
        freshUri = uri
        return uri.toString()
    }

    override fun writeAndSyncNew(uri: String, bytes: ByteArray) {
        val destination = exactUri(uri)
        check(destination == freshUri && !written)
        val metadata = metadata(uri)
        check(metadata.owned && metadata.pending && metadata.relativePath == OUTPUT_DIRECTORY)
        written = true // Never reopen a writable descriptor, even after failure.
        val descriptor = checkNotNull(resolver.openFileDescriptor(destination, "w"))
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override fun openRead(uri: String): InputStream =
        checkNotNull(resolver.openInputStream(exactUri(uri)))

    override fun metadata(uri: String): ObjectMetadata {
        val destination = exactUri(uri)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        return checkNotNull(resolver.query(destination, columns, null, null, null)).use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst())
            columns.forEach { check(!cursor.isNull(cursor.getColumnIndexOrThrow(it))) }
            val id = cursor.getLong(0)
            check(ContentUris.withAppendedId(collection, id) == destination)
            val pending = cursor.getInt(3)
            check(pending == 0 || pending == 1)
            ObjectMetadata(destination.toString(), cursor.getString(1), cursor.getLong(2), pending == 1,
                cursor.getString(4) == ownerPackage, cursor.getString(5))
        }
    }

    override fun publish(uri: String): Boolean {
        val destination = exactUri(uri)
        check(destination == freshUri && written)
        check(metadata(uri).let { it.owned && it.pending && it.relativePath == OUTPUT_DIRECTORY })
        freshUri = null // Consume authority BEFORE the potentially ambiguous IPC.
        return resolver.update(destination, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1
    }
}

/** One process-wide serializer/journal owner, including overlapping Activity/ViewModel lifetimes. */
internal object DebugPlacement {
    private var instance: PlacementMachine? = null
    @Synchronized fun get(context: Context): PlacementMachine {
        check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29)
        return instance ?: PlacementMachine(
            AndroidDownloadsBackend(context.applicationContext.contentResolver, context.packageName),
            FilePlacementJournal(context.noBackupFilesDir) { directory ->
                val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
                try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
            },
        ).also { instance = it }
    }
}
