package com.phishtopia.ja2fieldkit.android.editing

import java.nio.file.Files
import kotlin.test.*

class FilePlacementJournalTest {
    private fun record() = PlacementRecord("00000000-0000-0000-0000-000000000000",
        "content://media/external_primary/downloads/12", "a".repeat(64), 123,
        "save-fieldkit-aaaaaaaaaaaa.sav", "save-fieldkit-aaaaaaaaaaaa (1).sav", PlacementStage.CREATED_PENDING)

    @Test fun roundTripEveryStageBoundedWithOnlyAllowedFields() {
        assertEquals(setOf("operationId", "uri", "sha256", "size", "expectedName", "actualName", "stage"),
            PlacementRecord::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet())
        for (stage in PlacementStage.entries) {
            val record = record().copy(stage = stage)
            val encoded = FilePlacementJournal.encode(record)
            assertTrue(encoded.size <= FilePlacementJournal.MAX_BYTES)
            assertEquals(record, FilePlacementJournal.decode(encoded))
            assertFails { FilePlacementJournal.decode(encoded + 0) }
            assertFails { FilePlacementJournal.decode(encoded.copyOf(encoded.size - 1)) }
        }
        assertFails { FilePlacementJournal.decode(ByteArray(2049)) }
        assertFails { record().copy(uri = "content://source/private/1") }
        assertFails { record().copy(actualName = "x".repeat(181) + ".sav") }
    }

    @Test fun atomicReceiptAndTemporaryInterruptionFailClosed() {
        val directory = Files.createTempDirectory("placement-journal-test").toFile()
        try {
            var syncs = 0
            val journal = FilePlacementJournal(directory) { syncs++; assertEquals(directory, it) }
            assertNull(journal.read())
            val temporary = directory.resolve("placement.receipt.tmp")
            temporary.writeBytes(byteArrayOf(0))
            assertFails { journal.read() } // Insert gap/first interrupted persistence cannot look empty.
            journal.store(record())
            assertEquals(1, syncs); assertFalse(temporary.exists()); assertEquals(record(), journal.read())
            temporary.writeBytes(byteArrayOf(0))
            assertEquals(record(), journal.read()) // Last committed record wins over incomplete update.
            journal.store(record().copy(stage = PlacementStage.PUBLICATION_ATTEMPTED))
            assertEquals(2, syncs)
            assertEquals(PlacementStage.PUBLICATION_ATTEMPTED, journal.read()?.stage)
            directory.resolve("placement.receipt").writeBytes(ByteArray(2049))
            assertFails { journal.read() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedDirectorySyncCannotClaimDurableOutcome() {
        val directory = Files.createTempDirectory("placement-journal-sync").toFile()
        try {
            val journal = FilePlacementJournal(directory) { throw IllegalStateException() }
            assertFails { journal.store(record()) }
            assertEquals(record(), journal.read()) // Atomic record remains available for reconciliation.
        } finally { directory.deleteRecursively() }
    }
}
