package com.phishtopia.ja2fieldkit.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source boundary check for the player-first success layout. */
class SuccessRenderOrderTest {
    private val activity = File(
        checkNotNull(System.getProperty("androidAppProjectDir")),
        "src/main/kotlin/com/phishtopia/ja2fieldkit/android/MainActivity.kt",
    ).readText()

    @Test
    fun successShowsCampaignAndSelectedMercBeforeTechnicalDetails() {
        val success = activity.substringAfter("is InspectionScreenState.Success -> {")
            .substringBefore("is InspectionScreenState.Failure -> {")
        val calls = Regex("content\\.add\\w+\\([^\\n]*\\)")
            .findAll(success).map { it.value }.toList()
        assertEquals(
            listOf(
                "content.addHeading(\"Inspection complete\")",
                "content.addCampaign(screenState.campaign)",
                "content.addHeading(\"Roster\")",
                "content.addBody(\"No roster members found.\")",
                "content.addMercSelector(screenState)",
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
