package com.phishtopia.ja2fieldkit.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source boundary checks complement the executable presentation transition tests. */
class MercSelectionWiringTest {
    private val sourceDir = File(checkNotNull(System.getProperty("androidAppProjectDir")),
        "src/main/kotlin/com/phishtopia/ja2fieldkit/android")
    private val model = File(sourceDir, "InspectionViewModel.kt").readText()
    private val activity = File(sourceDir, "MainActivity.kt").readText()

    @Test
    fun selectActionAcceptsOnlyAnIdAndCannotImportOrInspect() {
        val action = model.substringAfter("fun selectMerc(").substringBefore("private fun")
        assertTrue(action.startsWith("profileIndex: Int)"))
        assertTrue(action.contains("state.withSelectedMerc(profileIndex)"))
        assertTrue(action.contains("if (next !== state) publish(next)"))
        listOf("ByteArray", "resolver", "uri", "inspector", "importer", "executor", "inspect(").forEach {
            assertFalse(action.contains(it), it)
        }
    }

    @Test
    fun activityRecreationReattachesToViewModelStateWithoutResettingSelection() {
        assertTrue(activity.contains("InspectionViewModel by viewModels()"))
        assertTrue(activity.contains("model.attach(stateObserver)"))
        assertTrue(activity.contains("model.detach(stateObserver)"))
        assertTrue(activity.contains("if (savedInstanceState == null) intent else null"))
        val attach = model.substringAfter("fun attach(").substringBefore("fun detach(")
        assertTrue(attach.contains("nextObserver(state)"))
        assertFalse(attach.contains("state ="))
        val detach = model.substringAfter("fun detach(").substringBefore("fun inspect(")
        assertFalse(detach.contains("state ="))
    }

    @Test
    fun selectorRendersOnlySelectedDetailAndLeavesSafePaddingAndSelectableText() {
        val selector = activity.substringAfter("private fun LinearLayout.addMercSelector(")
            .substringBefore("private fun MercPresentation.displayName")
        assertEquals(1, Regex("addMerc\\(").findAll(selector).count())
        assertTrue(selector.contains("addMerc(selected)"))
        assertTrue(selector.contains("labelFor = selector.id"))
        assertTrue(selector.contains("contentDescription = \"Selected merc\""))
        assertTrue(selector.contains("isSaveEnabled = false"))
        assertTrue(selector.contains("merc.profileIndex != selected.profileIndex"))
        assertTrue(activity.contains("No roster members found."))
        assertTrue(activity.contains("applySafeContentPadding("))
        assertTrue(activity.contains("setTextIsSelectable(true)"))
    }
}
