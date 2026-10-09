package com.phishtopia.ja2fieldkit.android.presentation

import com.phishtopia.ja2fieldkit.core.model.LiveMercLocation

internal fun LiveMercLocation.displayLocation(): String = when (this) {
    is LiveMercLocation.Sector -> "${'A' + y - 1}$x" + if (z == 0) "" else "-$z"
    LiveMercLocation.InTransit -> "In transit"
    LiveMercLocation.Prisoner -> "POW — location unknown"
    LiveMercLocation.Dead -> "Dead"
    LiveMercLocation.InVehicle -> "In vehicle"
    LiveMercLocation.Unavailable -> "Unavailable"
}
