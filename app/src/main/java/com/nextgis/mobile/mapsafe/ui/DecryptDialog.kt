package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe decrypt dialog placeholder.
 */
class DecryptDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Decrypt")
            .setMessage("Decryption workflow will be connected here.")
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
