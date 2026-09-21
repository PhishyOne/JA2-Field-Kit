package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.RotationDigestOracle
import java.lang.reflect.Modifier
import kotlin.test.*

class MarksmanshipAdmissionTest {
    @Test
    fun scalarAndOversizeAdmissionPrecedesAllProportionalWork() {
        val work = mutableListOf<EditWork>()
        val editor = privilegedEditor(
            RotationDigestOracle { error("Must not detect") }, observe = work::add,
        )
        val valid = MarksmanshipEditRequest(0, 0, 0)
        fun check(bytes: ByteArray, request: MarksmanshipEditRequest, reason: MarksmanshipEditReason) {
            val result = assertIs<MarksmanshipEditResult.Failure>(editor.edit(bytes, request))
            assertEquals(MarksmanshipEditStage.STRUCTURE, result.stage)
            assertEquals(reason, result.reason)
            assertTrue(work.isEmpty())
        }
        val maximum = ByteArray(Ja2MarksmanshipEditor.MAX_SAVE_BYTES)
        for (id in listOf(Int.MIN_VALUE, -1, 170, Int.MAX_VALUE)) {
            check(maximum, valid.copy(profileId = id), MarksmanshipEditReason.INVALID_PROFILE_ID)
        }
        for (value in listOf(Int.MIN_VALUE, -129, 128, Int.MAX_VALUE)) {
            check(maximum, valid.copy(newMarksmanship = value), MarksmanshipEditReason.INVALID_MARKSMANSHIP)
            check(maximum, valid.copy(expectedCurrentMarksmanship = value), MarksmanshipEditReason.INVALID_MARKSMANSHIP)
        }
        check(ByteArray(maximum.size + 1), valid, MarksmanshipEditReason.SOURCE_TOO_LARGE)
        // Size admission must win even when scalar admission would also fail.
        check(ByteArray(maximum.size + 1), valid.copy(profileId = -1), MarksmanshipEditReason.SOURCE_TOO_LARGE)
        val admitted = assertIs<MarksmanshipEditResult.Failure>(editor.edit(maximum, valid))
        assertEquals(MarksmanshipEditStage.SOURCE_PARSE, admitted.stage)
        assertEquals(listOf(EditWork.SOURCE_SNAPSHOT, EditWork.SOURCE_HASH, EditWork.SOURCE_INSPECTION), work)
    }

    @Test
    fun fixedWriterExactLimitOverflowPredictedLimitAndTerminalFailure() {
        val limit = Ja2MarksmanshipEditor.MAX_SAVE_BYTES
        val writer = BoundedEditCandidateWriter(limit, limit)
        val input = ByteArray(limit)
        writer.append(input)
        assertContentEquals(input, writer.finish())
        assertFailsWith<CandidateBoundException> { writer.append(byteArrayOf(1)) }
        assertFailsWith<CandidateBoundException> { writer.finish() }
        var allocations = 0
        for ((prediction, maximum) in listOf(limit + 1 to limit, 11 to 10, Int.MAX_VALUE to limit, -1 to limit)) {
            assertFailsWith<CandidateBoundException> {
                BoundedEditCandidateWriter(prediction, maximum) { allocations++ }
            }
        }
        assertEquals(0, allocations)
        val tighterPrediction = BoundedEditCandidateWriter(2, limit)
        tighterPrediction.append(byteArrayOf(1, 2))
        assertFailsWith<CandidateBoundException> { tighterPrediction.append(byteArrayOf(3)) }
        assertFailsWith<CandidateBoundException> { tighterPrediction.finish() }
        val truncated = BoundedEditCandidateWriter(2, 2)
        truncated.append(byteArrayOf(1))
        assertFailsWith<CandidateBoundException> { truncated.finish() }
        assertFailsWith<CandidateBoundException> { truncated.append(byteArrayOf(2)) }
        val invalidRange = BoundedEditCandidateWriter(2, 2)
        assertFailsWith<CandidateBoundException> { invalidRange.append(byteArrayOf(1), Int.MIN_VALUE, Int.MAX_VALUE) }
        assertFailsWith<CandidateBoundException> { invalidRange.finish() }
    }

    @Test
    fun capabilityCannotRaiseCoreCeilingAndProductionRemainsDisabled() {
        for (invalid in listOf(-1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SyntheticMarksmanshipCapability(maxSourceBytes = invalid) }
            assertFailsWith<IllegalArgumentException> { SyntheticMarksmanshipCapability(maxCandidateBytes = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { SyntheticMarksmanshipCapability(maxCandidateBytes = Int.MAX_VALUE) }
        val result = assertIs<MarksmanshipEditResult.Failure>(Ja2MarksmanshipEditor().edit(
            ByteArray(0), MarksmanshipEditRequest(0, 0, 0),
        ))
        assertEquals(MarksmanshipEditReason.CAPABILITY_DISABLED, result.reason)
    }

    @Test
    fun failureSurfaceContainsOnlyBoundedCodesAndSuccessHasNoPublicCandidateFactory() {
        val fields = MarksmanshipEditResult.Failure::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf(MarksmanshipEditStage::class.java, MarksmanshipEditReason::class.java), fields.map { it.type }.toSet())
        assertTrue(MarksmanshipEditResult.Failure::class.java.methods.none { it.returnType == ByteArray::class.java })
        assertTrue(MarksmanshipEditResult.VerifiedCandidate::class.java.constructors.isEmpty())
        val implementation = MarksmanshipEditResult.VerifiedCandidate::class.java.permittedSubclasses.single()
        assertTrue(implementation.declaredConstructors.all { Modifier.isPrivate(it.modifiers) })
        assertTrue(MarksmanshipEditRequest::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .all { it.type == Int::class.javaPrimitiveType && Modifier.isFinal(it.modifiers) })
    }
}
