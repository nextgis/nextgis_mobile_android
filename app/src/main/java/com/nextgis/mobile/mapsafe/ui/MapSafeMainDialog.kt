package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Main MapSafe entry dialog.
 *
 * This mirrors the MapSafe web/QGIS structure by separating:
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
            ) { _, which ->
                when (which) {
                    0 -> SafeguardFeaturesDialog()
                        .show(parentFragmentManager, "SafeguardFeaturesDialog")
                    1 -> AccessFeaturesDialog()
                        .show(parentFragmentManager, "AccessFeaturesDialog")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
