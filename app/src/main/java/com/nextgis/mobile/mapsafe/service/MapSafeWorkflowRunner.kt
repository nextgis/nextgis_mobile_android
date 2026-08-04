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

    sealed class WorkflowMessage {
        data class Success(
            val message: String,
            val selectedLayer: VectorLayer? = null,
            val zoomExtent: GeoEnvelope? = null
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
            return WorkflowMessage.Failure("No vector layer selected.")
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
                "Created ${result.outputLayerName}. " +
                    "Masked ${result.maskedPoints}/${result.totalPoints} points. " +
                    "Inserted ${result.inserted}, failed ${result.failed}. " +
                    "Average distance: ${String.format("%.2f", result.averageDistanceMetres)} m"
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
            return WorkflowMessage.Failure("No vector layer selected.")
        }

        return try {
            val result = HexabinningWorkflow.createHexbinLayer(
                context = context,
                app = app,
                sourceLayer = selectedLayer,
                resolution = resolution
            )

            WorkflowMessage.Success(
                "Created ${result.outputLayerName} using ${result.engine.displayName} " +
                    "at resolution ${result.resolution}. " +
                    "Grouped ${result.sourcePoints} points into ${result.hexagons} blue hexagons."
            )
        } catch (e: Throwable) {
            WorkflowMessage.Failure("Hexabinning failed: ${e.message}", e)
        }
    }
}
