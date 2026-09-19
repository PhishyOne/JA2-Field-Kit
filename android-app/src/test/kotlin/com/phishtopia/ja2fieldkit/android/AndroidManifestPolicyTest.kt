package com.phishtopia.ja2fieldkit.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidManifestPolicyTest {
    private val manifest = File(
        checkNotNull(System.getProperty("androidAppProjectDir")),
        "src/main/AndroidManifest.xml",
    )
    private val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(manifest)

    @Test
    fun requestsNoPermissions() {
        assertEquals(0, document.getElementsByTagName("uses-permission").length)
        assertEquals(0, document.getElementsByTagName("uses-permission-sdk-23").length)
    }

    @Test
    fun exposesNoWriteOrDocumentCreationAction() {
        val actions = document.getElementsByTagName("action")
        val names = (0 until actions.length).map { index ->
            actions.item(index).attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue
        }
        assertFalse(names.any { it.contains("CREATE_DOCUMENT") || it.endsWith(".EDIT") })
        assertTrue(names.contains("android.intent.action.VIEW"))
        assertTrue(names.contains("android.intent.action.SEND"))
    }

    @Test
    fun viewFiltersAcceptOnlyContentUris() {
        val dataNodes = document.getElementsByTagName("data")
        val schemes = (0 until dataNodes.length).mapNotNull { index ->
            dataNodes.item(index).attributes.getNamedItemNS(ANDROID_NAMESPACE, "scheme")?.nodeValue
        }
        assertTrue(schemes.isNotEmpty())
        assertEquals(setOf("content"), schemes.toSet())
    }

    @Test
    fun reportShareIsTextOnlyWithoutUriClipDataOrGrantFlags() {
        val activity = File(
            checkNotNull(System.getProperty("androidAppProjectDir")),
            "src/main/kotlin/com/phishtopia/ja2fieldkit/android/MainActivity.kt",
        ).readText()
        val shareBlock = activity.substringAfter("val send = Intent(Intent.ACTION_SEND)")
            .substringBefore("startActivity(")

        assertTrue(shareBlock.contains("type = payload.mimeType"))
        assertTrue(shareBlock.contains("putExtra(Intent.EXTRA_TEXT, payload.text)"))
        assertFalse(shareBlock.contains("ClipData"))
        assertFalse(shareBlock.contains("Uri"))
        assertFalse(shareBlock.contains("flags"))
        assertFalse(shareBlock.contains("FLAG_GRANT"))
    }

    @Test
    fun clipboardWriteExistsOnlyInsideExplicitCopyClickHandler() {
        val activity = File(
            checkNotNull(System.getProperty("androidAppProjectDir")),
            "src/main/kotlin/com/phishtopia/ja2fieldkit/android/MainActivity.kt",
        ).readText()
        val copyButton = activity.substringAfter("setText(R.string.copy_compatibility_report)")
            .substringBefore("setText(R.string.share_compatibility_report)")

        assertEquals(1, Regex("setPrimaryClip").findAll(activity).count())
        assertTrue(copyButton.indexOf("setOnClickListener") < copyButton.indexOf("setPrimaryClip"))
        assertTrue(copyButton.contains("preview.clipboardText"))
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
