package com.nextgis.mobile.mapsafe.service

import android.content.Context
import com.nextgis.maplib.datasource.GeoEnvelope
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.MainApplication

/**
 * Single entry point for running MapSafe workflows from UI/controller code.
 *
 * This keeps MainActivity/MapFragment small: they only need to pass the
 * selected layer and user parameters into this runner.
 */
object MapSafeWorkflowRunner {

    data class DonutMaskingDetails(
        val sourceLayerName: String,
        val outputLayerName: String,
        val minDistanceMetres: Double,
        val maxDistanceMetres: Double,
        val totalPoints: Int,
        val maskedPoints: Int,
        val averageDistanceMetres: Double,
        val disclosureRiskPercent: Double,
        val privacyRatingPercent: Double,
        val parentNearestCount: Int,
        val evaluatedPoints: Int
    )

    sealed class WorkflowMessage {
        data class Success(
            val message: String,
            val selectedLayer: VectorLayer? = null,
            val zoomExtent: GeoEnvelope? = null,
            val donutMaskingDetails: DonutMaskingDetails? = null
        ) : WorkflowMessage()
        data class Failure(val message: String, val error: Throwable? = null) : WorkflowMessage()
    }

    fun loadSamplePoints(
        context: Context,
        app: MainApplication
    ): WorkflowMessage {
        return try {
            val result = MapSafeSampleDataWorkflow.createSampleLayer(context, app)
            WorkflowMessage.Success(
                message = "Loaded ${result.inserted}/${result.attempted} synthetic sample points " +
                    "into ${result.layerName}. The layer is selected and ready for MapSafe testing.",
                selectedLayer = result.layer,
                zoomExtent = result.extent
            )
        } catch (e: Throwable) {
            WorkflowMessage.Failure("Loading sample points failed: ${e.message}", e)
        }
    }

    fun runDonutMasking(
        context: Context,
        app: MainApplication,
        selectedLayer: VectorLayer?,
        minDistanceMetres: Double,
        maxDistanceMetres: Double
    ): WorkflowMessage {
        if (selectedLayer == null) {
            return WorkflowMessage.Failure(
                "No compatible point-vector layer is selected. Choose a point layer or use the bundled MapSafe sample dataset."
            )
        }

        return try {
            val result = DonutMaskingWorkflow.createMaskedLayer(
                context = context,
                app = app,
                sourceLayer = selectedLayer,
                minDistanceMetres = minDistanceMetres,
                maxDistanceMetres = maxDistanceMetres
            )

            WorkflowMessage.Success(
                message = "Created ${result.outputLayerName}. " +
                    "Masked ${result.maskedPoints}/${result.totalPoints} points. " +
                    "Inserted ${result.inserted}, failed ${result.failed}. " +
                    "Average distance: ${String.format("%.2f", result.averageDistanceMetres)} m. " +
                    "Spruill privacy rating: " +
                    "${String.format("%.2f", result.spruillMeasure.privacyRatingPercent)}/100 " +
                    "(higher is better). Disclosure risk: " +
                    "${String.format("%.2f", result.spruillMeasure.disclosureRiskPercent)}% " +
                    "(${result.spruillMeasure.parentNearestCount}/" +
                    "${result.spruillMeasure.evaluatedPoints} parent-nearest).",
                selectedLayer = result.outputLayer,
                zoomExtent = GeoEnvelope(result.outputLayer.extents),
                donutMaskingDetails = DonutMaskingDetails(
                    sourceLayerName = selectedLayer.name,
                    outputLayerName = result.outputLayerName,
                    minDistanceMetres = minDistanceMetres,
                    maxDistanceMetres = maxDistanceMetres,
                    totalPoints = result.totalPoints,
                    maskedPoints = result.maskedPoints,
                    averageDistanceMetres = result.averageDistanceMetres,
                    disclosureRiskPercent = result.spruillMeasure.disclosureRiskPercent,
                    privacyRatingPercent = result.spruillMeasure.privacyRatingPercent,
                    parentNearestCount = result.spruillMeasure.parentNearestCount,
                    evaluatedPoints = result.spruillMeasure.evaluatedPoints
                )
            )
        } catch (e: Throwable) {
            WorkflowMessage.Failure("Donut masking failed: ${e.message}", e)
        }
    }

    fun runHexabinning(
        context: Context,
        app: MainApplication,
        selectedLayer: VectorLayer?,
        resolution: Int
    ): WorkflowMessage {
        if (selectedLayer == null) {
            return WorkflowMessage.Failure(
                "No compatible point-vector layer is selected. Choose a point layer or use the bundled MapSafe sample dataset."
            )
        }

        return try {
            val result = HexabinningWorkflow.createHexbinLayer(
                context = context,
                app = app,
                sourceLayer = selectedLayer,
                resolution = resolution
            )

            WorkflowMessage.Success(
                message = "Created ${result.outputLayerName} using ${result.engine.displayName} " +
                    "at resolution ${result.resolution}. " +
                    "Grouped ${result.sourcePoints} points into ${result.hexagons} blue hexagons.",
                selectedLayer = result.outputLayer,
                zoomExtent = GeoEnvelope(result.outputLayer.extents)
            )
        } catch (e: Throwable) {
            WorkflowMessage.Failure("Hexabinning failed: ${e.message}", e)
        }
    }
}
