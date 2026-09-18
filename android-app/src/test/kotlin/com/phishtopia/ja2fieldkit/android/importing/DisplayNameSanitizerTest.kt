package com.phishtopia.ja2fieldkit.android.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayNameSanitizerTest {
    @Test
    fun pathLookingProviderNamesStillSelectTheLeaf() {
        assertEquals("campaign.sav", DisplayNameSanitizer.sanitize("private/path/campaign.sav"))
        assertEquals("save.sav", DisplayNameSanitizer.sanitize("C:\\private\\save.sav"))
    }

    @Test
    fun replacesControlsAndBidiOverride() {
        assertEquals("campaign .sav", DisplayNameSanitizer.sanitize("campaign\u202e.sav"))
        assertEquals("slot name.sav", DisplayNameSanitizer.sanitize("slot\tname.sav"))
        assertEquals("slot  name.sav", DisplayNameSanitizer.sanitize("slot\r\nname.sav"))
    }

    @Test
    fun replacesBidiIsolates() {
        assertEquals("report .sav", DisplayNameSanitizer.sanitize("\u2066report\u2069.sav"))
    }

    @Test
    fun replacesLineAndParagraphSeparators() {
        assertEquals("line paragraph.sav", DisplayNameSanitizer.sanitize("line\u2028paragraph.sav"))
        assertEquals("line paragraph.sav", DisplayNameSanitizer.sanitize("line\u2029paragraph.sav"))
    }

    @Test
    fun preservesOrdinaryPrintableUnicode() {
        assertEquals(
            "Crème-作戦-한국어-💾.sav",
            DisplayNameSanitizer.sanitize("Crème-作戦-한국어-💾.sav"),
        )
    }

    @Test
    fun allUnsafeInputUsesSafeFallback() {
        assertEquals("Selected save", DisplayNameSanitizer.sanitize("\u202e\u2066\u2069\u2028\u2029\n\t"))
    }

    @Test
    fun truncatesTo120CodePointsAfterSanitization() {
        val value = DisplayNameSanitizer.sanitize("A\u202e" + "😀".repeat(120))

        assertTrue(value.endsWith("…"))
        assertEquals(121, value.codePointCount(0, value.length))
        assertEquals("A " + "😀".repeat(118) + "…", value)
    }
}
