package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Selects file-based or current-layer encryption. */
class EncryptDialog : DialogFragment() {

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), 0)
            addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.ENCRYPT))
            addView(MapSafeUi.screenHeading(
                context,
                "Encrypt & Protect",
                "Choose the protected dataset that will be encrypted for authorised recipients."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "Selected map layer", 16f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Export the active protected representation and carry it into encryption.", 14f),
                MapSafeUi.outlineButton(context, "Encrypt selected map layer") {
                    openEncryption(selectedLayer = true)
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "Another protected file", 16f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Choose an existing file from device storage.", 14f),
                MapSafeUi.outlineButton(context, "Encrypt another file") {
                    openEncryption(selectedLayer = false)
                }
            ))
        }
        return AlertDialog.Builder(requireContext())
            .setView(content)
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    private fun showParent() {
        SafeguardFeaturesDialog()
            .show(parentFragmentManager, "SafeguardFeaturesDialog")
    }

    private fun openEncryption(selectedLayer: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_OPEN_ENCRYPTION,
            Bundle().apply { putBoolean(RESULT_SELECTED_LAYER, selectedLayer) }
        )
        dismiss()
    }

    companion object {
        const val REQUEST_OPEN_ENCRYPTION = "mapsafe_open_encryption_request"
        const val RESULT_SELECTED_LAYER = "encrypt_selected_layer"
        const val RESULT_SOURCE_LAYER_NAME = "encrypt_source_layer_name"
    }
}
