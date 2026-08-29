package com.nextgis.mobile.mapsafe.safeguard.anonymise

import com.uber.h3core.H3Core

/**
 * MapSafe H3 hexabinning support.
 */
object Hexabinning {
    const val VERSION = "0.4"

    enum class Engine(val fieldValue: String, val displayName: String) {
        H3("h3", "H3"),
        PORTABLE_GRID("portable_grid", "portable hex grid")
    }

    private val h3: H3Core? by lazy {
        try {
            H3Core.newSystemInstance()
        } catch (_: LinkageError) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    val engine: Engine
        get() = if (h3 != null) Engine.H3 else Engine.PORTABLE_GRID

    data class HexbinResult(
        val cellId: String,
        val pointCount: Int
    )

    fun pointToCell(
        latitude: Double,
        longitude: Double,
        resolution: Int
    ): String {
        return h3?.latLngToCellAddress(latitude, longitude, resolution)
            ?: PortableHexGrid.pointToCell(latitude, longitude, resolution)
    }

    /**
     * Returns the H3 cell boundary as latitude/longitude pairs.
     * Pair.first = latitude, Pair.second = longitude.
     */
    fun cellBoundaryToLatLon(cellId: String): List<Pair<Double, Double>> {
        if (PortableHexGrid.isPortableCell(cellId)) {
            return PortableHexGrid.cellBoundaryToLatLon(cellId)
        }

        val nativeH3 = requireNotNull(h3) { "The H3 native library is unavailable on this device." }
        return nativeH3.cellToBoundary(cellId).map { coord -> Pair(coord.lat, coord.lng) }
    }
}
