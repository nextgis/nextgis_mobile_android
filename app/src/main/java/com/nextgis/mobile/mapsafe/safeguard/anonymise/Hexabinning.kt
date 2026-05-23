package com.nextgis.mobile.mapsafe.safeguard.anonymise

import com.uber.h3core.H3Core

/**
 * MapSafe H3 hexabinning support.
 */
object Hexabinning {
    const val VERSION = "0.3"

    private val h3: H3Core by lazy {
        H3Core.newInstance()
    }

    data class HexbinResult(
        val cellId: String,
        val pointCount: Int
    )

    fun pointToCell(
        latitude: Double,
        longitude: Double,
        resolution: Int
    ): String {
        return h3.latLngToCellAddress(latitude, longitude, resolution)
    }

    /**
     * Returns the H3 cell boundary as latitude/longitude pairs.
     * Pair.first = latitude, Pair.second = longitude.
     */
    fun cellBoundaryToLatLon(cellId: String): List<Pair<Double, Double>> {
        return h3.cellToBoundary(cellId).map { coord ->
            Pair(coord.lat, coord.lng)
        }
    }
}
