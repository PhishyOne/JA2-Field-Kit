package com.phishtopia.ja2fieldkit.android.editing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Single receipt + one bounded temporary file. The directory is app-private/no-backup. */
internal class FilePlacementJournal(
    private val directory: File,
    private val syncDirectory: (File) -> Unit,
) : PlacementJournal {
    private val committed = File(directory, "placement.receipt")
    private val temporary = File(directory, "placement.receipt.tmp")

    override fun read(): PlacementRecord? {
        // A temporary file with no committed record is ambiguous, not an empty journal.
        if (!committed.exists()) {
            check(!temporary.exists())
            return null
        }
        require(committed.length() in 1..MAX_BYTES.toLong())
        val bytes = committed.inputStream().use { it.readBytesBounded() }
        return decode(bytes)
    }

    override fun store(record: PlacementRecord) {
        val bytes = encode(record)
        check(directory.isDirectory)
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.flush()
            it.fd.sync()
        }
        // Same app-private directory, Linux rename atomicity; no copy/delete fallback.
        check(temporary.renameTo(committed))
        syncDirectory(directory)
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        repeat(MAX_BYTES + 1) {
            val byte = read()
            if (byte < 0) return output.toByteArray()
            check(output.size() < MAX_BYTES)
            output.write(byte)
        }
        error("Receipt too large")
    }

    companion object {
        const val MAX_BYTES = 2048
        fun encode(record: PlacementRecord): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use {
                it.writeInt(1)
                it.writeUTF(record.operationId)
                it.writeUTF(record.uri.orEmpty())
                it.writeUTF(record.sha256)
                it.writeInt(record.size)
                it.writeUTF(record.expectedName)
                it.writeUTF(record.actualName.orEmpty())
                it.writeUTF(record.stage.name)
            }
            return buffer.toByteArray().also { require(it.size <= MAX_BYTES) }
        }
        fun decode(bytes: ByteArray): PlacementRecord {
            require(bytes.size <= MAX_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use {
                check(it.readInt() == 1)
                PlacementRecord(it.readUTF(), it.readUTF().ifEmpty { null }, it.readUTF(), it.readInt(),
                    it.readUTF(), it.readUTF().ifEmpty { null }, PlacementStage.valueOf(it.readUTF()))
                    .also { _ -> check(it.read() == -1) }
            }
        }
    }
}
