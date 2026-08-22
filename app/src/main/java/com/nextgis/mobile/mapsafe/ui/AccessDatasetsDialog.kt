package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.service.MapSafeDatasetCatalogue

/** Lists imported datasets that passed MapSafe's OpenPGP integrity gate. */
class AccessDatasetsDialog : DialogFragment() {

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val app = context.applicationContext as MainApplication
        val entries = MapSafeDatasetCatalogue.list(app.map)
        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), 0)
            addView(MapSafeUi.accessStepStrip(context, MapSafeUi.AccessStep.ACCESS))
            addView(MapSafeUi.screenHeading(
                context,
                "Access Datasets",
                "Choose the level of spatial detail that is appropriate for your work."
            ))

            if (entries.isEmpty()) {
                addView(MapSafeUi.infoCard(
                    context,
                    "No accessible datasets yet",
                    "A GeoJSON dataset appears here after authorised decryption, successful " +
                        "integrity checking, and import into the current map."
                ))
                addView(MapSafeUi.primaryButton(context, "Decrypt a dataset") {
                    dismiss()
                    DecryptDialog().show(parentFragmentManager, "DecryptDialog")
                })
            } else {
                entries.forEach { entry ->
                    addView(MapSafeUi.card(
                        context,
                        MapSafeUi.text(context, entry.displayName, 17f, MapSafeUi.GREEN_TEXT, bold = true),
                        MapSafeUi.valueRow(context, "Available detail", entry.detailLevel.displayName, strongValue = true),
                        MapSafeUi.valueRow(
                            context,
                            "Features",
                            if (entry.featureCount > 0) entry.featureCount.toString() else "Unavailable"
                        ),
                        MapSafeUi.text(context, entry.detailLevel.description, 13f, MapSafeUi.MUTED).apply {
                            setPadding(0, MapSafeUi.dp(context, 5), 0, MapSafeUi.dp(context, 8))
                        },
                        MapSafeUi.outlineButton(context, "Open on map") {
                            parentFragmentManager.setFragmentResult(
                                REQUEST_OPEN_DATASET,
                                Bundle().apply { putString(RESULT_LAYER_NAME, entry.layerName) }
                            )
                            dismiss()
                        }
                    ))
                }
            }
        }

        return AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    private fun showParent() {
        AccessFeaturesDialog().show(parentFragmentManager, "AccessFeaturesDialog")
    }

    companion object {
        const val REQUEST_OPEN_DATASET = "mapsafe_open_accessed_dataset_request"
        const val RESULT_LAYER_NAME = "mapsafe_accessed_dataset_layer_name"
    }
}
