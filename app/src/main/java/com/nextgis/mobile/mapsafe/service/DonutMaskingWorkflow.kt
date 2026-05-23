package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.Field
import com.nextgis.maplib.datasource.Feature
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.MapSafeConstants

/**
 * High-level donut masking workflow for NextGIS Mobile.
 *
 * This class creates a new output point layer and populates it using the
 * older maplib VectorLayer + ContentResolver insertion pattern.
 */
object DonutMaskingWorkflow {

    data class WorkflowResult(
        val outputLayerName: String,
        val totalPoints: Int,
        val maskedPoints: Int,
        val averageDistanceMetres: Double,
        val inserted: Int,
        val failed: Int
    )

    fun createMaskedLayer(
        context: Context,
        app: MainApplication,
        sourceLayer: VectorLayer,
        minDistanceMetres: Double,
        maxDistanceMetres: Double
    ): WorkflowResult {
        val outputLayerName = sourceLayer.name + MapSafeConstants.MASKED_LAYER_SUFFIX
        val outputFields = createOutputFields()

        val outputLayer = app.createEmptyVectorLayer(
            outputLayerName,
            null,
            GeoConstants.GTPoint,
            outputFields
        )

        val map: MapBase = app.map
        map.addLayer(outputLayer)
        map.save()

        val sourcePoints = readPointFeatures(sourceLayer)
        val processed = DonutMaskingProcessor.processPoints(
            sourcePoints,
            minDistanceMetres,
            maxDistanceMetres
        )

        val layerUri = MapSafeLayerWriter.buildLayerUri(app, outputLayer)
        val insertResult = MapSafeLayerWriter.insertFeatures(
            context.contentResolver,
            layerUri,
            processed.features
        )

        outputLayer.notifyLayerChanged()
        map.save()

        return WorkflowResult(
            outputLayerName = outputLayerName,
            totalPoints = processed.statistics.totalPoints,
            maskedPoints = processed.statistics.maskedPoints,
            averageDistanceMetres = processed.statistics.averageDistanceMetres,
            inserted = insertResult.inserted,
            failed = insertResult.failed
        )
    }

    private fun createOutputFields(): List<Field> {
        val fields = ArrayList<Field>()
        fields.add(Field(GeoConstants.FTString, "source", "Source"))
        fields.add(Field(GeoConstants.FTReal, "mask_min_m", "Mask Min M"))
        fields.add(Field(GeoConstants.FTReal, "mask_max_m", "Mask Max M"))
        return fields
    }

    private fun readPointFeatures(sourceLayer: VectorLayer): List<Pair<GeoPoint, Map<String, Any?>>> {
        val result = mutableListOf<Pair<GeoPoint, Map<String, Any?>>>()
        val ids = sourceLayer.query(null)

        for (id in ids) {
            val geometry = sourceLayer.getGeometryForId(id)
            if (geometry is GeoPoint) {
                val attributes = mutableMapOf<String, Any?>()
                attributes["source"] = sourceLayer.name
                attributes["mask_min_m"] = null
                attributes["mask_max_m"] = null
                result.add(Pair(geometry, attributes))
            }
        }

        return result
    }
}
