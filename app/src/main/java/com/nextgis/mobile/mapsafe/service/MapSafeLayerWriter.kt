package com.nextgis.mobile.mapsafe.service

import android.util.Log
import com.nextgis.maplib.datasource.Feature
import com.nextgis.maplib.datasource.GeoGeometry
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.Constants
import com.nextgis.maplib.util.GeoConstants

/**
 * Writes generated MapSafe features into a NextGIS Mobile VectorLayer.
 *
 * The current nextgis_mobile_android codebase uses the older maplib pattern:
 * VectorLayer + Android ContentResolver, not the newer SDK v3 API.
 */
object MapSafeLayerWriter {

    data class InsertResult(
        val attempted: Int,
        val inserted: Int,
        val failed: Int
    )

    data class FeatureToInsert(
        val geometry: GeoGeometry,
        val attributes: Map<String, Any?> = emptyMap()
    )

    fun insertFeatures(
        layer: VectorLayer,
        features: List<FeatureToInsert>
    ): InsertResult {
        val layerFields = layer.fields
        val prepared = ArrayList<Feature>(features.size)
        features.forEachIndexed { featureIndex, source ->
            val feature = Feature(Constants.NOT_FOUND.toLong(), layerFields).apply {
                geometry = source.geometry
            }
            source.attributes.forEach { (key, value) ->
                val storedValue = when (value) {
                    is Boolean -> if (value) 1 else 0
                    null, is String, is Int, is Long, is Float, is Double -> value
                    else -> value.toString()
                }
                if (!feature.setFieldValue(key, storedValue)) {
                    Log.w(TAG, "Generated feature ${featureIndex + 1} has no output field '$key'.")
                }
            }
            prepared.add(feature)
        }

        val inserted = try {
            layer.createFeaturesBatch(prepared)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not commit the generated feature batch.", error)
            0
        }
        val failed = features.size - inserted
        return InsertResult(
            attempted = features.size,
            inserted = inserted,
            failed = failed
        )
    }

    fun ensureWebMercator(geometry: GeoGeometry): GeoGeometry {
        if (geometry.crs != GeoConstants.CRS_WEB_MERCATOR) {
            geometry.project(GeoConstants.CRS_WEB_MERCATOR)
        }
        return geometry
    }

    private const val TAG = "MapSafeLayerWriter"
}
