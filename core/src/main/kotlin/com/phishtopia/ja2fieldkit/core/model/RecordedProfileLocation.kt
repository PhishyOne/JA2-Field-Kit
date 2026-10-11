package com.phishtopia.ja2fieldkit.core.model

/** Persistent profile sector, not live presence and not covered by the profile checksum. */
sealed interface RecordedProfileLocation {
    data class Sector(val x: Int, val y: Int, val z: Int) : RecordedProfileLocation {
        init { require(x in 1..16 && y in 1..16 && z in 0..3) }
    }
    data object Unavailable : RecordedProfileLocation
}
