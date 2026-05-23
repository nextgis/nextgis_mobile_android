package com.nextgis.mobile.mapsafe.safeguard.anonymise

/**
 * MapSafe H3 hexabinning support.
 *
 * Future implementation will integrate the h3-java library.
 */
object Hexabinning {
    const val VERSION = "0.2"

    data class HexbinResult(
        val cellId: String,
        val pointCount: Int
    )

    fun pointToCell(
        latitude: Double,
        longitude: Double,
        resolution: Int
    ): String {
        return "H3_PLACEHOLDER_${resolution}_${latitude}_${longitude}"
    }

    fun cellBoundaryToPolygon(cellId: String): List<Pair<Double, Double>> {
        return emptyList()
    }
}
