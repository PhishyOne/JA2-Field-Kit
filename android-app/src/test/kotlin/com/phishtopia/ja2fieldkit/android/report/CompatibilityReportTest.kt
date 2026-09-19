package com.phishtopia.ja2fieldkit.android.report

import com.phishtopia.ja2fieldkit.android.importing.DisplayNameSanitizer
import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.FormatPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompatibilityReportTest {
    @Test
    fun serializesDeterministicV01JsonWithSafeFormatFacts() {
        val inputs = CompatibilityReportFactory.captureInputs(
            source = source(),
            format = format(),
            failureKind = "UNSUPPORTED_VARIANT",
            failureDiagnostic = "VARIANT_UNSUPPORTED",
        )
        val report = CompatibilityReportFactory.create("0.1.0", inputs)
        val expected = """
            {
              "schema_version": "ja2-field-kit.compatibility-report/0.1",
              "app_version": "0.1.0",
              "source": {
                "category": "open_with",
                "actual_size_bytes": 2563321,
                "sha256": "${"ab".repeat(32)}"
              },
              "format": {
                "save_version": 103,
                "build": "04.12.02",
                "layout": "normal_non_linux_v103_build_041202",
                "compatibility": "unsupported_variant",
                "producer": "unknown"
              },
              "failure": {
                "kind": "unsupported_variant",
                "diagnostic": "variant_unsupported"
              }
            }
        """.trimIndent() + "\n"

        assertEquals(expected, report.toJson())
        assertEquals(report.toJson(), report.toJson())
    }

    @Test
    fun rejectsInvalidActualSizeAndHash() {
        assertFailsWith<IllegalArgumentException> { report(actualSizeBytes = -1) }
        assertFailsWith<IllegalArgumentException> {
            report(actualSizeBytes = CompatibilityReportV01.MAXIMUM_SOURCE_BYTES + 1)
        }
        assertFailsWith<IllegalArgumentException> { report(sourceSha256 = "AB".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { report(sourceSha256 = "00") }
    }

    @Test
    fun hostileFilenameProviderMetadataAndGameplayDataNeverEnterReport() {
        val hostile = source(
            displayName = "../../private/account-42/Ira-Health99\nslot.sav",
            declaredSizeBytes = 9_999_999,
            lastModifiedEpochMillis = 1_725_000_000_000,
        )

        val inputs = CompatibilityReportFactory.captureInputs(
            source = hostile,
            format = format(build = "campaign notes are private"),
            failureKind = "CORRUPT_INPUT",
            failureDiagnostic = "CONTENT_CORRUPT",
        )
        val text = CompatibilityReportFactory.create("0.1.0", inputs).toJson()

        listOf(
            "private", "account-42", "Ira", "Health99", "slot.sav",
            "9999999", "1725000000000", "campaign notes",
        ).forEach { forbidden -> assertFalse(text.contains(forbidden, ignoreCase = true)) }
        assertFalse(text.contains('/') && text.contains("private"))
        assertEquals("\"build\": null,", text.lineSequence().first { "\"build\"" in it }.trim())
    }

    @Test
    fun onlyAllowListedBuildFactCanEnterReport() {
        listOf("Ira", "save-notes", "04.12.03", "04.12.02 private").forEach { hostileBuild ->
            val inputs = CompatibilityReportFactory.captureInputs(
                source = source(),
                format = format(build = hostileBuild),
                failureKind = "UNKNOWN_FORMAT",
                failureDiagnostic = "IDENTITY_UNKNOWN",
            )
            val text = CompatibilityReportFactory.create("0.1.0", inputs).toJson()

            assertTrue(text.contains("\"build\": null"))
            assertFalse(text.contains(hostileBuild))
        }
    }

    @Test
    fun previewClipboardAndShareUseTheExactSameText() {
        val text = report().toJson()
        val preview = CompatibilityReportPreview(text)

        assertEquals(text, preview.text)
        assertEquals(preview.text, preview.clipboardText)
        assertEquals("text/plain", preview.share.mimeType)
        assertEquals(preview.text, preview.share.text)
    }

    @Test
    fun reportAndActionModelsContainNoRawByteContainerOrSourceReference() {
        val report = report()
        val preview = CompatibilityReportPreview(report.toJson())

        listOf(report.javaClass, preview.javaClass, preview.share.javaClass).forEach { type ->
            assertFalse(type.declaredFields.any { it.type == ByteArray::class.java })
        }
        assertFalse(report.toString().contains("content://"))
        assertNull(report.build)
    }

    @Test
    fun retainedInputsExcludeFilenameAndProviderMetadataBeforeSerialization() {
        val inputs = CompatibilityReportFactory.captureInputs(
            source = source(
                displayName = "private-account-Ira.sav",
                declaredSizeBytes = 9_999_999,
                lastModifiedEpochMillis = 1_725_000_000_000,
            ),
            format = format(build = "private gameplay"),
            failureKind = "CORRUPT_INPUT",
            failureDiagnostic = "CONTENT_CORRUPT",
        )

        val retained = inputs.toString()
        listOf("private", "account", "Ira", "9999999", "1725000000000", "gameplay")
            .forEach { forbidden -> assertFalse(retained.contains(forbidden, ignoreCase = true)) }
        assertFalse(inputs.javaClass.declaredFields.any { it.type == ByteArray::class.java })
    }

    private fun report(
        actualSizeBytes: Long = 4,
        sourceSha256: String = "00".repeat(32),
    ) = CompatibilityReportV01(
        appVersion = "0.1.0",
        sourceCategory = "document_picker",
        actualSizeBytes = actualSizeBytes,
        sourceSha256 = sourceSha256,
        saveVersion = null,
        build = null,
        layout = "unknown",
        compatibility = "unknown",
        producer = "unknown",
        failureKind = "unknown_format",
        failureDiagnostic = "identity_unknown",
    )

    private fun source(
        displayName: String = "slot.sav",
        declaredSizeBytes: Long? = null,
        lastModifiedEpochMillis: Long? = null,
    ) = ImportedSaveProvenance(
        displayName = DisplayNameSanitizer.sanitize(displayName),
        provenance = SourceProvenance.OPEN_WITH,
        declaredSizeBytes = declaredSizeBytes,
        actualSizeBytes = 2_563_321,
        lastModifiedEpochMillis = lastModifiedEpochMillis,
        sha256Hex = "ab".repeat(32),
    )

    private fun format(build: String = "04.12.02") = FormatPresentation(
        version = "103",
        build = build,
        layout = "Normal non-Linux v103",
        compatibility = "Unsupported variant",
        producer = "Not established",
    )
}
