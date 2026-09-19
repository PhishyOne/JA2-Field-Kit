package com.phishtopia.ja2fieldkit.android

import com.phishtopia.ja2fieldkit.android.importing.SourceProvenance
import com.phishtopia.ja2fieldkit.android.presentation.SourceFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class IncomingImportIntentTest {
    @Test
    fun viewIsHandledOnceAndOnlySourceFreeStateIsStored() {
        val consumed = consumeImportIntent(
            IncomingImportIntent(action = ACTION_VIEW_IMPORT, viewSource = "content://provider/save"),
        )

        val request = assertIs<ImportIntentRequest.Inspect<String>>(consumed.request)
        assertEquals("content://provider/save", request.source)
        assertEquals(SourceProvenance.OPEN_WITH, request.provenance)
        assertEquals(StoredActivityIntent.SOURCE_FREE, consumed.storedIntent)
    }

    @Test
    fun sendIsHandledOnceAndOnlySourceFreeStateIsStored() {
        val consumed = consumeImportIntent(
            IncomingImportIntent(
                action = ACTION_SEND_IMPORT,
                sharedSource = "content://provider/shared-save",
                sharedSourceCount = 1,
            ),
        )

        val request = assertIs<ImportIntentRequest.Inspect<String>>(consumed.request)
        assertEquals("content://provider/shared-save", request.source)
        assertEquals(SourceProvenance.SHARE_TO, request.provenance)
        assertEquals(StoredActivityIntent.SOURCE_FREE, consumed.storedIntent)
    }

    @Test
    fun multipleSharedItemsRemainRejectedWithoutRetainingThem() {
        val consumed = consumeImportIntent(
            IncomingImportIntent(
                action = ACTION_SEND_IMPORT,
                sharedSource = "content://provider/first-save",
                sharedSourceCount = 2,
            ),
        )

        val request = assertIs<ImportIntentRequest.Reject>(consumed.request)
        assertEquals(SourceFailureKind.MULTIPLE_ITEMS, request.kind)
        assertEquals(StoredActivityIntent.SOURCE_FREE, consumed.storedIntent)
    }

    @Test
    fun launcherAndOtherNonSourceIntentsDoNotImport() {
        listOf(null, "android.intent.action.MAIN", "app.example.BENIGN").forEach { action ->
            val consumed = consumeImportIntent(IncomingImportIntent<String>(action = action))

            assertIs<ImportIntentRequest.None>(consumed.request)
            assertEquals(StoredActivityIntent.SOURCE_FREE, consumed.storedIntent)
        }
    }

    @Test
    fun newIntentConsumptionDoesNotPutRawSourceInStoredState() {
        val rawProviderIdentifier = "content://private.provider/document/account%3A42"

        val consumed = consumeImportIntent(
            IncomingImportIntent(action = ACTION_VIEW_IMPORT, viewSource = rawProviderIdentifier),
        )

        assertIs<ImportIntentRequest.Inspect<String>>(consumed.request)
        assertEquals(StoredActivityIntent.SOURCE_FREE, consumed.storedIntent)
        assertNotEquals(rawProviderIdentifier, consumed.storedIntent.toString())
    }

    @Test
    fun recreationFromSourceFreeStateDoesNotReimport() {
        val first = consumeImportIntent(
            IncomingImportIntent(action = ACTION_VIEW_IMPORT, viewSource = "content://provider/save"),
        )
        assertIs<ImportIntentRequest.Inspect<String>>(first.request)

        val recreated = consumeImportIntent(IncomingImportIntent<String>(action = null))

        assertEquals(StoredActivityIntent.SOURCE_FREE, recreated.storedIntent)
        assertIs<ImportIntentRequest.None>(recreated.request)
    }
}
