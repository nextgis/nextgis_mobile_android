package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe access features entry dialog.
 *
 * This groups functions related to opening and checking protected data.
 */
class AccessFeaturesDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Access Features")
            .setItems(
                arrayOf(
                    "Check Records",
                    "Decrypt",
                    "Open Layer"
                )
            ) { _, _ ->
                // Future navigation target.
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
