package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe encryption dialog placeholder.
 *
 * Future versions will allow users to choose a spatial file/layer export and
 * encrypt it before sharing or uploading.
 */
class EncryptDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Encrypt")
            .setMessage("Encryption workflow will be connected here.")
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
