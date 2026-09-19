package com.phishtopia.ja2fieldkit.android.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderMetadataQueryPolicyTest {
    @Test
    fun timestampFailureDoesNotDiscardStandardMetadata() {
        val metadata = query { projection ->
            when (projection) {
                ProviderMetadataProjection.STANDARD -> standardMetadata()
                ProviderMetadataProjection.LAST_MODIFIED -> error("unsupported column")
            }
        }

        assertEquals("slot.sav", metadata.displayName)
        assertEquals(42, metadata.declaredSizeBytes)
        assertNull(metadata.lastModifiedEpochMillis)
    }

    @Test
    fun providerSupportingBothQueriesPreservesAllMetadata() {
        val metadata = query { projection ->
            when (projection) {
                ProviderMetadataProjection.STANDARD -> standardMetadata()
                ProviderMetadataProjection.LAST_MODIFIED -> QueriedProviderMetadata(
                    lastModifiedEpochMillis = 1_725_000_000_000,
                )
            }
        }

        assertEquals("slot.sav", metadata.displayName)
        assertEquals(42, metadata.declaredSizeBytes)
        assertEquals(1_725_000_000_000, metadata.lastModifiedEpochMillis)
    }

    @Test
    fun standardMetadataFailureUsesSafeFallback() {
        val metadata = query { projection ->
            when (projection) {
                ProviderMetadataProjection.STANDARD -> error("query failed")
                ProviderMetadataProjection.LAST_MODIFIED -> QueriedProviderMetadata(
                    lastModifiedEpochMillis = 1_725_000_000_000,
                )
            }
        }

        assertEquals("Selected save", metadata.displayName)
        assertNull(metadata.declaredSizeBytes)
        assertEquals(1_725_000_000_000, metadata.lastModifiedEpochMillis)
    }

    @Test
    fun invalidTimestampDoesNotDiscardStandardMetadata() {
        listOf(-1L, 0L).forEach { invalidTimestamp ->
            val metadata = query { projection ->
                when (projection) {
                    ProviderMetadataProjection.STANDARD -> standardMetadata()
                    ProviderMetadataProjection.LAST_MODIFIED -> QueriedProviderMetadata(
                        lastModifiedEpochMillis = invalidTimestamp,
                    )
                }
            }

            assertEquals("slot.sav", metadata.displayName)
            assertEquals(42, metadata.declaredSizeBytes)
            assertNull(metadata.lastModifiedEpochMillis)
        }
    }

    private fun query(
        providerQuery: (ProviderMetadataProjection) -> QueriedProviderMetadata?,
    ): ProviderSaveMetadata = ProviderMetadataQueryPolicy.query(
        SourceProvenance.DOCUMENT_PICKER,
        providerQuery,
    )

    private fun standardMetadata() = QueriedProviderMetadata(
        displayName = "slot.sav",
        declaredSizeBytes = 42,
    )
}
