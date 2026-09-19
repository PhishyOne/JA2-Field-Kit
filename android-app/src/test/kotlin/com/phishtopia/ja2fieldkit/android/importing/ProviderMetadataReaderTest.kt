package com.phishtopia.ja2fieldkit.android.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProviderMetadataReaderTest {
    @Test
    fun openableMetadataAndTimestampAreReadFromIndependentQueries() {
        val query = FakeQuery(
            baseRow = row(strings = mapOf(DISPLAY_NAME to "slot.sav"), longs = mapOf(SIZE to 42)),
            timestampRow = row(longs = mapOf(LAST_MODIFIED to 1_725_000_000_000)),
        )

        val metadata = read(query)

        assertEquals("slot.sav", metadata.displayName)
        assertEquals(42, metadata.declaredSizeBytes)
        assertEquals(1_725_000_000_000, metadata.lastModifiedEpochMillis)
        assertEquals(listOf(listOf(DISPLAY_NAME, SIZE), listOf(LAST_MODIFIED)), query.projections)
    }

    @Test
    fun timestampQueryFailurePreservesOpenableMetadata() {
        val metadata = read(
            FakeQuery(
                baseRow = row(strings = mapOf(DISPLAY_NAME to "slot.sav"), longs = mapOf(SIZE to 42)),
                timestampFailure = UnsupportedOperationException("unsupported projection"),
            ),
        )

        assertEquals("slot.sav", metadata.displayName)
        assertEquals(42, metadata.declaredSizeBytes)
        assertNull(metadata.lastModifiedEpochMillis)
    }

    @Test
    fun absentTimestampColumnPreservesOpenableMetadata() {
        val metadata = read(
            FakeQuery(
                baseRow = row(strings = mapOf(DISPLAY_NAME to "generic.sav"), longs = mapOf(SIZE to 7)),
                timestampRow = row(),
            ),
        )

        assertEquals("generic.sav", metadata.displayName)
        assertEquals(7, metadata.declaredSizeBytes)
        assertNull(metadata.lastModifiedEpochMillis)
    }

    @Test
    fun baseQueryFailureReturnsSafeFallbackWithoutRequestingTimestamp() {
        val query = FakeQuery(baseFailure = IllegalArgumentException("unsupported projection"))

        val metadata = read(query)

        assertEquals("Selected save", metadata.displayName)
        assertNull(metadata.declaredSizeBytes)
        assertNull(metadata.lastModifiedEpochMillis)
        assertEquals(listOf(listOf(DISPLAY_NAME, SIZE)), query.projections)
    }

    @Test
    fun providerSizeAndTimestampValuesRetainExistingSanitation() {
        listOf(null, -1L).forEach { size ->
            assertNull(read(FakeQuery(baseRow = row(longs = mapOf(SIZE to size)))).declaredSizeBytes)
        }
        assertEquals(0, read(FakeQuery(baseRow = row(longs = mapOf(SIZE to 0L)))).declaredSizeBytes)

        listOf(null, -1L, 0L).forEach { timestamp ->
            assertNull(
                read(FakeQuery(timestampRow = row(longs = mapOf(LAST_MODIFIED to timestamp))))
                    .lastModifiedEpochMillis,
            )
        }
    }

    @Test
    fun retainedMetadataContainsNoRawUriPathOrProviderIdentifier() {
        val metadata = read(
            FakeQuery(
                baseRow = row(
                    strings = mapOf(DISPLAY_NAME to "content://private.provider/account/folder/slot.sav"),
                    longs = mapOf(SIZE to 3),
                ),
            ),
        )

        assertEquals("slot.sav", metadata.displayName)
        assertFalse(metadata.toString().contains("private.provider"))
        assertFalse(metadata.toString().contains("content://"))
    }

    private fun read(query: FakeQuery): ProviderSaveMetadata = ProviderMetadataReader.read(
        query = query,
        provenance = SourceProvenance.DOCUMENT_PICKER,
    )

    private class FakeQuery(
        private val baseRow: ProviderMetadataRow? = row(),
        private val timestampRow: ProviderMetadataRow? = row(),
        private val baseFailure: RuntimeException? = null,
        private val timestampFailure: RuntimeException? = null,
    ) : ProviderMetadataQuery {
        val projections = mutableListOf<List<String>>()

        override fun query(projection: Array<String>, consume: (ProviderMetadataRow) -> Unit) {
            projections += projection.toList()
            when (projection.toList()) {
                listOf(DISPLAY_NAME, SIZE) -> {
                    baseFailure?.let { throw it }
                    baseRow?.let(consume)
                }
                listOf(LAST_MODIFIED) -> {
                    timestampFailure?.let { throw it }
                    timestampRow?.let(consume)
                }
                else -> error("Unexpected projection: ${projection.toList()}")
            }
        }
    }

    private companion object {
        const val DISPLAY_NAME = "_display_name"
        const val SIZE = "_size"
        const val LAST_MODIFIED = "last_modified"

        fun row(
            strings: Map<String, String?> = emptyMap(),
            longs: Map<String, Long?> = emptyMap(),
        ): ProviderMetadataRow = object : ProviderMetadataRow {
            override fun stringOrNull(column: String): String? = strings[column]

            override fun longOrNull(column: String): Long? = longs[column]
        }
    }
}
