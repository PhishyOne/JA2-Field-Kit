package com.phishtopia.ja2fieldkit.android

import java.io.File
import kotlin.test.*

/** Boundary guard: the read-only dossier renders presentation facts, with no edit/source access. */
class PersonnelWiringTest {
    @Test fun personnelEntryNavigationAndReadOnlySectionsAreConnected() {
        val root = File(checkNotNull(System.getProperty("androidAppProjectDir")),
            "src/main/kotlin/com/phishtopia/ja2fieldkit/android")
        val activity = File(root, "MainActivity.kt").readText()
        val model = File(root, "InspectionViewModel.kt").readText()
        assertTrue(activity.contains("text = \"Personnel\""))
        assertTrue(activity.contains("setOnClickListener { model.openPersonnel() }"))
        assertTrue(activity.contains("if (screenState.personnelVisible)"))
        assertTrue(activity.contains("setOnClickListener { model.openPersonnel(person.profileId) }"))
        assertTrue(activity.contains("override fun handleOnBackPressed() = model.closePersonnel()"))
        val dossier = activity.substringAfter("private fun LinearLayout.addDossier(")
            .substringBefore("private fun updateMercSelection(")
        for (section in listOf("Attributes", "Traits", "Personality", "Record", "Relationships")) {
            assertTrue(dossier.contains("section(\"$section\""))
        }
        assertTrue(dossier.contains("Standard profile category: ${'$'}{person.standardCategory}"))
        assertTrue(dossier.contains("if (person.economics.isNotEmpty()) {"))
        val economics = dossier.substringAfter("if (person.economics.isNotEmpty()) {").substringBefore("section(\"Attributes\"")
        assertTrue(economics.contains("addHeading(\"Profile economics\", 17f)"))
        assertTrue(economics.contains("Recorded profile values. Hiring and current contract details depend on other game state."))
        assertTrue(economics.indexOf("Recorded profile values.") < economics.indexOf("person.economics.joinToString"))
        for (forbidden in listOf("editMerc", "export", "inspector", "ByteArray", "MercProfile")) {
            assertFalse(dossier.contains(forbidden))
        }
        assertTrue(dossier.contains("addHeading(\"Profile inventory\", 17f)"))
        assertTrue(dossier.contains("person.profileInventoryText(displayedCatalog)"))
        assertTrue(activity.contains("displayedCatalog = (screenState as? InspectionScreenState.Success)?.catalog?.active"))
        val navigation = model.substringAfter("fun openPersonnel(").substringBefore("private fun withCatalog(")
        for (forbidden in listOf("inspector", "editSession", "resolver", "ByteArray", "SavedStateHandle")) {
            assertFalse(navigation.contains(forbidden))
        }
        assertTrue(navigation.contains("state.openPersonnel(profileId)"))
        assertTrue(navigation.contains("state.closePersonnel()"))
    }
    @Test fun filtersUseSafeStateWithoutSourceAccessAndRecreationUsesViewModel() {
        val root = File(checkNotNull(System.getProperty("androidAppProjectDir")),
            "src/main/kotlin/com/phishtopia/ja2fieldkit/android")
        val activity = File(root, "MainActivity.kt").readText()
        val model = File(root, "InspectionViewModel.kt").readText()
        val methods = model.substringAfter("fun setPersonnelQuery(").substringBefore("private fun withCatalog(")
        for (forbidden in listOf("inspector", "importer", "executor", "ByteArray", "editSession", "resolver",
            "Preferences", "File", "Uri", "analytics", "http")) assertFalse(forbidden in methods)
        for (call in listOf("withPersonnelQuery(query)", "withPersonnelCategory(category)",
            "withPersonnelCurrentSquadOnly(enabled)", "clearPersonnelFilters()")) assertTrue("state.$call" in methods)
        assertEquals(4, Regex("""if \(next !== state\) publish\(next\)""").findAll(methods).count())
        val listUi = activity.substringAfter("private fun LinearLayout.addPersonnel(")
            .substringBefore("private fun LinearLayout.addDossier(")
        assertTrue("PersonnelCategoryFilter.entries.getOrNull(position)" in listUi)
        assertTrue("if (personnelCategory !== parent) return" in listUi)
        assertTrue("state.personnelFilter.results(state.personnel)" in listUi)
        assertTrue("No profiles match these filters." in listUi)
        assertTrue("PersonnelFilter.safeQuery(it.toString())" in listUi)
        assertTrue("model.setPersonnelQuery(safe)" in listUi)
        assertTrue("setText(state.personnelFilter.query)" in listUi)
        assertTrue("isChecked = state.personnelFilter.currentSquadOnly" in listUi)
        assertEquals(3, Regex("isSaveEnabled = false").findAll(listUi).count())
        assertTrue("IME_FLAG_NO_PERSONALIZED_LEARNING" in listUi)
        assertTrue("private val model: InspectionViewModel by viewModels()" in activity)
        assertTrue("model.attach(stateObserver)" in activity)
        assertTrue("model.detach(stateObserver)" in activity)
        assertTrue(activity.indexOf("if (updatePersonnelFilters(screenState)) return") <
            activity.indexOf("// A full render replaces the view tree"))
        for (forbidden in listOf("Preferences", "SavedStateHandle", "ByteArray", "inspector", "importer",
            "analytics", "http", "java.io")) assertFalse(forbidden in listUi)
    }

}
