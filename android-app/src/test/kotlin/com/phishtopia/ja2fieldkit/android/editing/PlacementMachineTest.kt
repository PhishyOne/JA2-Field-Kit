package com.phishtopia.ja2fieldkit.android.editing

import com.phishtopia.ja2fieldkit.core.SaveEditTransactionTest
import com.phishtopia.ja2fieldkit.core.SaveEditResult
import java.io.ByteArrayInputStream
import kotlin.test.*

class PlacementMachineTest {
    private val candidate by lazy { SaveEditTransactionTest().androidCandidate() }
    private class Crash : Error()
    private class Journal : PlacementJournal {
        var record: PlacementRecord? = null
        val stages = mutableListOf<PlacementStage>()
        var crashAt: PlacementStage? = null
        var failAt: PlacementStage? = null
        override fun read() = record
        override fun store(record: PlacementRecord) {
            if (record.stage == failAt) throw IllegalStateException()
            this.record = record
            stages += record.stage
            if (record.stage == crashAt) throw Crash()
        }
    }
    private class Backend(override val apiLevel: Int = 29, id: Int = 27) : PlacementBackend {
        val uri = "content://media/external_primary/downloads/$id"
        val events = mutableListOf<String>()
        var bytes = byteArrayOf()
        // Independently programmable index, including deferred provider scanning.
        var indexedSize: () -> Long? = { bytes.size.toLong() }
        var readBytes: (ByteArray) -> ByteArray = { it }
        var prior: Backend? = null
        var pending = true
        var name = "save-fieldkit-provider-renamed.sav"
        var owned = true
        var binding = uri
        var path = OUTPUT_DIRECTORY
        var badPending = false
        var badPublished = false
        var updateFails = false
        var updateCommitsThenThrows = false
        var crashAfter: String? = null
        var failAfter: String? = null
        var onPublish: () -> Unit = {}
        val observedByOtherApps = mutableListOf<ByteArray?>()
        fun competingWrite(): Boolean = !pending // Platform ownership boundary modeled here.
        private fun event(value: String) {
            events += value
            observedByOtherApps += if (pending) null else bytes.copyOf()
            if (crashAfter == value) throw Crash()
            if (failAfter == value) throw IllegalStateException()
        }
        override fun createPending(displayName: String): String {
            assertTrue(displayName.matches(Regex("save-fieldkit-[a-f0-9]{12}\\.sav")))
            event("insert-pending")
            return uri
        }
        override fun writeAndSyncNew(uri: String, bytes: ByteArray) {
            assertEquals(this.uri, uri)
            assertTrue(pending)
            assertEquals(1, events.count { it == "insert-pending" })
            event("write")
            this.bytes = bytes.copyOf()
            event("flush")
            event("fsync")
            event("close-writer")
        }
        override fun metadata(uri: String): ObjectMetadata {
            if (uri != this.uri) return checkNotNull(prior).metadata(uri)
            assertEquals(this.uri, uri)
            event(if (pending) "query-pending" else "query-published")
            return ObjectMetadata(binding, name, indexedSize(), pending, owned, path)
        }
        override fun openRead(uri: String): ByteArrayInputStream {
            if (uri != this.uri) return checkNotNull(prior).openRead(uri)
            assertEquals(this.uri, uri)
            event(if (pending) "read-pending" else "read-published")
            return ByteArrayInputStream(readBytes(if (badPending && pending || badPublished && !pending) bytes + 1 else bytes))
        }
        override fun publish(uri: String): Boolean {
            assertEquals(this.uri, uri)
            onPublish()
            event("publish-same-uri")
            if (updateFails) return false
            pending = false
            if (updateCommitsThenThrows) throw IllegalStateException()
            event("published")
            return true
        }
    }

    @Test fun onlyVerifiedTypeCanEnterAndOldApiRefusesBeforeAnyWork() {
        val place = PlacementMachine::class.java.getDeclaredMethod("place", SaveEditResult.VerifiedCandidate::class.java)
        assertFalse(place.parameterTypes.single().isAssignableFrom(SaveEditResult.Failure::class.java))
        assertTrue(SaveEditResult.VerifiedCandidate::class.java.isSealed)
        val backend = Backend(28); val journal = Journal()
        assertFalse(PlacementMachine(backend, journal).place(candidate).verified)
        assertTrue(backend.events.isEmpty()); assertNull(journal.record)
    }

    @Test fun successfulStateOrderSyncReadbacksAndActualProviderName() {
        val backend = Backend(); val journal = Journal()
        backend.onPublish = {
            assertEquals(PlacementStage.PUBLICATION_ATTEMPTED, journal.record?.stage)
            assertFalse(backend.competingWrite()) // Pending protection holds through commit handoff.
        }
        val result = PlacementMachine(backend, journal).place(candidate)
        assertTrue(result.verified)
        assertEquals(backend.name, result.record?.actualName)
        assertEquals(backend.uri, result.record?.uri)
        assertEquals(candidate.provenance.candidate().sha256(), result.record?.sha256)
        assertEquals(listOf(PlacementStage.PREPARING, PlacementStage.CREATED_PENDING,
            PlacementStage.CREATED_PENDING, PlacementStage.BYTES_VERIFIED_PENDING,
            PlacementStage.PUBLICATION_ATTEMPTED, PlacementStage.VERIFIED_PUBLISHED), journal.stages)
        val events = backend.events
        assertTrue(events.indexOf("fsync") < events.indexOf("close-writer"))
        assertTrue(events.indexOf("close-writer") < events.indexOf("read-pending"))
        assertTrue(events.indexOf("read-pending") < events.indexOf("publish-same-uri"))
        assertTrue(events.indexOf("publish-same-uri") < events.indexOf("read-published"))
        assertEquals(1, events.count { it == "publish-same-uri" })
        assertContentEquals(candidate.candidateBytes, backend.bytes)
        backend.observedByOtherApps.filterNotNull().forEach { assertContentEquals(candidate.candidateBytes, it) }
        assertTrue(backend.observedByOtherApps.any { it == null })
    }

    @Test fun pendingTamperOrBindingFailureNeverPublishes() {
        for (fault in listOf<(Backend) -> Unit>({ it.badPending = true }, { it.owned = false },
            { it.binding = "content://media/external_primary/downloads/99" }, { it.path = "Download/elsewhere/" },
            { it.name = "../bad.sav" }, { it.failAfter = "fsync" })) {
            val backend = Backend().also(fault); val journal = Journal()
            assertFalse(PlacementMachine(backend, journal).place(candidate).verified)
            assertFalse("publish-same-uri" in backend.events)
            assertNotNull(journal.record)
        }
    }

    @Test fun publicationFailureAndPostPublicationTamperStayUncertainAndNeverRetry() {
        for (fault in listOf<(Backend) -> Unit>({ it.updateFails = true }, { it.badPublished = true },
            { it.updateCommitsThenThrows = true })) {
            val backend = Backend().also(fault); val journal = Journal()
            val machine = PlacementMachine(backend, journal)
            assertEquals(PlacementStage.UNCERTAIN, machine.place(candidate).record?.stage)
            val before = backend.events.count { it == "publish-same-uri" }
            machine.reconcile()
            assertEquals(before, backend.events.count { it == "publish-same-uri" })
            if (backend.updateCommitsThenThrows) assertEquals(PlacementStage.VERIFIED_PUBLISHED, journal.record?.stage)
            else {
                machine.place(candidate)
                assertEquals(1, backend.events.count { it == "insert-pending" })
            }
        }
    }

    @Test fun journalFailuresBeforePublicationPreventItAndAfterPublicationRemainUncertain() {
        for (stage in placementStages) {
            val backend = Backend(); val journal = Journal().also { it.failAt = stage }
            val result = PlacementMachine(backend, journal).place(candidate)
            assertFalse(result.verified)
            if (stage != PlacementStage.VERIFIED_PUBLISHED) assertFalse("publish-same-uri" in backend.events)
            else assertEquals(PlacementStage.UNCERTAIN, result.record?.stage)
        }
    }

    @Test fun processCrashAtEveryDurableTransitionReconcilesWithoutMutation() {
        for (stage in placementStages) {
            val backend = Backend(); val journal = Journal().also { it.crashAt = stage }
            assertFailsWith<Crash> { PlacementMachine(backend, journal).place(candidate) }
            journal.crashAt = null
            val mutations = backend.events.filter { it in mutationEvents }
            val recovered = PlacementMachine(backend, journal).reconcile()
            assertEquals(mutations, backend.events.filter { it in mutationEvents })
            assertEquals(stage == PlacementStage.VERIFIED_PUBLISHED, recovered.verified)
            assertNotNull(journal.record)
        }
    }

    @Test fun crashInsideBackendIncludingInsertReceiptGapAndPublishReceiptGap() {
        for (event in listOf("insert-pending", "write", "flush", "fsync", "close-writer", "read-pending",
            "publish-same-uri", "published", "read-published")) {
            val backend = Backend().also { it.crashAfter = event }; val journal = Journal()
            assertFailsWith<Crash> { PlacementMachine(backend, journal).place(candidate) }
            backend.crashAfter = null
            val before = backend.events.filter { it in mutationEvents }
            val result = PlacementMachine(backend, journal).reconcile()
            assertEquals(before, backend.events.filter { it in mutationEvents })
            assertEquals(!backend.pending, result.verified)
            if (event == "insert-pending") assertNull(journal.record?.uri)
        }
    }

    @Test fun duplicateNamesDoNotSelectExistingObjectAndSizeOrHashMismatchRefuses() {
        // Separate provider-assigned identities are the authority; no name lookup exists.
        val first = Backend(id = 27); val second = Backend(id = 28)
        val one = PlacementMachine(first, Journal()).place(candidate)
        val two = PlacementMachine(second, Journal()).place(candidate)
        assertEquals(one.record?.actualName, two.record?.actualName)
        assertNotEquals(one.record?.uri, two.record?.uri)
        assertContentEquals(candidate.candidateBytes, first.bytes)
        val backend = Backend(); val journal = Journal()
        val machine = PlacementMachine(backend, journal)
        assertTrue(machine.place(candidate).verified)
        backend.bytes[0] = (backend.bytes[0].toInt() xor 1).toByte()
        assertEquals(PlacementStage.UNCERTAIN, machine.reconcile().record?.stage)
        assertFalse(machine.place(candidate).verified)
        assertEquals(1, backend.events.count { it == "insert-pending" })
    }

    @Test fun recoveringAnEarlierSuccessNeverClaimsCurrentCandidateWasExported() {
        val backend = Backend().also { it.updateCommitsThenThrows = true }; val journal = Journal()
        val machine = PlacementMachine(backend, journal)
        assertFalse(machine.place(candidate).verified)
        val recoveredDuringNewRequest = machine.place(candidate)
        assertFalse(recoveredDuringNewRequest.verified)
        assertTrue(recoveredDuringNewRequest.status.contains("current edit NOT exported"))
        assertEquals(PlacementStage.VERIFIED_PUBLISHED, journal.record?.stage)
        assertEquals(1, backend.events.count { it == "insert-pending" })
    }

    @Test fun publishedObjectReturningToPendingBlocksAnotherExport() {
        val backend = Backend(); val journal = Journal(); val machine = PlacementMachine(backend, journal)
        assertTrue(machine.place(candidate).verified)
        backend.pending = true
        assertFalse(machine.reconcile().verified)
        assertEquals(PlacementStage.UNCERTAIN, journal.record?.stage)
        assertFalse(machine.place(candidate).verified)
        assertEquals(1, backend.events.count { it == "insert-pending" })
    }

    @Test fun streamingHashChecksEofShortLongAndSameSizeDrift() {
        val bytes = byteArrayOf(1, 2, 3)
        val hash = hashExact(bytes.inputStream(), 3)
        assertFails { hashExact(bytes.inputStream(), 2) }
        assertFails { hashExact(bytes.inputStream(), 4) }
        assertNotEquals(hash, hashExact(byteArrayOf(1, 2, 4).inputStream(), 3))
    }

    private fun assertIndexDoesNotVetoExactBytes(index: Backend.() -> Long?) {
        val backend = Backend().also { it.indexedSize = { it.index() } }
        val journal = Journal()
        backend.onPublish = {
            assertTrue(PlacementStage.BYTES_VERIFIED_PENDING in journal.stages)
            assertEquals(PlacementStage.PUBLICATION_ATTEMPTED, journal.record?.stage)
            assertTrue("read-pending" in backend.events)
        }
        val result = PlacementMachine(backend, journal).place(candidate)
        assertTrue(result.verified)
        assertEquals(backend.uri, result.record?.uri)
        assertContentEquals(candidate.candidateBytes, backend.bytes)
        assertTrue("read-published" in backend.events)
        assertEquals(1, backend.events.count { it == "publish-same-uri" })
    }

    @Test fun pendingNullImmediatelyAfterInsertAllowsWriteAndPublication() =
        assertIndexDoesNotVetoExactBytes { if (pending) null else bytes.size.toLong() }

    @Test fun pendingZeroAfterFullWriteAllowsPublication() =
        assertIndexDoesNotVetoExactBytes { if (pending) 0L else bytes.size.toLong() }

    @Test fun pendingStaleNonzeroAllowsPublication() =
        assertIndexDoesNotVetoExactBytes { if (pending) 7L else bytes.size.toLong() }

    @Test fun publishedNullIndexAllowsExactReadbackSuccess() =
        assertIndexDoesNotVetoExactBytes { if (pending) bytes.size.toLong() else null }

    @Test fun publishedStaleIndexAllowsExactReadbackSuccess() =
        assertIndexDoesNotVetoExactBytes { if (pending) bytes.size.toLong() else 7L }

    @Test fun android10DeferredScanReproductionAndSynchronousControl() {
        // Android-10-like provider: insert leaves SIZE null/0, writing/closing does not
        // update it; scanning at publication finally indexes the real content length.
        for (initialSize in listOf(null, 0L)) {
            val backend = Backend(); val journal = Journal()
            var index = initialSize
            backend.indexedSize = { index }
            backend.onPublish = {
                assertEquals(initialSize, backend.metadata(backend.uri).indexedSize)
                assertContentEquals(candidate.candidateBytes, backend.bytes)
                assertTrue("close-writer" in backend.events && "read-pending" in backend.events)
                index = backend.bytes.size.toLong() // Deferred scan, not write-time accounting.
            }
            assertTrue(PlacementMachine(backend, journal).place(candidate).verified)
        }
        assertTrue(PlacementMachine(Backend(), Journal()).place(candidate).verified)
    }

    private fun assertStreamMismatchBlocks(transform: (ByteArray) -> ByteArray) {
        val backend = Backend().also {
            it.indexedSize = { candidate.provenance.candidate().size().toLong() }
            it.readBytes = transform
        }
        val journal = Journal(); val machine = PlacementMachine(backend, journal)
        assertEquals(PlacementStage.FAILED, machine.place(candidate).record?.stage)
        assertTrue("read-pending" in backend.events)
        assertEquals(PlacementStage.UNCERTAIN, machine.reconcile().record?.stage)
        assertFalse(machine.place(candidate).verified)
        assertFalse("publish-same-uri" in backend.events)
        assertEquals(1, backend.events.count { it == "insert-pending" })
    }

    @Test fun shorterStreamRefusesEvenWhenIndexClaimsCandidateSize() =
        assertStreamMismatchBlocks { it.copyOf(it.size - 1) }

    @Test fun longerStreamRefusesEvenWhenIndexClaimsCandidateSize() =
        assertStreamMismatchBlocks { it + 0 }

    @Test fun wrongHashAtCorrectLengthRefusesEvenWhenIndexClaimsCandidateSize() =
        assertStreamMismatchBlocks { it.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() } }

    @Test fun publishedStreamMismatchRemainsUncertainWithStaleOrCorrectIndex() {
        for (index in listOf(null, 0L, candidate.provenance.candidate().size().toLong())) {
            for (transform in listOf<(ByteArray) -> ByteArray>(
                { it.copyOf(it.size - 1) }, { it + 0 },
                { it.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() } })) {
                val backend = Backend().also {
                    it.indexedSize = { index }
                    it.onPublish = { it.readBytes = transform }
                }
                val journal = Journal(); val machine = PlacementMachine(backend, journal)
                assertEquals(PlacementStage.UNCERTAIN, machine.place(candidate).record?.stage)
                assertEquals(PlacementStage.UNCERTAIN, machine.reconcile().record?.stage)
                assertFalse(machine.place(candidate).verified)
                assertEquals(1, backend.events.count { it == "publish-same-uri" })
                assertEquals(1, backend.events.count { it == "insert-pending" })
            }
        }
    }

    private fun pendingReceipt(backend: Backend, journal: Journal, stage: PlacementStage) {
        backend.crashAfter = "close-writer"
        assertFailsWith<Crash> { PlacementMachine(backend, journal).place(candidate) }
        backend.crashAfter = null
        // Models old FAILED receipts (SIZE veto after writing), and durable pre-publish states.
        journal.record = checkNotNull(journal.record).copy(stage = stage)
    }

    @Test fun exactPendingReceiptWithNullOrZeroIndexReconcilesAndAllowsSeparateNewExport() {
        for (index in listOf(null, 0L)) {
            for (stage in listOf(PlacementStage.CREATED_PENDING, PlacementStage.BYTES_VERIFIED_PENDING, PlacementStage.FAILED)) {
                val old = Backend().also { it.indexedSize = { index } }; val journal = Journal()
                pendingReceipt(old, journal, stage)
                val before = old.events.filter { it in mutationEvents }
                val result = PlacementMachine(old, journal).reconcile()
                assertFalse(result.verified) // Retained is never a published-success receipt.
                assertEquals(PlacementStage.VERIFIED_RETAINED_PENDING, result.record?.stage)
                assertEquals(before, old.events.filter { it in mutationEvents })
                val fresh = Backend(id = 28).also { it.prior = old }
                val newResult = PlacementMachine(fresh, journal).place(candidate)
                assertTrue(newResult.verified)
                assertEquals(fresh.uri, newResult.record?.uri)
                assertTrue(old.pending)
                assertContentEquals(candidate.candidateBytes, old.bytes)
                assertEquals(before, old.events.filter { it in mutationEvents })
            }
        }
    }

    @Test fun recoveryDuringNewRequestDoesNotClaimTheCurrentEditWasExported() {
        val backend = Backend().also { it.indexedSize = { 0L } }; val journal = Journal()
        pendingReceipt(backend, journal, PlacementStage.FAILED)
        val result = PlacementMachine(backend, journal).place(candidate)
        assertFalse(result.verified)
        assertTrue(result.status.contains("current edit NOT exported"))
        assertEquals(PlacementStage.VERIFIED_RETAINED_PENDING, journal.record?.stage)
        assertEquals(1, backend.events.count { it == "insert-pending" })
        assertFalse("publish-same-uri" in backend.events)
    }

    @Test fun retainedPendingMustStillMatchBeforeSubsequentExport() {
        for (fault in listOf<(Backend) -> Unit>({ it.bytes = it.bytes + 0 }, { it.owned = false },
            { it.name = "changed.sav" }, { it.binding = "content://media/external_primary/downloads/99" })) {
            val old = Backend(); val journal = Journal()
            pendingReceipt(old, journal, PlacementStage.FAILED)
            PlacementMachine(old, journal).reconcile()
            fault(old)
            val fresh = Backend(id = 28).also { it.prior = old }
            assertFalse(PlacementMachine(fresh, journal).place(candidate).verified)
            assertEquals(PlacementStage.UNCERTAIN, journal.record?.stage)
            assertTrue(fresh.events.isEmpty())
            assertFalse("publish-same-uri" in old.events)
        }
    }

    @Test fun nullOrZeroIndexNeverResolvesPublicationUncertaintyOrMissingWrite() {
        for (index in listOf(null, 0L)) {
            for (stage in listOf(PlacementStage.PUBLICATION_ATTEMPTED, PlacementStage.UNCERTAIN,
                PlacementStage.VERIFIED_PUBLISHED, PlacementStage.PREPARING)) {
                val backend = Backend().also { it.indexedSize = { index } }; val journal = Journal()
                pendingReceipt(backend, journal, stage)
                assertFalse(PlacementMachine(backend, journal).reconcile().verified)
                assertNotEquals(PlacementStage.VERIFIED_RETAINED_PENDING, journal.record?.stage)
                assertFalse(PlacementMachine(backend, journal).place(candidate).verified)
                assertFalse("publish-same-uri" in backend.events)
                assertEquals(1, backend.events.count { it == "insert-pending" })
            }
            val backend = Backend().also { it.indexedSize = { index } }; val journal = Journal()
            pendingReceipt(backend, journal, PlacementStage.FAILED)
            backend.bytes = byteArrayOf() // Old null-SIZE rejection before any write cannot be salvaged.
            assertEquals(PlacementStage.UNCERTAIN, PlacementMachine(backend, journal).reconcile().record?.stage)
            assertFalse(PlacementMachine(backend, journal).place(candidate).verified)
            assertFalse("publish-same-uri" in backend.events)
            assertEquals(1, backend.events.count { it == "insert-pending" })
        }
    }

    @Test fun retainedReceiptMustPersistBeforeNewExportsAreAllowed() {
        val backend = Backend(); val journal = Journal()
        pendingReceipt(backend, journal, PlacementStage.FAILED)
        journal.failAt = PlacementStage.VERIFIED_RETAINED_PENDING
        assertEquals(PlacementStage.UNCERTAIN, PlacementMachine(backend, journal).reconcile().record?.stage)
        assertFalse(PlacementMachine(backend, journal).place(candidate).verified)
        assertFalse("publish-same-uri" in backend.events)
        assertEquals(1, backend.events.count { it == "insert-pending" })
    }

    @Test fun crashAfterRetainedReceiptPersistenceRechecksWithoutMediaMutation() {
        val backend = Backend(); val journal = Journal()
        pendingReceipt(backend, journal, PlacementStage.FAILED)
        journal.crashAt = PlacementStage.VERIFIED_RETAINED_PENDING
        val before = backend.events.filter { it in mutationEvents }
        assertFailsWith<Crash> { PlacementMachine(backend, journal).reconcile() }
        journal.crashAt = null
        val result = PlacementMachine(backend, journal).reconcile()
        assertEquals(PlacementStage.VERIFIED_RETAINED_PENDING, result.record?.stage)
        assertFalse(result.verified)
        assertEquals(before, backend.events.filter { it in mutationEvents })
    }
    companion object {
        private val placementStages = PlacementStage.entries.filter {
            it !in listOf(PlacementStage.FAILED, PlacementStage.UNCERTAIN, PlacementStage.VERIFIED_RETAINED_PENDING)
        }
        private val mutationEvents = setOf("insert-pending", "write", "flush", "fsync", "publish-same-uri", "published")
    }
}
