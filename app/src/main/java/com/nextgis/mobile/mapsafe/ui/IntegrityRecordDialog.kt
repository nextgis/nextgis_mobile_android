package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * Placeholder dialog for integrity record workflows.
 */
class IntegrityRecordDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Integrity Records")
            .setMessage("Integrity record workflow will be connected here.")
            .setNegativeButton("Back") { _, _ -> showParent() }
            .setPositiveButton("Close", null)
            .create()
    }

    private fun showParent() {
        when (requireArguments().getString(ARG_PARENT)) {
            PARENT_ACCESS -> AccessFeaturesDialog()
                .show(parentFragmentManager, "AccessFeaturesDialog")
            else -> SafeguardFeaturesDialog()
                .show(parentFragmentManager, "SafeguardFeaturesDialog")
        }
    }

    companion object {
        private const val ARG_PARENT = "mapsafe_integrity_parent"
        private const val PARENT_SAFEGUARD = "safeguard"
        private const val PARENT_ACCESS = "access"

        fun forSafeguardFeatures() = IntegrityRecordDialog().apply {
            arguments = Bundle().apply { putString(ARG_PARENT, PARENT_SAFEGUARD) }
        }

        fun forAccessFeatures() = IntegrityRecordDialog().apply {
            arguments = Bundle().apply { putString(ARG_PARENT, PARENT_ACCESS) }
        }
    }
}
