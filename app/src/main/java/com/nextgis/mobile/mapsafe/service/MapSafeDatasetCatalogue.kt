package com.nextgis.mobile.mapsafe.service

import com.nextgis.maplib.api.ILayer
import com.nextgis.maplib.map.LayerGroup
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.mapsafe.MapSafeConstants
import java.io.File
import java.util.Locale

/**
 * Builds the Access Dataset catalogue from verified-decryption imports that are
 * still present in the current NextGIS map.
 */
object MapSafeDatasetCatalogue {

    enum class DetailLevel(
        val displayName: String,
        val description: String
    ) {
        ORIGINAL(
            "Original detail",
            "Individual features and their recovered attributes are available."
        ),
        MASKED(
            "Masked points",
            "Individual points are available at deliberately displaced locations."
        ),
        HEXBINNED(
            "Hexagonal aggregation",
            "Only aggregated cell areas and counts are available."
        )
    }

    data class Entry(
        val layerName: String,
        val displayName: String,
        val detailLevel: DetailLevel,
        val featureCount: Int
    )

    fun list(map: MapBase): List<Entry> {
        val layers = mutableListOf<ILayer>()
        collectLayers(map, layers)
        return layers.asSequence()
            .filterIsInstance<VectorLayer>()
            .filter(::isVerifiedDecryptionImport)
            .map { layer ->
                val fieldNames = layer.fields.map { it.name }
                Entry(
                    layerName = layer.name,
                    displayName = displayName(layer.name),
                    detailLevel = classify(layer.name, fieldNames),
                    featureCount = runCatching { layer.query(null).size }.getOrDefault(0)
                )
            }
            .sortedWith(compareBy<Entry>({ it.detailLevel.ordinal }, { it.displayName.lowercase(Locale.ROOT) }))
            .toList()
    }

    fun isVerifiedDecryptionImport(layer: VectorLayer): Boolean =
        hasVerifiedDecryptionMarker(layer.path)

    fun markVerifiedDecryptionImport(layer: VectorLayer) {
        val marker = File(layer.path, VERIFIED_DECRYPTION_MARKER)
        marker.outputStream().bufferedWriter(Charsets.US_ASCII).use { writer ->
            writer.write(VERIFIED_DECRYPTION_MARKER_CONTENT)
        }
    }

    fun hasVerifiedDecryptionMarker(layerStorage: File): Boolean {
        val marker = File(layerStorage, VERIFIED_DECRYPTION_MARKER)
        if (!marker.isFile || marker.length() > 128L) return false
        return runCatching { marker.readText(Charsets.US_ASCII) }
            .getOrNull() == VERIFIED_DECRYPTION_MARKER_CONTENT
    }

    fun hasDecryptionImportName(layerName: String): Boolean =
        DECRYPTED_LAYER_PATTERN.matches(layerName.trim())

    fun displayName(layerName: String): String = layerName.trim()
        .replace(DECRYPTED_LAYER_SUFFIX_PATTERN, "")
        .ifBlank { "Decrypted dataset" }

    fun classify(layerName: String, fieldNames: Collection<String> = emptyList()): DetailLevel {
        val normalisedName = displayName(layerName).lowercase(Locale.ROOT)
        val normalisedFields = fieldNames.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }

        return when {
            normalisedName.contains(MapSafeConstants.HEXBIN_LAYER_SUFFIX) ||
                HEXBIN_FIELDS.all(normalisedFields::contains) -> DetailLevel.HEXBINNED

            normalisedName.contains(MapSafeConstants.MASKED_LAYER_SUFFIX) ||
                normalisedFields.any { it == MapSafeConstants.FIELD_MASK_DISTANCE_METRES ||
                    it.startsWith("${MapSafeConstants.FIELD_MASK_DISTANCE_METRES}_") } -> DetailLevel.MASKED

            else -> DetailLevel.ORIGINAL
        }
    }

    private fun collectLayers(group: LayerGroup, destination: MutableList<ILayer>) {
        group.layers.forEach { layer ->
            destination += layer
            if (layer is LayerGroup) collectLayers(layer, destination)
        }
    }

    private val DECRYPTED_LAYER_PATTERN = Regex(".+ \\(decrypted\\)(?: \\d+)?$")
    private val DECRYPTED_LAYER_SUFFIX_PATTERN = Regex(" \\(decrypted\\)(?: \\d+)?$")
    private val HEXBIN_FIELDS = setOf("cell_id", "count")
    private const val VERIFIED_DECRYPTION_MARKER = ".mapsafe-verified-decryption-v1"
    private const val VERIFIED_DECRYPTION_MARKER_CONTENT = "MapSafe verified decryption import v1\n"
}
