package com.phishtopia.ja2fieldkit.core.model

/** Saved-state location, not a running-game connection or checksum-authenticated fact. */
sealed interface LiveMercLocation {
    data class Sector(val x: Int, val y: Int, val z: Int) : LiveMercLocation {
        init { require(x in 1..16 && y in 1..16 && z in 0..3) }
    }
    data object InTransit : LiveMercLocation
    data object Prisoner : LiveMercLocation
    data object Dead : LiveMercLocation
    data object InVehicle : LiveMercLocation
    data object Unavailable : LiveMercLocation
}
