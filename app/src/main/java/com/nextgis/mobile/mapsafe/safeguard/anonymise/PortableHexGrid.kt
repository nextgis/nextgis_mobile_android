package com.nextgis.mobile.mapsafe.safeguard.anonymise

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Native-free hexagonal grid used when the H3 JNI library is unavailable.
 *
 * The grid is calculated in Web Mercator metres. H3 resolution numbers are
 * mapped to their approximate average H3 edge lengths so the fallback remains
 * visually comparable to H3 at the same selected resolution.
 */
object PortableHexGrid {
    private const val CELL_PREFIX = "grid"
    private const val EARTH_RADIUS_METRES = 6_378_137.0
    private const val MAX_MERCATOR_LATITUDE = 85.05112878
    private val SQRT_THREE = sqrt(3.0)

    private val edgeLengthMetres = doubleArrayOf(
        1_281_256.0,
        483_056.0,
        182_513.0,
        68_979.0,
        26_071.0,
        9_854.0,
        3_724.0,
        1_406.0,
        531.0,
        201.0,
        75.9,
        28.7,
        10.8,
        4.09,
        1.55,
        0.584
    )

    private data class AxialCell(
        val resolution: Int,
        val q: Long,
        val r: Long
    ) {
        val id: String
            get() = "${CELL_PREFIX}_${resolution}_${q}_${r}"
    }

    fun isPortableCell(cellId: String): Boolean = cellId.startsWith("${CELL_PREFIX}_")

    fun pointToCell(latitude: Double, longitude: Double, resolution: Int): String {
        require(latitude.isFinite() && longitude.isFinite()) { "Coordinates must be finite." }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Coordinates are outside valid latitude/longitude ranges."
        }
        require(resolution in edgeLengthMetres.indices) { "Hex resolution must be between 0 and 15." }

        val (x, y) = toWebMercator(latitude, longitude)
        val size = edgeLengthMetres[resolution]
        val fractionalQ = (2.0 / 3.0 * x) / size
        val fractionalR = (-x / 3.0 + SQRT_THREE * y / 3.0) / size
        val (q, r) = roundAxial(fractionalQ, fractionalR)
        return AxialCell(resolution, q, r).id
    }

    /** Pair.first = latitude, Pair.second = longitude. */
    fun cellBoundaryToLatLon(cellId: String): List<Pair<Double, Double>> {
        val cell = parseCell(cellId)
        val size = edgeLengthMetres[cell.resolution]
        val centerX = size * 1.5 * cell.q
        val centerY = size * SQRT_THREE * (cell.r + cell.q / 2.0)

        return (0 until 6).map { vertex ->
            val angle = PI / 180.0 * (60.0 * vertex)
            val x = centerX + size * kotlin.math.cos(angle)
            val y = centerY + size * sin(angle)
            fromWebMercator(x, y)
        }
    }

    private fun parseCell(cellId: String): AxialCell {
        val parts = cellId.split('_')
        require(parts.size == 4 && parts[0] == CELL_PREFIX) { "Invalid portable hex cell ID: $cellId" }
        val resolution = parts[1].toIntOrNull()
        val q = parts[2].toLongOrNull()
        val r = parts[3].toLongOrNull()
        require(resolution != null && resolution in edgeLengthMetres.indices && q != null && r != null) {
            "Invalid portable hex cell ID: $cellId"
        }
        return AxialCell(resolution, q, r)
    }

    private fun roundAxial(q: Double, r: Double): Pair<Long, Long> {
        val cubeX = q
        val cubeZ = r
        val cubeY = -cubeX - cubeZ

        var roundedX = round(cubeX)
        var roundedY = round(cubeY)
        var roundedZ = round(cubeZ)

        val xDifference = abs(roundedX - cubeX)
        val yDifference = abs(roundedY - cubeY)
        val zDifference = abs(roundedZ - cubeZ)

        if (xDifference > yDifference && xDifference > zDifference) {
            roundedX = -roundedY - roundedZ
        } else if (yDifference > zDifference) {
            roundedY = -roundedX - roundedZ
        } else {
            roundedZ = -roundedX - roundedY
        }

        return Pair(roundedX.toLong(), roundedZ.toLong())
    }

    private fun toWebMercator(latitude: Double, longitude: Double): Pair<Double, Double> {
        val safeLatitude = max(-MAX_MERCATOR_LATITUDE, min(MAX_MERCATOR_LATITUDE, latitude))
        val x = EARTH_RADIUS_METRES * longitude * PI / 180.0
        val y = EARTH_RADIUS_METRES * ln(tan(PI / 4.0 + safeLatitude * PI / 360.0))
        return Pair(x, y)
    }

    private fun fromWebMercator(x: Double, y: Double): Pair<Double, Double> {
        val longitude = x / EARTH_RADIUS_METRES * 180.0 / PI
        val latitude = (2.0 * atan(exp(y / EARTH_RADIUS_METRES)) - PI / 2.0) * 180.0 / PI
        return Pair(latitude, longitude)
    }
}
