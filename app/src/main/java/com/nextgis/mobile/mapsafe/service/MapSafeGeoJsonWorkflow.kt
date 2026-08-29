package com.nextgis.mobile.mapsafe.service

import com.nextgis.maplib.datasource.GeoEnvelope
import com.nextgis.maplib.datasource.GeoGeometry
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.Layer
import com.nextgis.maplib.map.LayerGroup
import com.nextgis.maplib.map.TrackLayer
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.maplibui.mapui.VectorLayerUI
import com.nextgis.mobile.MainApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
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
        val featureCount = destination.outputStream().use { output ->
            exportLayer(sourceLayer, output)
        }

        return ExportResult(destination, fileName, featureCount)
    }

    /** Writes one vector layer as portable WGS84 GeoJSON to a user-selected destination. */
    fun exportLayer(sourceLayer: VectorLayer, output: OutputStream): Int {
        require(sourceLayer.isValid) { "The selected vector layer is not valid." }
        val collection = featureCollection(sourceLayer)
        output.bufferedWriter(Charsets.UTF_8).apply {
            write(collection.toString())
            flush()
        }
        return collection.getJSONArray("features").length()
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
            MapSafeDatasetCatalogue.markVerifiedDecryptionImport(layer)
            showOnlyDecryptedDataLayer(map, layer)

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
        // Some maplib geometry copy implementations do not carry their CRS field.
        // Restore it from the stored source before applying the strict export projection.
        val geometry = source.copy().apply { crs = source.crs }
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

    private fun featureCollection(sourceLayer: VectorLayer): JSONObject {
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
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("name", sourceLayer.name)
            .put("features", features)
    }

    /** Keep map context, but hide every previously loaded vector or track overlay. */
    private fun showOnlyDecryptedDataLayer(map: MapBase, decryptedLayer: VectorLayer) {
        val dataLayers = mutableListOf<Layer>()
        collectDataLayers(map, dataLayers)
        dataLayers.forEach { layer ->
            layer.isVisible = layer === decryptedLayer
        }
        decryptedLayer.isVisible = true
        map.save()
    }

    private fun collectDataLayers(group: LayerGroup, destination: MutableList<Layer>) {
        group.layers.forEach { layer ->
            if (layer is VectorLayer || layer is TrackLayer) destination += layer as Layer
            if (layer is LayerGroup) collectDataLayers(layer, destination)
        }
    }

    private const val MAX_IMPORT_BYTES = 50L * 1024L * 1024L
}
