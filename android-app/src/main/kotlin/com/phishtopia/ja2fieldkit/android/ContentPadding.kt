package com.phishtopia.ja2fieldkit.android

internal data class ContentPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class ContentInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun contentPadding(base: ContentPadding, insets: ContentInsets): ContentPadding =
    ContentPadding(
        left = base.left + insets.left,
        top = base.top + insets.top,
        right = base.right + insets.right,
        bottom = base.bottom + insets.bottom,
    )
