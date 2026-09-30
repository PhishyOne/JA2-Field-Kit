package com.phishtopia.ja2fieldkit.android.editing

import com.phishtopia.ja2fieldkit.core.*
import kotlin.test.*
import com.phishtopia.ja2fieldkit.android.importing.*
import com.phishtopia.ja2fieldkit.android.presentation.*
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class EditSessionTest {
    @Test fun snapshotCopiesClearsAndDoesNotAcceptUiPreconditions() {
        val (bytes, inspector) = SaveEditTransactionTest().androidFixture()
        val session = EditSession(bytes, inspector, SaveEditTransactionTest().androidEditor())
        val snapshotField = EditSession::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        val retained = snapshotField.get(session) as ByteArray
        assertNotSame(bytes, retained)
        assertContentEquals(bytes, retained)
        bytes.fill(0)
        val merc = assertNotNull(session.merc(7))
        val stat = assertIs<SaveEditRequest.SetHiredStat>(EditSession.operation(merc,
            EditChoice.Stat(HiredMercStat.MARKSMANSHIP, 91)))
        assertEquals(89, stat.expectedCurrent())
        assertEquals(91, stat.value())
        val item = assertIs<SaveEditRequest.SetSimpleItem>(EditSession.operation(merc,
            EditChoice.Inventory(7, SimpleItem.TOOLKIT, 90)))
        assertEquals(SaveEditRequest.ExpectedSlot(201, 1, 80), item.expected())
        assertEquals(203, item.itemId()); assertEquals(1, item.count()); assertEquals(90, item.status())
        assertEquals(80, merc.slots[7]?.status())
        assertIs<SaveEditResult.VerifiedCandidate>(session.generate(7, EditChoice.Inventory(7, SimpleItem.TOOLKIT, 90)))
        assertFails { (merc.slots as MutableMap)[7] = SaveEditRequest.ExpectedSlot(202, 1, 79) }
        val mapField = EditSession::class.java.getDeclaredField("mercs").apply { isAccessible = true }
        assertFails { (mapField.get(session) as MutableMap<*, *>).clear() }
        assertFails { (merc.stats as MutableMap)[HiredMercStat.MARKSMANSHIP] = 22 }
        session.clear()
        assertNull(session.merc(7)); assertNull(snapshotField.get(session))
        assertTrue(retained.all { it == 0.toByte() })
        assertIs<SaveEditResult.Failure>(session.generate(7, EditChoice.Stat(HiredMercStat.MARKSMANSHIP, 90)))
    }

    @Test fun onlyCurrentUniqueHiredV103MercsAreAvailableAndSessionIsSingleUse() {
        val (bytes, inspector) = SaveEditTransactionTest().androidFixture()
        val session = EditSession(bytes, inspector)
        assertNotNull(session.merc(7)); assertNull(session.merc(0))
        session.consume()
        assertNull(session.merc(7))
        assertIs<SaveEditResult.Failure>(session.generate(7, EditChoice.Stat(HiredMercStat.AGILITY, 90)))
        val v102 = bytes.copyOf().also { it[0] = 102 }
        assertIs<SaveInspectionV01Result.Success>(inspector.inspectV01(v102))
        assertNull(EditSession(v102, inspector).merc(7))
        for (kind in listOf("nonplayer", "vehicle", "missing", "duplicate")) {
            val (other, reader) = SaveEditTransactionTest().androidFixture(kind = kind)
            assertNull(EditSession(other, reader).merc(7), kind)
        }
        assertNull(EditSession(byteArrayOf()).merc(7))
        assertFails { EditSession(ByteArray(Ja2SaveEditor.MAX_SAVE_BYTES + 1)) }
    }

    @TestFactory fun profileAssertionsThroughActualEditorAndVerifier() = (7..10).flatMap { slot ->
        listOf(SimpleItem.FIRST_AID, SimpleItem.MEDICAL, SimpleItem.TOOLKIT).flatMap { item ->
            listOf(1, 80, 100).map { condition -> dynamicTest("slot $slot item ${item.itemId} condition $condition") {
                val fixture = SaveEditTransactionTest()
                val (bytes, inspector) = fixture.androidFixture(item.itemId, condition, slot)
                val session = EditSession(bytes, inspector, fixture.androidEditor())
                val merc = assertNotNull(session.merc(7))
                assertEquals(SaveEditRequest.ExpectedSlot(item.itemId, 1, condition), merc.slots[slot])
                val same = assertIs<SaveEditResult.VerifiedCandidate>(session.generate(7, EditChoice.Inventory(slot, item, condition)))
                assertContentEquals(bytes, same.candidateBytes)
                for (desired in listOf(1, 80, 100)) {
                    val replacement = SimpleItem.entries[1 + item.ordinal % 3]
                    val candidate = assertIs<SaveEditResult.VerifiedCandidate>(session.generate(7,
                        EditChoice.Inventory(slot, replacement, desired)))
                    val actual = inspector.parseBuild041202NormalNonLinuxProfiles(candidate.candidateBytes)[7].inventory[slot]
                    assertEquals(replacement.itemId, actual.itemId); assertEquals(desired, actual.status)
                    assertEquals(1, actual.count)
                    assertEquals(condition, merc.slots[slot]?.status())
                }
                val cleared = assertIs<SaveEditResult.VerifiedCandidate>(session.generate(7, EditChoice.Inventory(slot, SimpleItem.CLEAR, 1)))
                val actual = inspector.parseBuild041202NormalNonLinuxProfiles(cleared.candidateBytes)[7].inventory[slot]
                assertEquals(com.phishtopia.ja2fieldkit.core.model.ProfileInventorySlot(0, 0, 0), actual)
            } }
        }
    }

    @TestFactory fun emptySlotsClearAndAdd() = (7..10).map { slot -> dynamicTest("empty slot $slot") {
        val fixture = SaveEditTransactionTest()
        val (bytes, inspector) = fixture.androidFixture(item = 0, slot = slot)
        val session = EditSession(bytes, inspector, fixture.androidEditor())
        assertEquals(SaveEditRequest.ExpectedSlot(0, 0, 0), session.merc(7)?.slots?.get(slot))
        assertContentEquals(bytes, assertIs<SaveEditResult.VerifiedCandidate>(session.generate(7,
            EditChoice.Inventory(slot, SimpleItem.CLEAR, 1))).candidateBytes)
        for (item in SimpleItem.entries.drop(1)) for (condition in listOf(1, 80, 100)) {
            assertIs<SaveEditResult.VerifiedCandidate>(session.generate(7, EditChoice.Inventory(slot, item, condition)))
        }
    } }

    @Test fun allTenStatsPassTheActualCoreVerifier() {
        val fixture = SaveEditTransactionTest()
        val (bytes, inspector) = fixture.androidFixture()
        val session = EditSession(bytes, inspector, fixture.androidEditor())
        for (stat in HiredMercStat.entries) assertIs<SaveEditResult.VerifiedCandidate>(
            session.generate(7, EditChoice.Stat(stat, stat.maximum())))
    }

    @Test fun profileLiveDisagreementNeverSelectsAWinnerOrNormalizesInvalidFacts() {
        val fixture = SaveEditTransactionTest()
        for (profile in listOf(false, true)) for (field in listOf("item", "count", "status")) {
            val (bytes, inspector) = fixture.androidDisagreement(profile, field)
            val session = EditSession(bytes, inspector, fixture.androidEditor())
            val expected = assertNotNull(session.merc(7)?.slots?.get(7))
            val actual = inspector.parseBuild041202NormalNonLinuxProfiles(bytes)[7].inventory[7]
            assertEquals(SaveEditRequest.ExpectedSlot(actual.itemId, actual.count, actual.status), expected)
            val failure = assertIs<SaveEditResult.Failure>(session.generate(7, EditChoice.Inventory(7, SimpleItem.CLEAR, 1)))
            assertEquals(if (profile && field == "count") SaveEditResult.Reason.INVALID_REQUEST
                else SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH, failure.reason())
        }
        for (condition in listOf(0, 101, 255)) {
            val (bytes, inspector) = fixture.androidFixture(status = condition)
            val session = EditSession(bytes, inspector, fixture.androidEditor())
            assertEquals(condition, session.merc(7)?.slots?.get(7)?.status())
            val failure = assertIs<SaveEditResult.Failure>(session.generate(7, EditChoice.Inventory(7, SimpleItem.CLEAR, 1)))
            assertEquals(SaveEditResult.Stage.STRUCTURE, failure.stage())
            assertEquals(SaveEditResult.Reason.INVALID_REQUEST, failure.reason())
        }
        val missing = EditMerc(7, emptyMap(), emptyMap())
        assertNull(EditSession.operation(missing, EditChoice.Inventory(7, SimpleItem.TOOLKIT, 80)))
        assertNull(EditSession.operation(missing, EditChoice.Stat(HiredMercStat.AGILITY, 80)))
    }

    @Test fun wrongValidConditionAndMutatedSourceAreRejectedByCore() {
        val fixture = SaveEditTransactionTest()
        val (bytes, inspector) = fixture.androidFixture()
        val merc = assertNotNull(EditSession(bytes, inspector).merc(7))
        val wrong = merc.copy(slots = mapOf(7 to SaveEditRequest.ExpectedSlot(201, 1, 79)))
        val identity = SaveEditRequest.SourceIdentity(bytes.size, hashExact(bytes.inputStream(), bytes.size))
        val operation = assertNotNull(EditSession.operation(wrong, EditChoice.Inventory(7, SimpleItem.TOOLKIT, 100)))
        val failure = assertIs<SaveEditResult.Failure>(fixture.androidEditor().edit(bytes, SaveEditRequest(identity, operation)))
        assertEquals(SaveEditResult.Stage.PRECONDITION, failure.stage())
        assertEquals(SaveEditResult.Reason.EXPECTED_CURRENT_MISMATCH, failure.reason())
        bytes[bytes.lastIndex]++
        assertEquals(SaveEditResult.Reason.SOURCE_IDENTITY_MISMATCH,
            assertIs<SaveEditResult.Failure>(fixture.androidEditor().edit(bytes, SaveEditRequest(identity, operation))).reason())
    }

    @Test fun failedEditorPreparationPreservesSuccessfulReadOnlyPresentationAndErasesImport() {
        val (bytes, inspector) = SaveEditTransactionTest().androidFixture()
        val imported = SaveImporter().import(bytes.inputStream(), ProviderSaveMetadata("synthetic.sav", null, null, SourceProvenance.DOCUMENT_PICKER))
        var retained: ByteArray? = null
        val displayed = imported.inspectWith(inspector) { inspection, live ->
            InspectionPresentationMapper.map(imported.provenance, inspection, live)
        }.also {
            assertNull(imported.takeEditSession { snapshot -> retained = snapshot; error("Profile parser refused") })
        }
        assertIs<InspectionScreenState.Success>(displayed)
        assertTrue(assertNotNull(retained).all { it == 0.toByte() })
    }

    @Test fun closedUiChoicesAndRanges() {
        val (bytes, inspector) = SaveEditTransactionTest().androidFixture()
        val merc = assertNotNull(EditSession(bytes, inspector).merc(7))
        assertEquals(10, merc.stats.size)
        for (stat in HiredMercStat.entries) {
            assertNotNull(EditSession.operation(merc, EditChoice.Stat(stat, stat.minimum())))
            assertNotNull(EditSession.operation(merc, EditChoice.Stat(stat, stat.maximum())))
            assertNull(EditSession.operation(merc, EditChoice.Stat(stat, stat.maximum() + 1)))
        }
        assertEquals(setOf(0, 201, 202, 203), SimpleItem.entries.map { it.itemId }.toSet())
        for (slot in 7..10) for (item in SimpleItem.entries) {
            assertNotNull(EditSession.operation(merc, EditChoice.Inventory(slot, item, 100)))
        }
        assertNull(EditSession.operation(merc, EditChoice.Inventory(6, SimpleItem.CLEAR, 100)))
        assertNull(EditSession.operation(merc, EditChoice.Inventory(7, SimpleItem.TOOLKIT, 101)))
        for (api in listOf(23, 28, 29, 37)) assertFalse(EditSession.available(false, api))
        assertFalse(EditSession.available(true, 28)); assertTrue(EditSession.available(true, 29))
    }
}
