package com.phishtopia.ja2fieldkit.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** View wiring checks complement executable grouping and selection tests. */
class InventoryGroupingWiringTest {
    private val project = File(checkNotNull(System.getProperty("androidAppProjectDir")))
    private val source = File(project, "src/main/kotlin/com/phishtopia/ja2fieldkit/android")
    private val activity = File(source, "MainActivity.kt").readText()

    @Test
    fun rendererValidatesBeforeRenderingAndUsesWrappingVisibleTwoColumnCells() {
        val render = activity.substringAfter("addHeading(\"Inventory\", 17f)")
            .substringBefore("private fun LinearLayout.addTitle")
        assertTrue(render.contains("groupInventory(merc.inventory)"))
        val rejection = render.substringAfter("if (groups == null)").substringBefore("groups.forEach")
        assertTrue(rejection.contains("Inventory unavailable: inconsistent slots."))
        assertTrue(rejection.contains("return"))
        assertTrue(render.contains("group.slots.chunked(2)"))
        assertTrue(render.contains("textView(slot.visibleText(), 16f)"))
        assertTrue(render.contains("LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)"))
        assertTrue(activity.contains("setTextIsSelectable(true)"))
        listOf("HorizontalScrollView", "RecyclerView", "setSingleLine", "ellipsize", "setHorizontallyScrolling")
            .forEach { assertFalse(activity.contains(it), it) }
    }

    @Test
    fun inventoryUsesNoCatalogPayloadOrAdditionalUiDependency() {
        val grouping = File(source, "presentation/InventoryGrouping.kt").readText()
        val render = activity.substringAfter("addHeading(\"Inventory\", 17f)")
            .substringBefore("private fun LinearLayout.addTitle")
        listOf("InventoryObject", "InventoryPayload", "ByteArray", "itemName", "icon", "condition",
            "ammo", "attachment", "weight", "catalog").forEach {
            assertFalse((grouping + render).contains(it, ignoreCase = true), it)
        }
        val dependencies = File(project, "build.gradle.kts").readText().substringAfter("dependencies {")
        val lines = dependencies.lines().map { it.trim() }.filter { it.contains("implementation(", true) }
        kotlin.test.assertEquals(listOf("implementation(project(\":core\"))",
            "implementation(\"androidx.activity:activity-ktx:1.13.0\")",
            "testImplementation(\"org.jetbrains.kotlin:kotlin-test-junit5:2.4.10\")"), lines)
    }
}
