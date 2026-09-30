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
        for (forbidden in listOf("editMerc", "export", "inspector", "ByteArray", "MercProfile")) {
            assertFalse(dossier.contains(forbidden))
        }
        val navigation = model.substringAfter("fun openPersonnel(").substringBefore("private fun withCatalog(")
        for (forbidden in listOf("inspector", "editSession", "resolver", "ByteArray", "SavedStateHandle")) {
            assertFalse(navigation.contains(forbidden))
        }
        assertTrue(navigation.contains("state.openPersonnel(profileId)"))
        assertTrue(navigation.contains("state.closePersonnel()"))
    }
}
