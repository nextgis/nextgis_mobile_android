package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe anonymisation dialog.
 *
 * Future implementations will connect these actions directly to
 * selected GIS layers.
 */
class AnonymiseDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Anonymise")
            .setItems(
                arrayOf(
                    "Donut Masking",
                    "Hexabinning"
                )
            ) { _, _ ->
                // Future anonymisation actions.
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
