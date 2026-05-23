package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Collects H3 hexabinning resolution from the user.
 */
class HexabinningDialog(
    private val onApply: (Int) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val resolutionInput = EditText(requireContext()).apply {
            hint = "H3 Resolution (0-15)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("8")
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("H3 Hexabinning")
            .setView(resolutionInput)
            .setPositiveButton("Apply") { _, _ ->
                val resolution = resolutionInput.text.toString().toIntOrNull()

                if (resolution == null || resolution !in 0..15) {
                    Toast.makeText(requireContext(), "Enter a valid H3 resolution between 0 and 15.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                onApply(resolution)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
