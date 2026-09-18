package com.phishtopia.ja2fieldkit.android.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayNameSanitizerTest {
    @Test
    fun keepsOnlyALeafAndRemovesControlCharacters() {
        assertEquals("campaign.sav", DisplayNameSanitizer.sanitize("private/path/\ncampaign.sav"))
        assertEquals("save.sav", DisplayNameSanitizer.sanitize("C:\\private\\save.sav"))
    }

    @Test
    fun usesSafeFallbackAndCodePointBound() {
        assertEquals("Selected save", DisplayNameSanitizer.sanitize("\n\t"))
        val value = DisplayNameSanitizer.sanitize("😀".repeat(121))
        assertTrue(value.endsWith("…"))
        assertEquals(121, value.codePointCount(0, value.length))
        assertFalse(value.contains('\n'))
    }
}
