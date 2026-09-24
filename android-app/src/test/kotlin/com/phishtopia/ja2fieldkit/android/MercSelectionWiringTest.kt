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
        assertTrue(selector.contains("model.selectMerc(merc.profileIndex)"))
        assertFalse(selector.contains("merc.profileIndex != selected.profileIndex"))
        assertTrue(selector.contains("if (mercSelector !== parent) return"))
        assertTrue(activity.contains("No roster members found."))
        assertTrue(activity.contains("applySafeContentPadding("))
        assertTrue(activity.contains("setTextIsSelectable(true)"))
    }

    @Test
    fun selectionOnlyRenderReturnsBeforeReplacingScrollOrSelector() {
        val render = activity.substringAfter("private fun render(")
            .substringBefore("private fun updateMercSelection(")
        assertTrue(render.substringBefore("val content =").contains(
            "if (updateMercSelection(screenState)) return"))
        assertTrue(render.indexOf("if (updateMercSelection(screenState)) return") <
            render.indexOf("setContentView(root)"))
        assertEquals(1, Regex("setContentView\\(").findAll(activity).count())

        val update = activity.substringAfter("private fun updateMercSelection(")
            .substringBefore("private fun View.applySafeContentPadding")
        listOf(
            "val previous = renderedSuccess ?: return false",
            "screenState !is InspectionScreenState.Success",
            "screenState.selectedProfileIndex == previous.selectedProfileIndex",
            "screenState.roster !== previous.roster",
            "screenState != previous.copy(selectedProfileIndex = screenState.selectedProfileIndex)",
            "val selector = mercSelector ?: return false",
            "val detail = selectedMercDetail ?: return false",
            "val selected = screenState.selectedMerc ?: return false",
            "renderedSuccess = screenState",
            "detail.removeAllViews()",
            "detail.addMerc(selected)",
            "if (selector.selectedItemPosition != position) selector.setSelection(position)",
            "return true",
        ).forEach { assertTrue(update.contains(it), it) }
        // This path can mutate only the retained detail's children and selector position.
        listOf("setContentView", "ScrollView(", "Spinner(", "LinearLayout(",
            "mercSelector =", "selectedMercDetail =", "removeView", "requestFocus",
            "scrollTo", "scrollBy").forEach { assertFalse(update.contains(it), it) }
    }

    @Test
    fun fullRenderInvalidatesCachedViewsAndBuildsDedicatedDetailContainer() {
        val render = activity.substringAfter("private fun render(")
            .substringBefore("private fun updateMercSelection(")
        val beforeBuilding = render.substringBefore("val content =")
        listOf("renderedSuccess = null", "mercSelector = null", "selectedMercDetail = null")
            .forEach { assertTrue(beforeBuilding.contains(it), it) }
        assertTrue(render.substringAfter("setContentView(root)").contains(
            "renderedSuccess = screenState as? InspectionScreenState.Success"))
        val selector = activity.substringAfter("private fun LinearLayout.addMercSelector(")
            .substringBefore("private fun MercPresentation.displayName")
        assertTrue(selector.contains("val selected = state.selectedMerc ?: return"))
        assertTrue(selector.contains("mercSelector = selector"))
        assertTrue(selector.contains("selectedMercDetail = LinearLayout(this@MainActivity).apply"))
        assertTrue(selector.substringAfter("selectedMercDetail =").contains("addMerc(selected)"))
    }
}
