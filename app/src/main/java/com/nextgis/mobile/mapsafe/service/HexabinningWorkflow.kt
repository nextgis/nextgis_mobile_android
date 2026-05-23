package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.Field
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.MapSafeConstants

/**
 * Initial H3 hexabinning workflow shell.
 *
 * This workflow currently prepares the output polygon layer and structure.
 * H3 polygon generation will be connected after h3-java integration.
 */
object HexabinningWorkflow {

    data class WorkflowResult(
        val outputLayerName: String,
        val inserted: Int,
        val failed: Int,
        val resolution: Int
    )

    fun createHexbinLayer(
        context: Context,
        app: MainApplication,
        sourceLayer: VectorLayer,
        resolution: Int
    ): WorkflowResult {

        val outputLayerName = sourceLayer.name + MapSafeConstants.HEXBIN_LAYER_SUFFIX

        val fields = ArrayList<Field>()
        fields.add(Field(GeoConstants.FTString, "h3_cell", "H3 Cell"))
        fields.add(Field(GeoConstants.FTInteger, "point_count", "Point Count"))
        fields.add(Field(GeoConstants.FTInteger, "resolution", "Resolution"))

        val outputLayer = app.createEmptyVectorLayer(
            outputLayerName,
            null,
            GeoConstants.GTPolygon,
            fields
        )

        val map: MapBase = app.map
        map.addLayer(outputLayer)
        map.save()

        outputLayer.notifyLayerChanged()
        map.save()

        return WorkflowResult(
            outputLayerName = outputLayerName,
            inserted = 0,
            failed = 0,
            resolution = resolution
        )
    }
}
