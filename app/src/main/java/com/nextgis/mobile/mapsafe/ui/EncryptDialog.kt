package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Selects file-based or current-layer encryption. */
class EncryptDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("OpenPGP Encryption")
            .setItems(
                arrayOf(
                    "Encrypt selected map layer",
                    "Encrypt another file"
                )
            ) { _, which ->
                parentFragmentManager.setFragmentResult(
                    REQUEST_OPEN_ENCRYPTION,
                    Bundle().apply { putBoolean(RESULT_SELECTED_LAYER, which == 0) }
                )
            }
            .setNegativeButton("Back") { _, _ ->
                SafeguardFeaturesDialog()
                    .show(parentFragmentManager, "SafeguardFeaturesDialog")
            }
            .create()
    }

    companion object {
        const val REQUEST_OPEN_ENCRYPTION = "mapsafe_open_encryption_request"
        const val RESULT_SELECTED_LAYER = "encrypt_selected_layer"
    }
}
