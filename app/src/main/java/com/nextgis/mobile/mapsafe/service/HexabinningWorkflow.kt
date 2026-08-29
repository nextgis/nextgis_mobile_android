package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.Field
import com.nextgis.maplib.datasource.Geo
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.datasource.GeoPolygon
import com.nextgis.maplib.map.MapBase
import com.nextgis.maplib.map.VectorLayer
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
        val outputLayer: VectorLayer,
        val outputLayerName: String,
        val sourcePoints: Int,
        val hexagons: Int,
        val inserted: Int,
        val failed: Int,
        val resolution: Int,
        val engine: Hexabinning.Engine
    )

    fun createHexbinLayer(
        context: Context,
        app: MainApplication,
        sourceLayer: VectorLayer,
        resolution: Int
    ): WorkflowResult {
        require(sourceLayer.geometryType == GeoConstants.GTPoint) {
            "Hexabinning requires a point layer."
        }
        require(resolution in 0..15) { "H3 resolution must be between 0 and 15." }

        val groupedCells = groupSourcePoints(sourceLayer, resolution)
        require(groupedCells.isNotEmpty()) { "The selected point layer contains no readable points." }
        val engine = Hexabinning.engine

        val map: MapBase = app.map
        val outputLayerName = uniqueLayerName(
            map,
            sourceLayer.name + MapSafeConstants.HEXBIN_LAYER_SUFFIX
        )

        val fields = ArrayList<Field>()
        fields.add(Field(GeoConstants.FTString, "cell_id", "Hex Cell"))
        fields.add(Field(GeoConstants.FTString, "engine", "Hexbin Engine"))
        fields.add(Field(GeoConstants.FTInteger, "point_count", "Point Count"))
        fields.add(Field(GeoConstants.FTInteger, "resolution", "Resolution"))

        val outputLayer = app.createEmptyVectorLayer(
            outputLayerName,
            null,
            GeoConstants.GTPolygon,
            fields
        )
        MapSafeLayerStyle.applyBluePolygonStyle(outputLayer)

        map.addLayer(outputLayer)
        map.save()

        val features = groupedCells.map { (cellId, count) ->
            MapSafeLayerWriter.FeatureToInsert(
                geometry = createPolygonFromCell(cellId),
                attributes = mapOf(
                    "cell_id" to cellId,
                    "engine" to engine.fieldValue,
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
            outputLayer = outputLayer,
            outputLayerName = outputLayerName,
            sourcePoints = groupedCells.values.sum(),
            hexagons = groupedCells.size,
            inserted = insertResult.inserted,
            failed = insertResult.failed,
            resolution = resolution,
            engine = engine
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
                val (lon, lat) = when (geometry.crs) {
                    GeoConstants.CRS_WEB_MERCATOR -> Pair(
                        Geo.mercatorToWgs84SphereX(geometry.x),
                        Geo.mercatorToWgs84SphereY(geometry.y)
                    )
                    GeoConstants.CRS_WGS84 -> Pair(geometry.x, geometry.y)
                    else -> throw IllegalArgumentException(
                        "Unsupported point CRS ${geometry.crs}; MapSafe supports EPSG:3857 and EPSG:4326."
                    )
                }
                val cellId = Hexabinning.pointToCell(lat, lon, resolution)
                counts[cellId] = (counts[cellId] ?: 0) + 1
            }
        }

        return counts
    }

    private fun createPolygonFromCell(cellId: String): GeoPolygon {
        val boundary = Hexabinning.cellBoundaryToLatLon(cellId)
        val polygon = GeoPolygon().apply {
            crs = GeoConstants.CRS_WEB_MERCATOR
        }

        for ((lat, lon) in boundary) {
            val point = GeoPoint(
                Geo.wgs84ToMercatorSphereX(lon),
                Geo.wgs84ToMercatorSphereY(lat)
            ).apply {
                crs = GeoConstants.CRS_WEB_MERCATOR
            }
            polygon.add(point)
        }

        if (boundary.isNotEmpty()) {
            val first = boundary.first()
            val closingPoint = GeoPoint(
                Geo.wgs84ToMercatorSphereX(first.second),
                Geo.wgs84ToMercatorSphereY(first.first)
            ).apply {
                crs = GeoConstants.CRS_WEB_MERCATOR
            }
            polygon.add(closingPoint)
        }

        return polygon
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
