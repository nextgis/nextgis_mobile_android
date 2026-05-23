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
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
