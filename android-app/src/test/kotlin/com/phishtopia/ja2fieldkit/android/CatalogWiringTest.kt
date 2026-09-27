package com.phishtopia.ja2fieldkit.android

import java.io.File
import kotlin.test.*

class CatalogWiringTest {
    private val dir = File(checkNotNull(System.getProperty("androidAppProjectDir")), "src/main/kotlin/com/phishtopia/ja2fieldkit/android")
    private val model = File(dir, "InspectionViewModel.kt").readText()
    private val activity = File(dir, "MainActivity.kt").readText()

    @Test fun explicitPickerIsTheOnlyCatalogEntryPoint() {
        assertEquals(2, Regex("ActivityResultContracts.OpenDocument\\(\\)").findAll(activity).count())
        assertEquals(1, Regex("model.loadItemNames\\(").findAll(activity).count())
        assertTrue(activity.substringAfter("private val openCatalogDocument").substringBefore("override fun onCreate").contains("model.loadItemNames"))
        assertTrue(activity.substringAfter("is InspectionScreenState.Success ->").substringBefore("is InspectionScreenState.Failure ->").contains("openCatalogDocument.launch"))
        assertFalse(activity.substringAfter("private fun consumeIntent").substringBefore("private fun render").contains("Catalog"))
        listOf("takePersistableUriPermission", "openOutputStream", "ACTION_CREATE_DOCUMENT", "SavedStateHandle").forEach {
            assertFalse((activity + model).contains(it))
        }
    }
    @Test fun loadingCannotInspectOrImportAndCompletionUsesCurrentSaveState() {
        val load = model.substringAfter("fun loadItemNames(").substringBefore("private fun querySourceMetadata")
        assertTrue(load.contains("CatalogReader.read(uri.scheme)"))
        assertTrue(load.contains("if (catalogSession.complete(request, result)) publish(state)"))
        listOf("inspector", "importer", "inspect(", "querySourceMetadata", "requestSequence").forEach { assertFalse(load.contains(it)) }
        assertTrue(model.contains("private val catalogSession = CatalogSession()"))
        assertTrue(model.contains("next.copy(catalog = catalogSession.presentation)"))
        assertTrue(model.contains("state = withCatalog(next)"))
        assertTrue(model.substringAfter("override fun onCleared").contains("catalogSession.clear()"))
        assertFalse(model.substringAfter("fun inspect(").substringBefore("fun showSourceFailure").contains("catalogSession.clear"))
        assertFalse(model.substringAfter("fun detach(").substringBefore("fun inspect(").contains("catalogSession"))
    }
}
