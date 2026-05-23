package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Main MapSafe entry dialog.
 *
 * Future versions will launch:
 * - Safeguard features
 * - Access features
 */
class MapSafeMainDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("MapSafe")
            .setItems(
                arrayOf(
                    "Safeguard Features",
                    "Access Features"
                )
            ) { _, _ ->
                // Placeholder for future navigation.
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
