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
            assertEquals(this.uri, uri)
            event(if (pending) "query-pending" else "query-published")
            return ObjectMetadata(binding, name, bytes.size.toLong(), pending, owned, path)
        }
        override fun openRead(uri: String): ByteArrayInputStream {
            assertEquals(this.uri, uri)
            event(if (pending) "read-pending" else "read-published")
            return ByteArrayInputStream(if (badPending && pending || badPublished && !pending) bytes + 1 else bytes)
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
        for (stage in PlacementStage.entries.filter { it !in listOf(PlacementStage.FAILED, PlacementStage.UNCERTAIN) }) {
            val backend = Backend(); val journal = Journal().also { it.failAt = stage }
            val result = PlacementMachine(backend, journal).place(candidate)
            assertFalse(result.verified)
            if (stage != PlacementStage.VERIFIED_PUBLISHED) assertFalse("publish-same-uri" in backend.events)
            else assertEquals(PlacementStage.UNCERTAIN, result.record?.stage)
        }
    }

    @Test fun processCrashAtEveryDurableTransitionReconcilesWithoutMutation() {
        for (stage in PlacementStage.entries.filter { it !in listOf(PlacementStage.FAILED, PlacementStage.UNCERTAIN) }) {
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
    companion object {
        private val mutationEvents = setOf("insert-pending", "write", "flush", "fsync", "publish-same-uri", "published")
    }
}
