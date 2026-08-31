package com.phishtopia.ja2fieldkit.core.format

enum class SaveFamily {
    JA2_REBORN,
    STRACCIATELLA,
    CLASSIC,
    UNKNOWN,
}

enum class DetectionConfidence {
    CONFIRMED,
    PROBABLE,
    UNKNOWN,
}

data class SaveFormatDetection(
    val family: SaveFamily,
    val saveVersion: Int? = null,
    val buildLabel: String? = null,
    val confidence: DetectionConfidence = DetectionConfidence.UNKNOWN,
    val evidence: List<String> = emptyList(),
)
