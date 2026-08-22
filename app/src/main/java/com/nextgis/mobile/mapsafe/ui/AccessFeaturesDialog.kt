package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe access features entry dialog.
 *
 * This groups functions related to opening and checking protected data.
 */
class AccessFeaturesDialog : DialogFragment() {

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), 0)
            addView(MapSafeUi.screenHeading(
                context,
                "Access Features",
                "Verify and securely access protected spatial datasets."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "1  Verify Record", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(
                    context,
                    "Compare an encrypted dataset with its blockchain integrity record before decryption.",
                    14f
                ),
                MapSafeUi.outlineButton(context, "Verify", ::openVerification)
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "2  Decrypt", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(
                    context,
                    "Use your OpenPGP private key and passphrase to decrypt an authorised package.",
                    14f
                ),
                MapSafeUi.outlineButton(context, "Decrypt", ::openDecryption)
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "3  Access Dataset", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(
                    context,
                    "Open original, masked, or hexbin datasets from the app-managed catalogue.",
                    14f
                ),
                MapSafeUi.text(
                    context,
                    "Only GeoJSON layers imported after successful OpenPGP integrity checking are listed.",
                    13f,
                    MapSafeUi.MUTED
                ),
                MapSafeUi.outlineButton(context, "Access datasets", ::openDatasets)
            ))
        }
        return AlertDialog.Builder(requireContext())
            .setView(content)
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    private fun openVerification() {
        dismiss()
        IntegrityRecordDialog.forAccessFeatures()
            .show(parentFragmentManager, "IntegrityRecordDialog")
    }

    private fun openDecryption() {
        dismiss()
        DecryptDialog().show(parentFragmentManager, "DecryptDialog")
    }

    private fun openDatasets() {
        dismiss()
        AccessDatasetsDialog().show(parentFragmentManager, "AccessDatasetsDialog")
    }

    private fun showParent() {
        MapSafeMainDialog().show(parentFragmentManager, MapSafeMainDialog.TAG)
    }
}
