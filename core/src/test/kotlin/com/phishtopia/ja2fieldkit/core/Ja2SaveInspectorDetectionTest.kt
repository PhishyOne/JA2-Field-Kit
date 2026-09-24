package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.*
import com.phishtopia.ja2fieldkit.core.model.*
import kotlin.test.*

/** Resource-independent tests: a malicious SUPPORTED claim must never reach even the header parser. */
class Ja2SaveInspectorDetectionTest {
    private val suspect = SaveFormatDetection(
        layout = SaveLayout.NORMAL_V103_BUILD_041202_NON_LINUX,
        compatibility = SaveCompatibility.SUPPORTED,
        family = SaveFamily.CLASSIC,
        saveVersion = 103,
        buildLabel = "04.12.02",
        evidence = listOf("SUSPECT_PAYLOAD"),
        reason = SaveDetectionReason.SELECTOR_BODY_ROTATION_MATCH,
        facts = SaveDetectionFacts(999, true, true, rawSaveVersion = 103),
    )

    @Test
    fun mutationAndRuntimeFailureAreSanitizedAcrossEveryEntryPoint() {
        for (mode in listOf("mutation", "throw", "mutation-then-throw")) {
            val caller = byteArrayOf(1, 2, 3)
            val original = caller.copyOf()
            var calls = 0
            val inspector = Ja2SaveInspector.withDetectorForTesting { snapshot ->
                calls++
                assertNotSame(caller, snapshot)
                if (mode != "throw") snapshot[0]++
                if (mode != "mutation") throw IllegalArgumentException("PRIVATE_EXCEPTION_PAYLOAD")
                suspect
            }
            val reason = if (mode == "mutation") SaveDetectionReason.DETECTOR_MUTATED_INPUT
                else SaveDetectionReason.DETECTION_FAILED
            val detection = inspector.detect(caller)
            assertEquals(SaveFormatDetection(SaveLayout.UNKNOWN, SaveCompatibility.UNKNOWN, SaveFamily.UNKNOWN,
                reason = reason), detection)
            assertContentEquals(original, caller)
            val result = assertIs<SaveInspectionV01Result.Failure>(inspector.inspectV01(caller))
            assertEquals(SaveInspectionFormat(SaveLayout.UNKNOWN, SaveCompatibility.UNKNOWN, SaveFamily.UNKNOWN,
                null, null), result.format)
            assertEquals(SaveInspectionFailureKind.CORRUPT_INPUT, result.failure.kind)
            assertEquals(SaveInspectionDiagnostic.DETECTION_FAILED, result.failure.diagnostic)
            val inventory = assertIs<LiveInventoryInspectionResult.Failure>(inspector.inspectLiveInventory(caller))
            assertEquals(result.format, inventory.format)
            assertEquals(result.failure, inventory.failure)
            val live = assertIs<LiveMercStateInspectionResult.Failure>(inspector.inspectLiveMercState(caller))
            assertEquals(result.format, live.format)
            assertEquals(result.failure, live.failure)
            assertContentEquals(original, caller)
            val interpreters: List<(ByteArray) -> Any> = listOf(
                inspector::parseBuild041202Header,
                inspector::frameBuild041202NormalNonLinuxProfiles,
                inspector::parseBuild041202NormalNonLinuxProfiles,
                inspector::parseBuild041202NormalNonLinuxRoster,
            )
            for (interpret in interpreters) {
                val error = assertFailsWith<SaveInterpretationAdmissionException> { interpret(caller) }
                assertEquals(SaveCompatibility.UNKNOWN, error.compatibility)
                assertEquals(SaveLayout.UNKNOWN, error.layout)
                assertEquals(reason, error.reason)
                assertNull(error.cause)
                assertFalse(error.toString().contains("PAYLOAD"))
                assertContentEquals(original, caller)
            }
            assertEquals(8, calls)
        }
    }

    @Test
    fun nonMutatingDetectionIsUnchangedAndRetainedDetectorArrayCannotAlterCaller() {
        val caller = byteArrayOf(1, 2, 3)
        val original = caller.copyOf()
        var retained: ByteArray? = null
        val inspector = Ja2SaveInspector.withDetectorForTesting { snapshot ->
            retained = snapshot
            suspect
        }
        assertEquals(suspect, inspector.detect(caller))
        retained!!.fill(0)
        assertContentEquals(original, caller)
        assertEquals(SaveFormatDetector.detect(caller), Ja2SaveInspector().detect(caller))
        assertContentEquals(original, caller)
    }
}
