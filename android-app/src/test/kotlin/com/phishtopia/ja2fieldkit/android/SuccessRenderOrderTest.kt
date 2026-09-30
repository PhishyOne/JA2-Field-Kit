package com.phishtopia.ja2fieldkit.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source boundary check for the player-first success layout. */
class SuccessRenderOrderTest {
    private val activity = File(
        checkNotNull(System.getProperty("androidAppProjectDir")),
        "src/main/kotlin/com/phishtopia/ja2fieldkit/android/MainActivity.kt",
    ).readText()

    @Test
    fun mercDetailsRenderOneStatsSectionAndAtMostOneLegendWithSelectableRows() {
        val detail = activity.substringAfter("private fun LinearLayout.addMerc(merc:")
            .substringBefore("private fun LinearLayout.addTitle")
        assertEquals(1, Regex("addHeading\\(\"Stats\"").findAll(detail).count())
        assertEquals(1, Regex("merc.statsLegend").findAll(detail).count())
        assertTrue(detail.contains("merc.statsLegend?.let { addBody(it) }"))
        assertTrue(detail.contains("merc.stats.joinToString(\"\\n\")"))
        assertFalse(detail.contains("Live/current tactical stats"))
        assertFalse(detail.contains("Profile/base stats"))
        assertTrue(activity.contains("setTextIsSelectable(true)"))
        assertTrue(activity.contains("addLabelValue(\"Sector\", campaign.sector)"))
    }

    @Test
    fun successShowsCampaignAndSelectedMercBeforeTechnicalDetails() {
        assertTrue(activity.contains("content.addHeading(\"Inspection complete\")"))
        val success = activity.substringAfter("content.addHeading(\"Inspection complete\")")
            .substringBefore("is InspectionScreenState.Failure -> {")
        val calls = Regex("content\\.add\\w+\\([^\\n]*\\)")
            .findAll(success).map { it.value }.toList()
        assertEquals(
            listOf(
                "content.addCampaign(screenState.campaign)",
                "content.addView(Button(this)",
                "content.addHeading(\"Roster\")",
                "content.addBody(\"No roster members found.\")",
                "content.addMercSelector(screenState)",
                "content.addBody(screenState.catalog.status)",
                "content.addView(Button(this)",
                "content.addHeading(\"Technical details\")",
                "content.addSource(screenState.source)",
                "content.addFormat(screenState.format)",
                "content.addOpenButton(R.string.open_another_save)",
            ),
            calls,
        )
        assertTrue(success.contains(
            "if (screenState.roster.isEmpty()) content.addBody(\"No roster members found.\")\n" +
                "                else content.addMercSelector(screenState)",
        ))
    }
}
