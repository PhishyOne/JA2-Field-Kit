package com.phishtopia.ja2fieldkit.android.report

import com.phishtopia.ja2fieldkit.android.importing.ImportedSaveProvenance
import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.FormatPresentation

/** Allow-listed facts retained before a user explicitly asks to generate a report. */
data class CompatibilityReportInputs(
    val sourceCategory: String,
    val actualSizeBytes: Long,
    val sourceSha256: String,
    val saveVersion: Int?,
    val build: String?,
    val layout: String,
    val compatibility: String,
    val producer: String,
    val failureKind: String,
    val failureDiagnostic: String,
) {
    init {
        CompatibilityReportV01.validateFacts(
            sourceCategory = sourceCategory,
            actualSizeBytes = actualSizeBytes,
            sourceSha256 = sourceSha256,
            saveVersion = saveVersion,
            build = build,
            layout = layout,
            compatibility = compatibility,
            producer = producer,
            failureKind = failureKind,
            failureDiagnostic = failureDiagnostic,
        )
    }
}

/** Privacy-bounded inputs accepted by the compatibility-report v0.1 serializer. */
data class CompatibilityReportV01(
    val appVersion: String,
    val sourceCategory: String,
    val actualSizeBytes: Long,
    val sourceSha256: String,
    val saveVersion: Int?,
    val build: String?,
    val layout: String,
    val compatibility: String,
    val producer: String,
    val failureKind: String,
    val failureDiagnostic: String,
) {
    init {
        require(appVersion.matches(SAFE_VERSION))
        validateFacts(
            sourceCategory, actualSizeBytes, sourceSha256, saveVersion, build,
            layout, compatibility, producer, failureKind, failureDiagnostic,
        )
    }

    /** Stable field order, LF line endings, two-space indentation, and one final LF. */
    fun toJson(): String = buildString {
        append("{\n")
        field("schema_version", SCHEMA_VERSION)
        field("app_version", appVersion)
        append("  \"source\": {\n")
        field("category", sourceCategory, 4)
        numberField("actual_size_bytes", actualSizeBytes, 4)
        field("sha256", sourceSha256, 4, last = true)
        append("  },\n")
        append("  \"format\": {\n")
        nullableNumberField("save_version", saveVersion, 4)
        nullableField("build", build, 4)
        field("layout", layout, 4)
        field("compatibility", compatibility, 4)
        field("producer", producer, 4, last = true)
        append("  },\n")
        append("  \"failure\": {\n")
        field("kind", failureKind, 4)
        field("diagnostic", failureDiagnostic, 4, last = true)
        append("  }\n")
        append("}\n")
    }

    private fun StringBuilder.field(
        name: String,
        value: String,
        indent: Int = 2,
        last: Boolean = false,
    ) {
        append(" ".repeat(indent))
        appendJsonString(name)
        append(": ")
        appendJsonString(value)
        append(if (last) '\n' else ",\n")
    }

    private fun StringBuilder.numberField(
        name: String,
        value: Long,
        indent: Int,
    ) {
        append(" ".repeat(indent))
        appendJsonString(name)
        append(": ")
        append(value)
        append(",\n")
    }

    private fun StringBuilder.nullableNumberField(name: String, value: Int?, indent: Int) {
        append(" ".repeat(indent))
        appendJsonString(name)
        append(": ")
        append(value ?: "null")
        append(",\n")
    }

    private fun StringBuilder.nullableField(name: String, value: String?, indent: Int) {
        append(" ".repeat(indent))
        appendJsonString(name)
        append(": ")
        if (value == null) append("null") else appendJsonString(value)
        append(",\n")
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    companion object {
        const val SCHEMA_VERSION = "ja2-field-kit.compatibility-report/0.1"
        const val MAXIMUM_SOURCE_BYTES = 16L * 1024 * 1024

        private val SAFE_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val BUILDS = setOf("04.12.02")
        private val SOURCE_CATEGORIES = setOf("document_picker", "open_with", "share_to")
        private val LAYOUTS = setOf("normal_non_linux_v103_build_041202", "unknown")
        private val COMPATIBILITIES = setOf(
            "supported", "candidate", "truncated", "unsupported_variant", "inconsistent", "unknown",
        )
        private val PRODUCERS = setOf("ja2_reborn", "ja2_stracciatella", "classic_ja2", "unknown")
        private val FAILURE_KINDS = setOf(
            "unknown_format", "unsupported_variant", "truncated_input", "inconsistent_input", "corrupt_input",
        )
        private val FAILURE_DIAGNOSTICS = setOf(
            "identity_incomplete", "identity_unknown", "identity_inconsistent", "header_truncated",
            "layout_truncated", "variant_unsupported", "content_ambiguous", "header_body_mismatch",
            "content_inconsistent", "content_corrupt", "detection_failed",
        )

        internal fun validateFacts(
            sourceCategory: String,
            actualSizeBytes: Long,
            sourceSha256: String,
            saveVersion: Int?,
            build: String?,
            layout: String,
            compatibility: String,
            producer: String,
            failureKind: String,
            failureDiagnostic: String,
        ) {
            require(sourceCategory in SOURCE_CATEGORIES)
            require(actualSizeBytes in 0..MAXIMUM_SOURCE_BYTES)
            require(sourceSha256.matches(SHA_256))
            require(saveVersion == null || saveVersion >= 0)
            require(build == null || build in BUILDS)
            require(layout in LAYOUTS)
            require(compatibility in COMPATIBILITIES)
            require(producer in PRODUCERS)
            require(failureKind in FAILURE_KINDS)
            require(failureDiagnostic in FAILURE_DIAGNOSTICS)
        }
    }
}

/** Builds a report only from retained safe provenance and inspectV01 presentation facts. */
object CompatibilityReportFactory {
    fun captureInputs(
        source: ImportedSaveProvenance,
        format: FormatPresentation,
        failureKind: String,
        failureDiagnostic: String,
    ): CompatibilityReportInputs = CompatibilityReportInputs(
        sourceCategory = when (source.provenance) {
            SourceProvenance.DOCUMENT_PICKER -> "document_picker"
            SourceProvenance.OPEN_WITH -> "open_with"
            SourceProvenance.SHARE_TO -> "share_to"
        },
        actualSizeBytes = source.actualSizeBytes,
        sourceSha256 = source.sha256Hex,
        saveVersion = format.version.toIntOrNull()?.takeIf { it >= 0 },
        build = format.build.takeIf { it == "04.12.02" },
        layout = when (format.layout) {
            "Normal non-Linux v103" -> "normal_non_linux_v103_build_041202"
            else -> "unknown"
        },
        compatibility = when (format.compatibility) {
            "Supported" -> "supported"
            "Candidate" -> "candidate"
            "Truncated" -> "truncated"
            "Unsupported variant" -> "unsupported_variant"
            "Inconsistent" -> "inconsistent"
            else -> "unknown"
        },
        producer = when (format.producer) {
            "JA2 Reborn" -> "ja2_reborn"
            "JA2 Stracciatella" -> "ja2_stracciatella"
            "Classic JA2" -> "classic_ja2"
            else -> "unknown"
        },
        failureKind = failureKind.lowercase(),
        failureDiagnostic = failureDiagnostic.lowercase(),
    )

    fun create(appVersion: String, inputs: CompatibilityReportInputs): CompatibilityReportV01 =
        CompatibilityReportV01(
            appVersion = appVersion,
            sourceCategory = inputs.sourceCategory,
            actualSizeBytes = inputs.actualSizeBytes,
            sourceSha256 = inputs.sourceSha256,
            saveVersion = inputs.saveVersion,
            build = inputs.build,
            layout = inputs.layout,
            compatibility = inputs.compatibility,
            producer = inputs.producer,
            failureKind = inputs.failureKind,
            failureDiagnostic = inputs.failureDiagnostic,
        )
}

/** One preview is the sole source for both explicit output actions. */
data class CompatibilityReportPreview(val text: String) {
    val clipboardText: String get() = text
    val share: CompatibilityReportShare get() = CompatibilityReportShare("text/plain", text)
}

data class CompatibilityReportShare(val mimeType: String, val text: String)
