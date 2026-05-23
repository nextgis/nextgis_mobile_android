package com.nextgis.mobile.dialog

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Simple MapSafe donut masking dialog.
 *
 * It collects minimum and maximum masking distances in metres and passes them
 * back to MainActivity / MapFragment.
 */
class MaskDialog(
    private val onApply: (Double, Double) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val minInput = EditText(requireContext()).apply {
            hint = "Minimum masking distance (m)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val maxInput = EditText(requireContext()).apply {
            hint = "Maximum masking distance (m)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
            addView(minInput)
            addView(maxInput)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("MapSafe Donut Masking")
            .setView(layout)
            .setPositiveButton("Apply Mask") { _, _ ->
                val minDist = minInput.text.toString().toDoubleOrNull()
                val maxDist = maxInput.text.toString().toDoubleOrNull()

                if (minDist == null || maxDist == null || minDist < 0 || maxDist < 0) {
                    Toast.makeText(requireContext(), "Please enter valid masking distances", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                onApply(minDist, maxDist)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
