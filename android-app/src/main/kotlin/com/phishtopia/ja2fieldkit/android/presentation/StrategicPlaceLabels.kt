package com.phishtopia.ja2fieldkit.android.presentation

import java.util.Collections

// Project-authored, pinned standard-map metadata. See docs/strategic-place-label-evidence.md.
private val surfacePlaceLabels: Map<String, String> = Collections.unmodifiableMap(mapOf(
    "A9" to "Omerta",
    "A10" to "Omerta",
    "B13" to "Drassen Airport",
    "C13" to "Drassen",
    "D13" to "Drassen",
    "H13" to "Alma",
    "H14" to "Alma",
    "I13" to "Alma",
    "I14" to "Alma",
    "G1" to "Grumm",
    "G2" to "Grumm",
    "H1" to "Grumm",
    "H2" to "Grumm",
    "H3" to "Grumm",
    "F8" to "Cambria Hospital",
    "F9" to "Cambria",
    "G8" to "Cambria",
    "G9" to "Cambria",
    "H8" to "Cambria",
    "C5" to "San Mona",
    "C6" to "San Mona",
    "D4" to "San Mona",
    "D5" to "San Mona",
    "I6" to "Estoni",
    "L11" to "Balime",
    "L12" to "Balime",
    "N3" to "Meduna Airport",
    "N5" to "Meduna",
    "O3" to "Meduna",
    "O4" to "Meduna",
    "P3" to "Meduna",
    "A2" to "Chitzena",
    "B2" to "Chitzena",
))

internal const val STANDARD_MAP_PLACE_NOTE = "Place names assume the standard map."

/** Presentation only: callers supply already-qualified canonical sector coordinates. */
internal fun decoratedSector(x: Int, y: Int, z: Int): String {
    val coordinate = "${'A' + y - 1}$x" + if (z == 0) "" else "-$z"
    if (z != 0) return coordinate
    val label = surfacePlaceLabels[coordinate] ?: return coordinate
    return "$coordinate — $label"
}
