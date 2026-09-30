package com.phishtopia.ja2fieldkit.android.editing

import com.phishtopia.ja2fieldkit.core.Ja2SaveEditor
import com.phishtopia.ja2fieldkit.core.SaveEditResult
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal const val OUTPUT_DIRECTORY = "Download/JA2 Field Kit/"
internal enum class PlacementStage {
    PREPARING, CREATED_PENDING, BYTES_VERIFIED_PENDING, PUBLICATION_ATTEMPTED,
    VERIFIED_PUBLISHED, FAILED, UNCERTAIN, VERIFIED_RETAINED_PENDING,
}

/** Exactly one bounded receipt, never save content, source identity or requested edits. */
internal data class PlacementRecord(
    val operationId: String,
    val uri: String?,
    val sha256: String,
    val size: Int,
    val expectedName: String,
    val actualName: String?,
    val stage: PlacementStage,
) {
    init {
        require(operationId.matches(Regex("[0-9a-f-]{36}")))
        require(uri == null || uri.matches(Regex("content://media/external_primary/downloads/[1-9][0-9]{0,18}")))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(size in 1..Ja2SaveEditor.MAX_SAVE_BYTES)
        require(validOutputName(expectedName))
        require(actualName == null || validOutputName(actualName))
    }
}

internal fun validOutputName(name: String): Boolean = name.length in 1..180 &&
    name.endsWith(".sav") && name.none { it == '/' || it == '\\' || it.isISOControl() }

internal data class ObjectMetadata(
    val uri: String, val displayName: String, val indexedSize: Long?,
    val pending: Boolean, val owned: Boolean, val relativePath: String,
)

/** Backend owns the pending object and exposes no existing-destination write API to callers. */
internal interface PlacementBackend {
    val apiLevel: Int
    fun createPending(displayName: String): String
    fun writeAndSyncNew(uri: String, bytes: ByteArray)
    fun metadata(uri: String): ObjectMetadata
    fun openRead(uri: String): InputStream
    /** Must update only IS_PENDING=0, on this same Uri. */
    fun publish(uri: String): Boolean
}
internal interface PlacementJournal {
    fun read(): PlacementRecord?
    fun store(record: PlacementRecord)
}

internal data class PlacementOutcome(val status: String, val record: PlacementRecord? = null) {
    val verified: Boolean get() = record?.stage == PlacementStage.VERIFIED_PUBLISHED && status == "Verified new save"
    fun text(): String = buildString {
        append(status)
        record?.let {
            append("\nStage: ${it.stage}")
            it.actualName?.let { name -> append("\n$name\n$OUTPUT_DIRECTORY") }
            it.uri?.let { uri -> append("\n$uri") }
            append("\n${it.size} bytes\nSHA-256: ${it.sha256}")
        }
        append("\nOriginal source untouched.")
    }
}

/** Serialized by the application singleton. Recovery NEVER writes, republishes or deletes media. */
internal class PlacementMachine(private val backend: PlacementBackend, private val journal: PlacementJournal) {
    @Synchronized fun place(candidate: SaveEditResult.VerifiedCandidate): PlacementOutcome {
        if (backend.apiLevel < 29) return PlacementOutcome("Export unavailable: Android 10+ required")
        val previous = try { journal.read() } catch (_: Exception) {
            return PlacementOutcome("UNCERTAIN: unreadable placement journal; export blocked")
        }
        if (previous != null && previous.stage != PlacementStage.VERIFIED_PUBLISHED) {
            val recovered = reconcile()
            // Recheck a retained receipt before replacing it with a separately requested export.
            if (previous.stage != PlacementStage.VERIFIED_RETAINED_PENDING ||
                recovered.record?.stage != PlacementStage.VERIFIED_RETAINED_PENDING) {
                return recovered.copy(status = "Previous export status (current edit NOT exported): ${recovered.status}")
            }
        }
        val identity = candidate.provenance.candidate()
        // A constant sanitized stem avoids persisting the original filename in the receipt.
        var record = PlacementRecord(UUID.randomUUID().toString(), null, identity.sha256(), identity.size(),
            "save-fieldkit-${identity.sha256().take(12)}.sav", null, PlacementStage.PREPARING)
        var publicationAttempted = false
        try {
            journal.store(record) // Covers insert/receipt gap: unknown orphan is never guessed/deleted.
            val uri = backend.createPending(record.expectedName)
            record = record.copy(uri = uri, stage = PlacementStage.CREATED_PENDING)
            journal.store(record)
            val created = checkedMetadata(record, pending = true)
            record = record.copy(actualName = created.displayName)
            journal.store(record)
            val bytes = candidate.candidateBytes
            try { backend.writeAndSyncNew(uri, bytes) } finally { bytes.fill(0) }
            verify(record, pending = true)
            record = record.copy(stage = PlacementStage.BYTES_VERIFIED_PENDING)
            journal.store(record)
            // Owner-only pending object remains protected, all writable descriptors closed.
            record = record.copy(stage = PlacementStage.PUBLICATION_ATTEMPTED)
            journal.store(record) // Must complete BEFORE the single publication update.
            publicationAttempted = true
            check(backend.publish(uri))
            verify(record, pending = false)
            record = record.copy(stage = PlacementStage.VERIFIED_PUBLISHED)
            journal.store(record)
            return PlacementOutcome("Verified new save", record)
        } catch (_: Exception) {
            record = record.copy(stage = if (publicationAttempted || record.uri == null ||
                record.stage == PlacementStage.PUBLICATION_ATTEMPTED || record.stage == PlacementStage.VERIFIED_PUBLISHED)
                PlacementStage.UNCERTAIN else PlacementStage.FAILED)
            try { journal.store(record) } catch (_: Exception) { /* Preserve last durable record. */ }
            return PlacementOutcome(if (record.stage == PlacementStage.UNCERTAIN)
                "UNCERTAIN: publication status requires reconciliation; no retry or cleanup"
                else "FAILED: pending output retained; publication refused", record)
        }
    }

    @Synchronized fun reconcile(): PlacementOutcome {
        if (backend.apiLevel < 29) return PlacementOutcome("Export unavailable: Android 10+ required")
        var record = try { journal.read() } catch (_: Exception) {
            return PlacementOutcome("UNCERTAIN: unreadable placement journal; export blocked")
        } ?: return PlacementOutcome("No previous export")
        try {
            check(record.uri != null)
            val metadata = backend.metadata(record.uri!!)
            if (metadata.pending) {
                verify(record, pending = true)
                if (record.actualName != null && record.stage in setOf(PlacementStage.CREATED_PENDING,
                        PlacementStage.BYTES_VERIFIED_PENDING, PlacementStage.FAILED,
                        PlacementStage.VERIFIED_RETAINED_PENDING)) {
                    // These durable stages prove no publication was attempted. Retain the exact
                    // object without publishing/deleting it; a later export gets a fresh Uri.
                    record = record.copy(stage = PlacementStage.VERIFIED_RETAINED_PENDING)
                    journal.store(record)
                    return PlacementOutcome("Verified pending output retained, not published. A new export is allowed.", record)
                }
                // A previously published object becoming pending is a changed outcome, not a receipt.
                if (record.stage == PlacementStage.VERIFIED_PUBLISHED) {
                    record = record.copy(stage = PlacementStage.UNCERTAIN)
                    journal.store(record)
                }
                // No pending publication retry in this slice, including after an interrupted write.
                return PlacementOutcome("Pending output retained; export blocked. No automatic publication.", record)
            }
            verify(record, pending = false)
            record = record.copy(actualName = metadata.displayName, stage = PlacementStage.VERIFIED_PUBLISHED)
            journal.store(record)
            return PlacementOutcome("Verified new save", record)
        } catch (_: Exception) {
            record = record.copy(stage = PlacementStage.UNCERTAIN)
            try { journal.store(record) } catch (_: Exception) { /* No mutation of output. */ }
            return PlacementOutcome("UNCERTAIN: missing, changed or unverifiable output; export blocked", record)
        }
    }

    private fun checkedMetadata(record: PlacementRecord, pending: Boolean): ObjectMetadata {
        val metadata = backend.metadata(checkNotNull(record.uri))
        check(metadata.uri == record.uri && metadata.owned && metadata.pending == pending)
        check(metadata.relativePath == OUTPUT_DIRECTORY && validOutputName(metadata.displayName))
        check(record.actualName == null || metadata.displayName == record.actualName)
        // MediaStore SIZE is an advisory index: null/zero/stale even after publication.
        // Only the exact reopened stream establishes byte count/EOF and SHA-256. Ignoring
        // index disagreement cannot admit different bytes or relax any binding check.
        return metadata
    }

    private fun verify(record: PlacementRecord, pending: Boolean) {
        checkedMetadata(record, pending)
        backend.openRead(checkNotNull(record.uri)).use { input ->
            check(hashExact(input, record.size) == record.sha256)
        }
        checkedMetadata(record, pending) // Bind both sides of the read to the same owned object.
    }
}

/** Bounded streaming read, including an EOF check; never retains another save snapshot. */
internal fun hashExact(input: InputStream, expectedSize: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var remaining = expectedSize
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        check(read >= 0)
        if (read == 0) {
            val single = input.read()
            check(single >= 0)
            digest.update(single.toByte())
            remaining--
        } else {
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }
    check(input.read() == -1)
    return digest.digest().joinToString("") { "%02x".format(it) }
}
