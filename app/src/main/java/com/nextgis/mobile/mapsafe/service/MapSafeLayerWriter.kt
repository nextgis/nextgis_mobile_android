package com.nextgis.mobile.mapsafe.service

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import com.nextgis.maplib.datasource.GeoGeometry
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.Constants
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.maplib.api.IGISApplication
import java.io.IOException

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

    fun buildLayerUri(app: IGISApplication, layer: VectorLayer): Uri {
        return Uri.parse("content://${app.authority}/${layer.path.name}")
    }

    fun insertFeatures(
        resolver: ContentResolver,
        layerUri: Uri,
        features: List<FeatureToInsert>
    ): InsertResult {
        var inserted = 0
        var failed = 0

        for (feature in features) {
            val values = ContentValues()

            try {
                values.put(Constants.FIELD_GEOM, feature.geometry.toBlob())
            } catch (e: IOException) {
                failed++
                continue
            }

            for ((key, value) in feature.attributes) {
                when (value) {
                    null -> values.putNull(key)
                    is String -> values.put(key, value)
                    is Int -> values.put(key, value)
                    is Long -> values.put(key, value)
                    is Float -> values.put(key, value)
                    is Double -> values.put(key, value)
                    is Boolean -> values.put(key, if (value) 1 else 0)
                    else -> values.put(key, value.toString())
                }
            }

            val result = resolver.insert(layerUri, values)
            if (result != null) inserted++ else failed++
        }

        resolver.notifyChange(layerUri, null)
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
}
