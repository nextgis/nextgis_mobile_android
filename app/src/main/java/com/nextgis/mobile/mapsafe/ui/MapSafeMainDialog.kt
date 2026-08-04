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
                    "Load sample points (Suva)",
                    "Safeguard Features",
                    "Access Features"
                )
            ) { _, which ->
                when (which) {
                    0 -> parentFragmentManager.setFragmentResult(
                        REQUEST_LOAD_SAMPLE_POINTS,
                        Bundle.EMPTY
                    )
                    1 -> SafeguardFeaturesDialog()
                        .show(parentFragmentManager, "SafeguardFeaturesDialog")
                    2 -> AccessFeaturesDialog()
                        .show(parentFragmentManager, "AccessFeaturesDialog")
                }
            }
            .setNegativeButton("Close", null)
            .create()
    }

    companion object {
        const val TAG = "MapSafeMainDialog"
        const val REQUEST_LOAD_SAMPLE_POINTS = "mapsafe_load_sample_points_request"
    }
}
