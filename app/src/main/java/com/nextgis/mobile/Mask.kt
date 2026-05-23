package com.nextgis.mobile

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * MapSafe donut masking helper.
 *
 * Coordinates are expected in WGS84 longitude/latitude degrees.
 * Distances are expected in metres.
 */
object Mask {
    private const val EARTH_RADIUS_METRES = 6371000.0

    data class MaskedPoint(
        val longitude: Double,
        val latitude: Double,
        val distanceMetres: Double
    )

    fun maskPoint(
        longitude: Double,
        latitude: Double,
        minDistanceMetres: Double,
        maxDistanceMetres: Double
    ): MaskedPoint {
        val safeMin = minOf(minDistanceMetres, maxDistanceMetres).coerceAtLeast(0.0)
        val safeMax = maxOf(minDistanceMetres, maxDistanceMetres).coerceAtLeast(safeMin)
        val distance = safeMin + Math.random() * (safeMax - safeMin)
        val bearing = Math.random() * 2.0 * PI

        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)
        val angularDistance = distance / EARTH_RADIUS_METRES

        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing)
        )

        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )

        val normalizedLon = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        val maskedLat = Math.toDegrees(lat2).coerceIn(-90.0, 90.0)

        return MaskedPoint(
            longitude = normalizedLon,
            latitude = maskedLat,
            distanceMetres = distance
        )
    }
}
