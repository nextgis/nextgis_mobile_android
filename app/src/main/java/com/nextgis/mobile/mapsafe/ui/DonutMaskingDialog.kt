package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Collects minimum and maximum donut masking distances from the user.
 */
class DonutMaskingDialog(
    private val onApply: (Double, Double) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val minInput = EditText(requireContext()).apply {
            hint = "Minimum distance (metres)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val maxInput = EditText(requireContext()).apply {
            hint = "Maximum distance (metres)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
            addView(minInput)
            addView(maxInput)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Donut Masking")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val minDist = minInput.text.toString().toDoubleOrNull()
                val maxDist = maxInput.text.toString().toDoubleOrNull()

                if (minDist == null || maxDist == null || minDist < 0 || maxDist < 0 || minDist > maxDist) {
                    Toast.makeText(requireContext(), "Enter valid distances: min must be <= max.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                onApply(minDist, maxDist)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
