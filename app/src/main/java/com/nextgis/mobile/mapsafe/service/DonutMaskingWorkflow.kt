package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.Field
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.MapSafeConstants
import com.nextgis.mobile.mapsafe.safeguard.anonymise.SpruillMeasure

/**
 * High-level donut masking workflow for NextGIS Mobile.
 *
 * This class creates a new output point layer and populates it using the
 * older maplib VectorLayer + ContentResolver insertion pattern.
 */
object DonutMaskingWorkflow {

    data class WorkflowResult(
        val outputLayer: VectorLayer,
        val outputLayerName: String,
        val totalPoints: Int,
        val maskedPoints: Int,
        val averageDistanceMetres: Double,
        val spruillMeasure: SpruillMeasure.Result,
        val inserted: Int,
        val failed: Int
    )

    private data class OutputSchema(
        val fields: List<Field>,
        val sourceFeatureIdField: String,
        val maskMinField: String,
        val maskMaxField: String,
        val maskDistanceField: String
    )

    fun createMaskedLayer(
        context: Context,
        app: MainApplication,
        sourceLayer: VectorLayer,
        minDistanceMetres: Double,
        maxDistanceMetres: Double
    ): WorkflowResult {
        require(sourceLayer.geometryType == GeoConstants.GTPoint) {
            "Donut masking requires a point layer."
        }
        require(minDistanceMetres.isFinite() && maxDistanceMetres.isFinite()) {
            "Masking distances must be finite numbers."
        }
        require(minDistanceMetres >= 0.0 && maxDistanceMetres >= minDistanceMetres) {
            "Minimum distance must be non-negative and no greater than maximum distance."
        }

        val schema = createOutputSchema(sourceLayer.fields)
        val sourcePoints = readPointFeatures(sourceLayer, schema)
        require(sourcePoints.isNotEmpty()) { "The selected point layer contains no readable points." }

        val processed = DonutMaskingProcessor.processPoints(
            sourcePoints,
            minDistanceMetres,
            maxDistanceMetres,
            schema.maskMinField,
            schema.maskMaxField,
            schema.maskDistanceField
        )

        val map: MapBase = app.map
        val outputLayerName = uniqueLayerName(
            map,
            sourceLayer.name + MapSafeConstants.MASKED_LAYER_SUFFIX
        )

        val outputLayer = app.createEmptyVectorLayer(
            outputLayerName,
            null,
            GeoConstants.GTPoint,
            schema.fields
        )
        MapSafeLayerStyle.applyBluePointStyle(outputLayer)

        map.addLayer(outputLayer)
        map.save()

        val layerUri = MapSafeLayerWriter.buildLayerUri(app, outputLayer)
        val insertResult = MapSafeLayerWriter.insertFeatures(
            context.contentResolver,
            layerUri,
            processed.features
        )

        outputLayer.notifyLayerChanged()
        map.save()

        return WorkflowResult(
            outputLayer = outputLayer,
            outputLayerName = outputLayerName,
            totalPoints = processed.statistics.totalPoints,
            maskedPoints = processed.statistics.maskedPoints,
            averageDistanceMetres = processed.statistics.averageDistanceMetres,
            spruillMeasure = processed.statistics.spruillMeasure,
            inserted = insertResult.inserted,
            failed = insertResult.failed
        )
    }

    private fun createOutputSchema(sourceFields: List<Field>): OutputSchema {
        val fields = sourceFields.map { Field(it.type, it.name, it.alias) }.toMutableList()
        val usedNames = fields.map { it.name.lowercase() }.toMutableSet()

        fun uniqueFieldName(preferred: String): String {
            var candidate = preferred
            var suffix = 2
            while (!usedNames.add(candidate.lowercase())) {
                candidate = "${preferred}_$suffix"
                suffix++
            }
            return candidate
        }

        val sourceIdField = uniqueFieldName(MapSafeConstants.FIELD_SOURCE_FEATURE_ID)
        val maskMinField = uniqueFieldName(MapSafeConstants.FIELD_MASK_MIN_METRES)
        val maskMaxField = uniqueFieldName(MapSafeConstants.FIELD_MASK_MAX_METRES)
        val maskDistanceField = uniqueFieldName(MapSafeConstants.FIELD_MASK_DISTANCE_METRES)

        fields.add(Field(GeoConstants.FTLong, sourceIdField, "MapSafe source feature ID"))
        fields.add(Field(GeoConstants.FTReal, maskMinField, "MapSafe minimum distance (m)"))
        fields.add(Field(GeoConstants.FTReal, maskMaxField, "MapSafe maximum distance (m)"))
        fields.add(Field(GeoConstants.FTReal, maskDistanceField, "MapSafe applied distance (m)"))

        return OutputSchema(
            fields,
            sourceIdField,
            maskMinField,
            maskMaxField,
            maskDistanceField
        )
    }

    private fun readPointFeatures(
        sourceLayer: VectorLayer,
        schema: OutputSchema
    ): List<DonutMaskingProcessor.SourcePoint> {
        val result = mutableListOf<DonutMaskingProcessor.SourcePoint>()
        val sourceFields = sourceLayer.fields
        val ids = sourceLayer.query(null)

        for (id in ids) {
            val geometry = sourceLayer.getGeometryForId(id)
            if (geometry is GeoPoint) {
                val sourceFeature = sourceLayer.getFeature(id)
                val attributes = LinkedHashMap<String, Any?>()
                sourceFields.forEachIndexed { index, field ->
                    attributes[field.name] = sourceFeature?.getFieldValue(index)
                }
                attributes[schema.sourceFeatureIdField] = id
                result.add(
                    DonutMaskingProcessor.SourcePoint(
                        point = GeoPoint(geometry),
                        attributes = attributes
                    )
                )
            }
        }

        return result
    }

    private fun uniqueLayerName(map: MapBase, baseName: String): String {
        var candidate = baseName
        var suffix = 2
        while (map.getLayerByName(candidate) != null) {
            candidate = "${baseName}_$suffix"
            suffix++
        }
        return candidate
    }
}
