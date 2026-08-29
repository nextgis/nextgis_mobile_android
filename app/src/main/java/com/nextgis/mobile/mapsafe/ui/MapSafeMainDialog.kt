package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.nextgis.mobile.activity.MainActivity

/**
 * Main MapSafe entry dialog.
 *
 * This mirrors the MapSafe web/QGIS structure by separating:
 * - Safeguard features
 * - Access features
 */
class MapSafeMainDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 8), MapSafeUi.dp(context, 8), MapSafeUi.dp(context, 8), 0)
            addView(MapSafeUi.logoWordmark(context))
            addView(MapSafeUi.screenHeading(
                context,
                "Choose a workflow",
                "Protect a map layer before sharing, or securely access a protected dataset."
            ))
            addView(MapSafeUi.outlineButton(context, "Security & Sharing") {
                startActivity(Intent(context, MapSafeSecurityActivity::class.java))
            }.fullWidth())
            addView(MapSafeUi.outlineButton(context, "Safeguard Features") {
                openWorkflow(DESTINATION_SAFEGUARD)
            }.fullWidth())
            addView(MapSafeUi.outlineButton(context, "Access Features") {
                openWorkflow(DESTINATION_ACCESS)
            }.fullWidth())
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "No suitable point layer available?"),
                MapSafeUi.text(
                    context,
                    "Use the bundled 30-point Suva dataset. It is always created as a compatible local vector layer and selected automatically.",
                    14f
                ),
                MapSafeUi.outlineButton(context, "Use sample dataset") {
                    useSampleDataset(openAnonymise = true)
                },
                pale = true
            ))
        }
        return AlertDialog.Builder(context)
            .setTitle("MapSafe")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Back", null)
            .create()
    }

    private fun useSampleDataset(openAnonymise: Boolean) {
        useSampleDataset(openAnonymise, destination = null)
    }

    private fun useSampleDataset(openAnonymise: Boolean, destination: String?) {
        parentFragmentManager.setFragmentResult(
            REQUEST_LOAD_SAMPLE_POINTS,
            Bundle().apply {
                putBoolean(RESULT_OPEN_ANONYMISE, openAnonymise)
                destination?.let { putString(RESULT_OPEN_DESTINATION, it) }
            }
        )
        dismiss()
    }

    private fun openWorkflow(destination: String) {
        if (destination == DESTINATION_ACCESS) {
            dismiss()
            showDestination(destination)
            return
        }
        val selectedLayer = (activity as? MainActivity)?.mapFragment?.selectedLayer
        if (selectedLayer == null) {
            showDatasetRequired(destination)
            return
        }

        dismiss()
        showDestination(destination)
    }

    private fun showDatasetRequired(destination: String) {
        val workflowName = if (destination == DESTINATION_ACCESS) "Access" else "Safeguard"
        AlertDialog.Builder(requireContext())
            .setTitle("Select a dataset first")
            .setMessage(
                "$workflowName Features needs an active map dataset. Return to the map and " +
                    "select the dataset you want to work with, or load the bundled sample dataset."
            )
            .setNegativeButton("Return to map") { _, _ -> dismiss() }
            .setPositiveButton("Load sample dataset") { _, _ ->
                useSampleDataset(openAnonymise = false, destination = destination)
            }
            .show()
    }

    private fun showDestination(destination: String) {
        when (destination) {
            DESTINATION_ACCESS ->
                AccessFeaturesDialog().show(parentFragmentManager, "AccessFeaturesDialog")

            else ->
                SafeguardFeaturesDialog().show(parentFragmentManager, "SafeguardFeaturesDialog")
        }
    }

    private fun <T : android.view.View> T.fullWidth(): T = apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, MapSafeUi.dp(requireContext(), 3), 0, MapSafeUi.dp(requireContext(), 8))
        }
    }

    companion object {
        const val TAG = "MapSafeMainDialog"
        const val REQUEST_LOAD_SAMPLE_POINTS = "mapsafe_load_sample_points_request"
        const val RESULT_OPEN_ANONYMISE = "open_anonymise_after_sample"
        const val RESULT_OPEN_DESTINATION = "open_destination_after_sample"
        const val DESTINATION_SAFEGUARD = "safeguard"
        const val DESTINATION_ACCESS = "access"
    }
}
