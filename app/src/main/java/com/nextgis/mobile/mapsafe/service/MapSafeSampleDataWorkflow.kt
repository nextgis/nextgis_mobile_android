package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.Field
import com.nextgis.maplib.datasource.Geo
import com.nextgis.maplib.datasource.GeoEnvelope
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import org.json.JSONObject

/** Loads the bundled synthetic point dataset into a regular local vector layer. */
object MapSafeSampleDataWorkflow {

    private const val ASSET_PATH = "mapsafe/sample_points_suva.geojson"
    private const val BASE_LAYER_NAME = "MapSafe sample points - Suva"

    data class WorkflowResult(
        val layer: VectorLayer,
        val layerName: String,
        val attempted: Int,
        val inserted: Int,
        val failed: Int,
        val extent: GeoEnvelope
    )

    fun createSampleLayer(context: Context, app: MainApplication): WorkflowResult {
        val features = readFeatures(context)
        require(features.isNotEmpty()) { "The bundled sample dataset is empty." }

        val map: MapBase = app.map
        val layerName = uniqueLayerName(map, BASE_LAYER_NAME)
        val fields = listOf(
            Field(GeoConstants.FTInteger, "site_id", "Site ID"),
            Field(GeoConstants.FTString, "site_name", "Site name"),
            Field(GeoConstants.FTString, "category", "Category"),
            Field(GeoConstants.FTString, "sensitivity", "Sensitivity"),
            Field(GeoConstants.FTInteger, "households", "Households")
        )

        val layer = app.createEmptyVectorLayer(
            layerName,
            null,
            GeoConstants.GTPoint,
            fields
        )
        require(layer.geometryType == GeoConstants.GTPoint) {
            "The sample layer was not created as a point-vector layer."
        }

        var addedToMap = false
        try {
            map.addLayer(layer)
            addedToMap = true
            map.save()

            val insertResult = MapSafeLayerWriter.insertFeatures(
                context.contentResolver,
                MapSafeLayerWriter.buildLayerUri(app, layer),
                features
            )
            require(insertResult.attempted == features.size) {
                "Expected ${features.size} sample points but attempted ${insertResult.attempted}."
            }
            require(insertResult.inserted == features.size && insertResult.failed == 0) {
                "Only ${insertResult.inserted}/${features.size} sample points were inserted."
            }

            layer.notifyLayerChanged()
            map.save()

            val extent = GeoEnvelope()
            features.forEach { feature -> extent.merge(feature.geometry.envelope) }
            require(extent.isInit) { "The sample dataset extent could not be calculated." }

            return WorkflowResult(
                layer = layer,
                layerName = layerName,
                attempted = insertResult.attempted,
                inserted = insertResult.inserted,
                failed = insertResult.failed,
                extent = extent
            )
        } catch (error: Throwable) {
            if (addedToMap) {
                runCatching {
                    map.removeLayer(layer)
                    map.save()
                }
            }
            runCatching { layer.delete(true) }
            throw error
        }
    }

    private fun readFeatures(context: Context): List<MapSafeLayerWriter.FeatureToInsert> {
        val json = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        require(root.optString("type") == "FeatureCollection") {
            "The bundled sample dataset is not a GeoJSON FeatureCollection."
        }

        val result = mutableListOf<MapSafeLayerWriter.FeatureToInsert>()
        val sourceFeatures = root.getJSONArray("features")

        for (index in 0 until sourceFeatures.length()) {
            val feature = sourceFeatures.getJSONObject(index)
            val geometry = feature.getJSONObject("geometry")
            require(geometry.optString("type") == "Point") {
                "Sample feature ${index + 1} is not a point."
            }

            val coordinates = geometry.getJSONArray("coordinates")
            val longitude = coordinates.getDouble(0)
            val latitude = coordinates.getDouble(1)
            require(longitude in -180.0..180.0 && latitude in -90.0..90.0) {
                "Sample feature ${index + 1} has invalid coordinates."
            }

            val point = GeoPoint(
                Geo.wgs84ToMercatorSphereX(longitude),
                Geo.wgs84ToMercatorSphereY(latitude)
            ).apply {
                crs = GeoConstants.CRS_WEB_MERCATOR
            }

            val properties = feature.getJSONObject("properties")
            result.add(
                MapSafeLayerWriter.FeatureToInsert(
                    geometry = point,
                    attributes = mapOf(
                        "site_id" to properties.getInt("site_id"),
                        "site_name" to properties.getString("site_name"),
                        "category" to properties.getString("category"),
                        "sensitivity" to properties.getString("sensitivity"),
                        "households" to properties.getInt("households")
                    )
                )
            )
        }

        return result
    }

    private fun uniqueLayerName(map: MapBase, baseName: String): String {
        var candidate = baseName
        var suffix = 2
        while (map.getLayerByName(candidate) != null) {
            candidate = "$baseName $suffix"
            suffix++
        }
        return candidate
    }
}
