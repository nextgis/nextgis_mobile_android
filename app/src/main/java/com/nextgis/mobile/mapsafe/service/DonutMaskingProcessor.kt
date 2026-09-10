package com.nextgis.mobile.mapsafe.service

import android.os.SystemClock
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.datasource.Geo
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.mapsafe.safeguard.anonymise.DonutMasking
import com.nextgis.mobile.mapsafe.safeguard.anonymise.SpruillMeasure

/**
 * Converts existing point geometries into masked point geometries.
 */
object DonutMaskingProcessor {

    data class SourcePoint(
        val point: GeoPoint,
        val attributes: Map<String, Any?>
    )

    data class MaskingStatistics(
        val totalPoints: Int,
        val maskedPoints: Int,
        val averageDistanceMetres: Double,
        val spruillMeasure: SpruillMeasure.Result,
        val maskingDurationNanos: Long,
        val spruillDurationNanos: Long,
        val featureConstructionDurationNanos: Long
    )

    data class ProcessResult(
        val features: List<MapSafeLayerWriter.FeatureToInsert>,
        val statistics: MaskingStatistics
    )

    fun processPoints(
        points: List<SourcePoint>,
        minDistanceMetres: Double,
        maxDistanceMetres: Double,
        maskMinField: String,
        maskMaxField: String,
        maskDistanceField: String
    ): ProcessResult {

        val originalCoordinates = ArrayList<SpruillMeasure.Coordinate>(points.size)
        val maskedCoordinates = ArrayList<SpruillMeasure.Coordinate>(points.size)
        val maskedResults = ArrayList<DonutMasking.Result>(points.size)
        var totalDistance = 0.0

        // The core timer intentionally contains coordinate work only: normalising the
        // source CRS, drawing a secure random displacement, and calculating the
        // spherical destination coordinate. Attribute copying and output-layer feature
        // construction are timed separately below.
        val maskingStartedAt = SystemClock.elapsedRealtimeNanos()

        for ((point) in points) {
            val (lon, lat) = when (point.crs) {
                GeoConstants.CRS_WEB_MERCATOR -> Pair(
                    Geo.mercatorToWgs84SphereX(point.x),
                    Geo.mercatorToWgs84SphereY(point.y)
                )
                GeoConstants.CRS_WGS84 -> Pair(point.x, point.y)
                else -> throw IllegalArgumentException(
                    "Unsupported point CRS ${point.crs}; MapSafe supports EPSG:3857 and EPSG:4326."
                )
            }

            val masked = DonutMasking.maskPoint(
                lon,
                lat,
                minDistanceMetres,
                maxDistanceMetres
            )

            originalCoordinates.add(SpruillMeasure.Coordinate(lon, lat))
            maskedCoordinates.add(SpruillMeasure.Coordinate(masked.longitude, masked.latitude))
            maskedResults.add(masked)
            totalDistance += masked.distanceMetres
        }
        val maskingDurationNanos = SystemClock.elapsedRealtimeNanos() - maskingStartedAt

        val averageDistance = if (points.isNotEmpty()) {
            totalDistance / points.size
        } else {
            0.0
        }
        val spruillStartedAt = SystemClock.elapsedRealtimeNanos()
        val spruillMeasure = SpruillMeasure.calculate(originalCoordinates, maskedCoordinates)
        val spruillDurationNanos = SystemClock.elapsedRealtimeNanos() - spruillStartedAt

        val featureConstructionStartedAt = SystemClock.elapsedRealtimeNanos()
        val output = ArrayList<MapSafeLayerWriter.FeatureToInsert>(points.size)
        points.forEachIndexed { index, source ->
            val masked = maskedResults[index]
            val maskedPoint = GeoPoint(
                Geo.wgs84ToMercatorSphereX(masked.longitude),
                Geo.wgs84ToMercatorSphereY(masked.latitude)
            ).apply {
                crs = GeoConstants.CRS_WEB_MERCATOR
            }

            val outputAttributes = LinkedHashMap(source.attributes)
            outputAttributes[maskMinField] = minDistanceMetres
            outputAttributes[maskMaxField] = maxDistanceMetres
            outputAttributes[maskDistanceField] = masked.distanceMetres

            output.add(
                MapSafeLayerWriter.FeatureToInsert(
                    geometry = maskedPoint,
                    attributes = outputAttributes
                )
            )
        }
        val featureConstructionDurationNanos =
            SystemClock.elapsedRealtimeNanos() - featureConstructionStartedAt

        return ProcessResult(
            features = output,
            statistics = MaskingStatistics(
                totalPoints = points.size,
                maskedPoints = output.size,
                averageDistanceMetres = averageDistance,
                spruillMeasure = spruillMeasure,
                maskingDurationNanos = maskingDurationNanos,
                spruillDurationNanos = spruillDurationNanos,
                featureConstructionDurationNanos = featureConstructionDurationNanos
            )
        )
    }
}
