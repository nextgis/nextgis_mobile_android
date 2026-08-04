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
class HexabinningDialog : DialogFragment() {

    private lateinit var resolutionInput: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        resolutionInput = EditText(requireContext()).apply {
            hint = "H3 Resolution (0-15)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("8")
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("H3 Hexabinning")
            .setView(resolutionInput)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Back") { _, _ ->
                AnonymiseDialog().show(parentFragmentManager, "AnonymiseDialog")
            }
            .create()
    }

    override fun onStart() {
        super.onStart()
        val alertDialog = dialog as? AlertDialog ?: return
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val resolution = resolutionInput.text.toString().toIntOrNull()

            if (resolution == null || resolution !in 0..15) {
                Toast.makeText(
                    requireContext(),
                    "Enter a valid H3 resolution between 0 and 15.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply { putInt(RESULT_RESOLUTION, resolution) }
            )
            dismiss()
        }
    }

    companion object {
        const val TAG = "HexabinningDialog"
        const val REQUEST_KEY = "mapsafe_hexabinning_request"
        const val RESULT_RESOLUTION = "h3_resolution"
    }
}
