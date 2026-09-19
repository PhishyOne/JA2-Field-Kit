package com.phishtopia.ja2fieldkit.android.importing

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveImporterTest {
    @Test
    fun exactBytesHashAndActualCountFormOneCompleteImport() {
        val bytes = "JA2 save bytes".encodeToByteArray()
        val imported = importer().import(
            ByteArrayInputStream(bytes),
            metadata(declaredSizeBytes = 15, lastModifiedEpochMillis = 1_725_000_000_000),
        )

        assertEquals(bytes.size.toLong(), imported.provenance.actualSizeBytes)
        assertEquals(15, imported.provenance.declaredSizeBytes)
        assertEquals(1_725_000_000_000, imported.provenance.lastModifiedEpochMillis)
        assertEquals(
            "11344873a93f6a627e5d7f28f79ae8e05ff32a66f250a328cb7d2f8016ae2d10",
            imported.provenance.sha256Hex,
        )
    }

    @Test
    fun declaredSizeMismatchNeverOverridesActualSize() {
        val imported = importer().import(
            ByteArrayInputStream(byteArrayOf(4, 5, 6)),
            metadata(declaredSizeBytes = 2),
        )

        assertEquals(2, imported.provenance.declaredSizeBytes)
        assertEquals(3, imported.provenance.actualSizeBytes)
    }

    @Test
    fun overLimitFailsWithoutAnImportedResultOrPartialProvenance() {
        var yielded: ImportedSave? = null

        assertFailsWith<SaveTooLargeException> {
            yielded = importer(maximumBytes = 16 * 1024 * 1024).import(
                ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 1)),
                metadata(),
            )
        }
        assertNull(yielded)
    }

    @Test
    fun optionalProviderFieldsRemainOptionalAndFilenameIsSanitizedBeforeImport() {
        val imported = importer().import(
            ByteArrayInputStream(byteArrayOf(1)),
            metadata(
                displayName = "private/path/\nslot.sav",
                declaredSizeBytes = null,
                lastModifiedEpochMillis = null,
            ),
        )

        assertEquals("slot.sav", imported.provenance.displayName)
        assertNull(imported.provenance.declaredSizeBytes)
        assertNull(imported.provenance.lastModifiedEpochMillis)
        assertTrue(imported.provenance.sha256Hex.matches(Regex("[0-9a-f]{64}")))

        val unavailable = importer().import(
            ByteArrayInputStream(byteArrayOf(2)),
            metadata(
                displayName = null,
                declaredSizeBytes = -1,
                lastModifiedEpochMillis = 0,
            ),
        )
        assertEquals("Selected save", unavailable.provenance.displayName)
        assertNull(unavailable.provenance.declaredSizeBytes)
        assertNull(unavailable.provenance.lastModifiedEpochMillis)
    }

    @Test
    fun everyCurrentSourceVariantUsesTheSameImportedSaveResult() {
        SourceProvenance.entries.forEach { source ->
            val imported: ImportedSave = importer().import(
                ByteArrayInputStream(byteArrayOf(source.ordinal.toByte())),
                metadata(provenance = source),
            )

            assertEquals(source, imported.provenance.provenance)
        }
    }

    private fun importer(maximumBytes: Int = 64): SaveImporter =
        SaveImporter(BoundedSaveReader(maximumBytes))

    private fun metadata(
        displayName: String? = "slot.sav",
        declaredSizeBytes: Long? = null,
        lastModifiedEpochMillis: Long? = null,
        provenance: SourceProvenance = SourceProvenance.DOCUMENT_PICKER,
    ) = ProviderSaveMetadata(
        displayName = displayName,
        declaredSizeBytes = declaredSizeBytes,
        lastModifiedEpochMillis = lastModifiedEpochMillis,
        provenance = provenance,
    )
}
