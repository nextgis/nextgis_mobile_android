package com.nextgis.mobile.mapsafe.service

import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.util.Geo
import com.nextgis.mobile.mapsafe.safeguard.anonymise.DonutMasking

/**
 * Converts existing point geometries into masked point geometries.
 */
object DonutMaskingProcessor {

    data class MaskingStatistics(
        val totalPoints: Int,
        val maskedPoints: Int,
        val averageDistanceMetres: Double
    )

    data class ProcessResult(
        val features: List<MapSafeLayerWriter.FeatureToInsert>,
        val statistics: MaskingStatistics
    )

    fun processPoints(
        points: List<Pair<GeoPoint, Map<String, Any?>>>,
        minDistanceMetres: Double,
        maxDistanceMetres: Double
    ): ProcessResult {

        val output = mutableListOf<MapSafeLayerWriter.FeatureToInsert>()
        var totalDistance = 0.0

        for ((point, attributes) in points) {

            val lon = Geo.mercatorToWgs84Lon(point.x)
            val lat = Geo.mercatorToWgs84Lat(point.y)

            val masked = DonutMasking.maskPoint(
                lon,
                lat,
                minDistanceMetres,
                maxDistanceMetres
            )

            val maskedPoint = GeoPoint(
                Geo.wgs84ToMercatorX(masked.longitude),
                Geo.wgs84ToMercatorY(masked.latitude)
            )

            totalDistance += masked.distanceMetres

            output.add(
                MapSafeLayerWriter.FeatureToInsert(
                    geometry = maskedPoint,
                    attributes = attributes
                )
            )
        }

        val averageDistance = if (points.isNotEmpty()) {
            totalDistance / points.size
        } else {
            0.0
        }

        return ProcessResult(
            features = output,
            statistics = MaskingStatistics(
                totalPoints = points.size,
                maskedPoints = output.size,
                averageDistanceMetres = averageDistance
            )
        )
    }
}
