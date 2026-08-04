package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Opens MapSafe's self-contained OpenPGP decryption and key-management screen. */
class DecryptDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("OpenPGP Decryption")
            .setMessage(
                "Decrypt an OpenPGP package addressed to your local identity and " +
                    "verify its integrity and signature."
            )
            .setNegativeButton("Back") { _, _ ->
                AccessFeaturesDialog().show(parentFragmentManager, "AccessFeaturesDialog")
            }
            .setPositiveButton("Open") { _, _ ->
                startActivity(MapSafeOpenPgpActivity.intent(requireContext(), decrypt = true))
            }
            .create()
    }
}
