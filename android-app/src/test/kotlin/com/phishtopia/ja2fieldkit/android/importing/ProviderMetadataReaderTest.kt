package com.phishtopia.ja2fieldkit.android.importing

import com.phishtopia.ja2fieldkit.android.presentation.InspectionPresentationMapper
import com.phishtopia.ja2fieldkit.core.Ja2SaveInspector
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProviderMetadataReaderTest {
    @Test
    fun rejectedTimestampQueryDoesNotDiscardOpenableNameOrSize() {
        val metadata = read(
            openable = OpenableSourceMetadata("slot01.sav", 24),
            timestampFailure = UnsupportedOperationException("unsupported column"),
        )
        val imported = SaveImporter(BoundedSaveReader(64)).import(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            metadata,
        )

        assertEquals("slot01.sav", imported.provenance.displayName)
        assertEquals(24, imported.provenance.declaredSizeBytes)
        assertEquals(3, imported.provenance.actualSizeBytes)
        assertNull(imported.provenance.lastModifiedEpochMillis)
    }

    @Test
    fun providerSupportingBothQueriesRetainsAllMetadata() {
        val metadata = read(
            openable = OpenableSourceMetadata("slot02.sav", 31),
            lastModified = 1_725_000_000_000L,
        )
        val imported = SaveImporter(BoundedSaveReader(64)).import(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            metadata,
        )

        assertEquals("slot02.sav", imported.provenance.displayName)
        assertEquals(31, imported.provenance.declaredSizeBytes)
        assertEquals(1_725_000_000_000L, imported.provenance.lastModifiedEpochMillis)
    }

    @Test
    fun rejectedOpenableQueryStillImportsWithFallbackNameAndNoDeclaredSize() {
        val metadata = read(
            openableFailure = IllegalArgumentException("unsupported projection"),
            timestampFailure = UnsupportedOperationException("unsupported column"),
        )

        val imported = SaveImporter(BoundedSaveReader(64)).import(
            ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            metadata,
        )

        assertEquals("Selected save", imported.provenance.displayName)
        assertNull(imported.provenance.declaredSizeBytes)
        assertEquals(3, imported.provenance.actualSizeBytes)
        assertNull(imported.provenance.lastModifiedEpochMillis)
    }

    @Test
    fun invalidOrNonpositiveTimestampIsAbsent() {
        listOf("1725000000000", 1.5, 0L, -1L, null).forEach { value ->
            assertNull(read(lastModified = value).lastModifiedEpochMillis)
        }
    }

    @Test
    fun declaredSizeMismatchDoesNotOverrideMeasuredActualSize() {
        val imported = SaveImporter(BoundedSaveReader(64)).import(
            ByteArrayInputStream(byteArrayOf(4, 5, 6)),
            read(openable = OpenableSourceMetadata("slot.sav", 2)),
        )

        assertEquals(2, imported.provenance.declaredSizeBytes)
        assertEquals(3, imported.provenance.actualSizeBytes)
    }

    @Test
    fun oversizedDeclaredSizeRemainsAdvisoryProvenance() {
        val imported = SaveImporter(BoundedSaveReader(3)).import(
            ByteArrayInputStream(byteArrayOf(4, 5, 6)),
            read(openable = OpenableSourceMetadata("slot.sav", 4)),
        )

        assertEquals(4, imported.provenance.declaredSizeBytes)
        assertEquals(3, imported.provenance.actualSizeBytes)
    }

    @Test
    fun negativeDeclaredSizeDegradesToAbsent() {
        val metadata = read(openable = OpenableSourceMetadata("slot.sav", -1))

        assertNull(metadata.declaredSizeBytes)
    }

    @Test
    fun rawProviderIdentifierDoesNotEnterPresentationState() {
        val rawIdentifier = "content://private.provider/document/account%3A42/slot.sav"
        val imported = SaveImporter(BoundedSaveReader(64)).import(
            ByteArrayInputStream(byteArrayOf()),
            read(openable = OpenableSourceMetadata(rawIdentifier, 0)),
        )
        val state = imported.inspectWith(Ja2SaveInspector()) { inspection, inventory ->
            InspectionPresentationMapper.map(imported.provenance, inspection, inventory)
        }

        assertEquals("slot.sav", imported.provenance.displayName)
        assertFalse(state.toString().contains(rawIdentifier))
        assertFalse(state.toString().contains("private.provider"))
    }

    private fun read(
        openable: OpenableSourceMetadata? = OpenableSourceMetadata("slot.sav", null),
        lastModified: Any? = null,
        openableFailure: RuntimeException? = null,
        timestampFailure: RuntimeException? = null,
    ): ProviderSaveMetadata = ProviderMetadataReader.read(
        provider = object : SourceMetadataProvider {
            override fun queryOpenableMetadata(): OpenableSourceMetadata? {
                openableFailure?.let { throw it }
                return openable
            }

            override fun queryLastModifiedValue(): Any? {
                timestampFailure?.let { throw it }
                return lastModified
            }
        },
        provenance = SourceProvenance.OPEN_WITH,
    )
}
