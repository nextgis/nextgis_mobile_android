package com.nextgis.mobile.mapsafe.service

import com.nextgis.maplib.datasource.GeoEnvelope
import com.nextgis.maplib.datasource.GeoGeometry
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.maplibui.mapui.VectorLayerUI
import com.nextgis.mobile.MainApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Bridges regular NextGIS vector layers and MapSafe OpenPGP packages through GeoJSON. */
object MapSafeGeoJsonWorkflow {

    data class ExportResult(
        val file: File,
        val fileName: String,
        val featureCount: Int
    )

    data class ImportResult(
        val layer: VectorLayer,
        val layerName: String,
        val featureCount: Int,
        val extent: GeoEnvelope
    )

    fun exportLayer(sourceLayer: VectorLayer, destinationDirectory: File): ExportResult {
        require(sourceLayer.isValid) { "The selected vector layer is not valid." }
        require(destinationDirectory.exists() || destinationDirectory.mkdirs()) {
            "The temporary MapSafe export directory could not be created."
        }

        val fileName = safeBaseName(sourceLayer.name) + ".geojson"
        val destination = File(destinationDirectory, fileName)
        val features = JSONArray()
        val fields = sourceLayer.fields

        for (id in sourceLayer.query(null)) {
            val sourceFeature = sourceLayer.getFeature(id) ?: continue
            val sourceGeometry = sourceLayer.getGeometryForId(id) ?: continue
            val geometry = geometryAsWgs84(sourceGeometry)
            val properties = JSONObject()

            fields.forEachIndexed { index, field ->
                properties.put(field.name, jsonValue(sourceFeature.getFieldValue(index)))
            }

            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("id", id)
                    .put("geometry", geometry.toJSON())
                    .put("properties", properties)
            )
        }

        require(features.length() > 0) { "The selected layer contains no readable features." }

        val collection = JSONObject()
            .put("type", "FeatureCollection")
            .put("name", sourceLayer.name)
            .put("features", features)

        destination.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(collection.toString())
        }

        return ExportResult(destination, fileName, features.length())
    }

    fun importLayer(
        app: MainApplication,
        geoJsonFile: File,
        originalFileName: String
    ): ImportResult {
        require(geoJsonFile.isFile && geoJsonFile.length() > 0L) {
            "The decrypted GeoJSON file is empty or unavailable."
        }
        require(geoJsonFile.length() <= MAX_IMPORT_BYTES) {
            "The decrypted GeoJSON file exceeds the 50 MB import limit."
        }

        val map: MapBase = app.map
        val layerName = uniqueLayerName(map, layerNameFrom(originalFileName))
        val layer = VectorLayerUI(app, map.createLayerStorage()).apply {
            name = layerName
            isVisible = true
            minZoom = GeoConstants.DEFAULT_MIN_ZOOM.toFloat()
            maxZoom = GeoConstants.DEFAULT_MAX_ZOOM.toFloat()
        }

        var addedToMap = false
        try {
            layer.createFromGeoJson(geoJsonFile, null)
            require(layer.isValid) { "The decrypted file is not a supported GeoJSON vector layer." }
            val featureCount = layer.query(null).size
            require(featureCount > 0) { "The decrypted GeoJSON layer contains no readable features." }
            val extent = GeoEnvelope(layer.extents)
            require(extent.isInit) { "The imported layer extent could not be calculated." }

            map.addLayer(layer)
            addedToMap = true
            layer.notifyLayerChanged()
            map.save()

            return ImportResult(layer, layerName, featureCount, extent)
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

    fun isGeoJsonFileName(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".geojson") || lower.endsWith(".json")
    }

    private fun geometryAsWgs84(source: GeoGeometry): GeoGeometry {
        val geometry = source.copy()
        when (geometry.crs) {
            GeoConstants.CRS_WGS84 -> Unit
            GeoConstants.CRS_WEB_MERCATOR -> require(geometry.project(GeoConstants.CRS_WGS84)) {
                "A feature geometry could not be projected to WGS84."
            }
            else -> error(
                "Unsupported layer CRS ${geometry.crs}; MapSafe supports EPSG:3857 and EPSG:4326."
            )
        }
        return geometry
    }

    private fun jsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Double -> if (value.isFinite()) value else JSONObject.NULL
            is Float -> if (value.isFinite()) value else JSONObject.NULL
            is Number, is Boolean, is String -> value
            else -> value.toString()
        }
    }

    private fun layerNameFrom(fileName: String): String {
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
        val lower = leaf.lowercase(Locale.ROOT)
        val withoutExtension = when {
            lower.endsWith(".geojson") -> leaf.dropLast(8)
            lower.endsWith(".json") -> leaf.dropLast(5)
            else -> leaf
        }
        val base = safeBaseName(withoutExtension)
        return (base.ifBlank { "MapSafe decrypted layer" }) + " (decrypted)"
    }

    private fun safeBaseName(name: String): String {
        return name.trim()
            .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim('.', ' ')
            .ifBlank { "mapsafe-layer" }
            .take(80)
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

    private const val MAX_IMPORT_BYTES = 50L * 1024L * 1024L
}
