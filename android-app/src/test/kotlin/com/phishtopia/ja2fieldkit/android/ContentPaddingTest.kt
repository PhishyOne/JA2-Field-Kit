package com.phishtopia.ja2fieldkit.android

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentPaddingTest {
    private val base = ContentPadding(left = 20, top = 20, right = 20, bottom = 32)
    private val insets = ContentInsets(left = 7, top = 24, right = 11, bottom = 36)

    @Test
    fun addsEveryInsetEdgeToDesignPadding() {
        assertEquals(
            ContentPadding(left = 27, top = 44, right = 31, bottom = 68),
            contentPadding(base, insets),
        )
    }

    @Test
    fun repeatedInsetSnapshotsDoNotAccumulatePadding() {
        val firstDispatch = contentPadding(base, insets)
        val repeatedDispatch = contentPadding(base, insets)

        assertEquals(firstDispatch, repeatedDispatch)
    }

    @Test
    fun aChangedSnapshotIsStillAppliedToTheOriginalBase() {
        assertEquals(
            ContentPadding(left = 33, top = 20, right = 20, bottom = 80),
            contentPadding(
                base,
                ContentInsets(left = 13, top = 0, right = 0, bottom = 48),
            ),
        )
    }
}
