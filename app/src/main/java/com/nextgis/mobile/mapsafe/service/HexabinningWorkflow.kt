package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.Field
import com.nextgis.maplib.datasource.GeoLinearRing
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.datasource.GeoPolygon
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.Geo
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.MapSafeConstants
import com.nextgis.mobile.mapsafe.safeguard.anonymise.Hexabinning

/**
 * H3 hexabinning workflow for NextGIS Mobile.
 *
 * Reads point features, groups them into H3 cells, creates polygon features
 * for each cell, and inserts them into a new polygon vector layer.
 */
object HexabinningWorkflow {

    data class WorkflowResult(
        val outputLayerName: String,
        val sourcePoints: Int,
        val hexagons: Int,
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

        val groupedCells = groupSourcePoints(sourceLayer, resolution)
        val features = groupedCells.map { (cellId, count) ->
            MapSafeLayerWriter.FeatureToInsert(
                geometry = createPolygonFromCell(cellId),
                attributes = mapOf(
                    "h3_cell" to cellId,
                    "point_count" to count,
                    "resolution" to resolution
                )
            )
        }

        val layerUri = MapSafeLayerWriter.buildLayerUri(app, outputLayer)
        val insertResult = MapSafeLayerWriter.insertFeatures(
            context.contentResolver,
            layerUri,
            features
        )

        outputLayer.notifyLayerChanged()
        map.save()

        return WorkflowResult(
            outputLayerName = outputLayerName,
            sourcePoints = groupedCells.values.sum(),
            hexagons = groupedCells.size,
            inserted = insertResult.inserted,
            failed = insertResult.failed,
            resolution = resolution
        )
    }

    private fun groupSourcePoints(
        sourceLayer: VectorLayer,
        resolution: Int
    ): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        val ids = sourceLayer.query(null)

        for (id in ids) {
            val geometry = sourceLayer.getGeometryForId(id)
            if (geometry is GeoPoint) {
                val lon = Geo.mercatorToWgs84Lon(geometry.x)
                val lat = Geo.mercatorToWgs84Lat(geometry.y)
                val cellId = Hexabinning.pointToCell(lat, lon, resolution)
                counts[cellId] = (counts[cellId] ?: 0) + 1
            }
        }

        return counts
    }

    private fun createPolygonFromCell(cellId: String): GeoPolygon {
        val boundary = Hexabinning.cellBoundaryToLatLon(cellId)
        val ring = GeoLinearRing()
        ring.crs = GeoConstants.CRS_WEB_MERCATOR

        for ((lat, lon) in boundary) {
            val point = GeoPoint(
                Geo.wgs84ToMercatorX(lon),
                Geo.wgs84ToMercatorY(lat)
            )
            point.crs = GeoConstants.CRS_WEB_MERCATOR
            ring.add(point)
        }

        if (boundary.isNotEmpty()) {
            val first = boundary.first()
            val closingPoint = GeoPoint(
                Geo.wgs84ToMercatorX(first.second),
                Geo.wgs84ToMercatorY(first.first)
            )
            closingPoint.crs = GeoConstants.CRS_WEB_MERCATOR
            ring.add(closingPoint)
        }

        val polygon = GeoPolygon()
        polygon.crs = GeoConstants.CRS_WEB_MERCATOR
        polygon.add(ring)
        return polygon
    }
}
