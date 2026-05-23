package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe safeguard features entry dialog.
 *
 * This groups functions that protect sensitive data before sharing.
 */
class SafeguardFeaturesDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Safeguard Features")
            .setItems(
                arrayOf(
                    "Anonymise",
                    "Encrypt",
                    "Blockchain Notarisation"
                )
            ) { _, _ ->
                // Future navigation target.
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
