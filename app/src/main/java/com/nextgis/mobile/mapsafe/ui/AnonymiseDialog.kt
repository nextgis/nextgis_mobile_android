package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe anonymisation dialog.
 *
 * Input dialogs publish Fragment results. MainActivity forwards those results
 * to the currently selected layer in MapFragment.
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
            ) { _, which ->
                when (which) {
                    0 -> DonutMaskingDialog()
                        .show(parentFragmentManager, DonutMaskingDialog.TAG)
                    1 -> HexabinningDialog()
                        .show(parentFragmentManager, HexabinningDialog.TAG)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                SafeguardFeaturesDialog()
                    .show(parentFragmentManager, "SafeguardFeaturesDialog")
            }
            .create()
    }
}
