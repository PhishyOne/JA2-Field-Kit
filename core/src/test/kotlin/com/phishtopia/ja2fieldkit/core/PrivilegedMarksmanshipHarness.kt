package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.RotationDigestOracle
import com.phishtopia.ja2fieldkit.core.format.SaveRotationTable
import com.phishtopia.ja2fieldkit.core.model.MercProfile
import com.phishtopia.ja2fieldkit.core.model.SaveInspectionV01Result
import java.security.MessageDigest
import java.util.function.Consumer

/** TEST ONLY: deliberate access suppression is the privilege, not possession of the descriptor. */
internal fun privilegedEditor(
    oracle: RotationDigestOracle,
    capability: SyntheticMarksmanshipCapability = SyntheticMarksmanshipCapability(),
    observe: (EditWork) -> Unit = {},
    inspector: Ja2SaveInspector = Ja2SaveInspector.withRotationDigestOracleForTesting(oracle),
): Ja2MarksmanshipEditor = Ja2MarksmanshipEditor::class.java.getDeclaredConstructor(
    Ja2SaveInspector::class.java, SyntheticMarksmanshipCapability::class.java, Consumer::class.java,
).apply { setAccessible(true) }.newInstance(inspector, capability, Consumer<EditWork> { observe(it) })

/** Corrupt the actual continuation input, never a detector's disposable snapshot. */
internal fun validatePrivilegedCandidate(
    editor: Ja2MarksmanshipEditor,
    source: ByteArray,
    candidate: ByteArray,
    request: MarksmanshipEditRequest,
    baseline: SaveInspectionV01Result.Success,
    profiles: List<MercProfile>,
    rotation: SaveRotationTable,
): MarksmanshipEditResult = Ja2MarksmanshipEditor::class.java.getDeclaredMethod(
    "validateCandidate", ByteArray::class.java, ByteArray::class.java, MarksmanshipEditRequest::class.java,
    SaveInspectionV01Result.Success::class.java, List::class.java, SaveRotationTable::class.java, String::class.java,
).apply { setAccessible(true) }.invoke(
    editor, source, candidate, request, baseline, profiles, rotation,
    MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) },
) as MarksmanshipEditResult

/** Exercise the exact private production writer without making it a public candidate-byte helper. */
internal class BoundedEditCandidateWriter(
    predictedSize: Int,
    capabilityMaximum: Int,
    beforeAllocation: () -> Unit = {},
) {
    private val type = Ja2MarksmanshipEditor::class.java.declaredClasses.single { it.simpleName == "BoundedCandidateWriter" }
    private val writer = unwrapReflection {
        type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Runnable::class.java)
            .apply { setAccessible(true) }.newInstance(predictedSize, capabilityMaximum, Runnable { beforeAllocation() })
    }

    fun append(source: ByteArray, start: Int = 0, end: Int = source.size) {
        unwrapReflection {
            type.getDeclaredMethod("append", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { setAccessible(true) }.invoke(writer, source, start, end)
        }
    }

    fun finish(): ByteArray = unwrapReflection {
        type.getDeclaredMethod("finish").apply { setAccessible(true) }.invoke(writer) as ByteArray
    }
}

private fun <T> unwrapReflection(block: () -> T): T = try {
    block()
} catch (error: java.lang.reflect.InvocationTargetException) {
    throw error.targetException
}
