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

    private const val ASSET_PATH = "mapsafe/north_whangarei_infected_trees.geojson"
    private const val BASE_LAYER_NAME = "North Whangārei infected trees"

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
            Field(GeoConstants.FTInteger, "tree_id", "Tree ID"),
            Field(GeoConstants.FTString, "site_code", "Site code"),
            Field(GeoConstants.FTString, "observation", "Observation"),
            Field(GeoConstants.FTString, "record_status", "Record status"),
            Field(GeoConstants.FTString, "sensitivity", "Sensitivity"),
            Field(GeoConstants.FTString, "data_guardian", "Data guardian"),
            Field(GeoConstants.FTString, "community", "Community"),
            Field(GeoConstants.FTString, "source_study", "Source study")
        )

        val layer = app.createEmptyVectorLayer(
            layerName,
            null,
            GeoConstants.GTPoint,
            fields
        )
        layer.isVisible = false
        require(layer.geometryType == GeoConstants.GTPoint) {
            "The sample layer was not created as a point-vector layer."
        }

        var addedToMap = false
        try {
            map.addLayer(layer)
            addedToMap = true
            map.save()

            val insertResult = MapSafeLayerWriter.insertFeatures(
                layer,
                features
            )
            require(insertResult.attempted == features.size) {
                "Expected ${features.size} sample points but attempted ${insertResult.attempted}."
            }
            require(insertResult.inserted == features.size && insertResult.failed == 0) {
                "Only ${insertResult.inserted}/${features.size} sample points were inserted."
            }

            layer.rebuildCache(null)
            layer.isVisible = true
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
                        "tree_id" to properties.getInt("tree_id"),
                        "site_code" to properties.getString("site_code"),
                        "observation" to properties.getString("observation"),
                        "record_status" to properties.getString("record_status"),
                        "sensitivity" to properties.getString("sensitivity"),
                        "data_guardian" to properties.getString("data_guardian"),
                        "community" to properties.getString("community"),
                        "source_study" to properties.getString("source_study")
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
