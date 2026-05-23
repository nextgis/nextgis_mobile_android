package com.nextgis.mobile.mapsafe.service

import android.content.Context
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
        data class Success(val message: String) : WorkflowMessage()
        data class Failure(val message: String, val error: Throwable? = null) : WorkflowMessage()
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
                    "Average distance: ${String.format(\"%.2f\", result.averageDistanceMetres)} m"
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
                "Created ${result.outputLayerName} for H3 resolution ${result.resolution}."
            )
        } catch (e: Throwable) {
            WorkflowMessage.Failure("Hexabinning failed: ${e.message}", e)
        }
    }
}
