package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe safeguard features entry dialog.
 *
 * This groups functions that protect sensitive data before sharing.
 */
class SafeguardFeaturesDialog : DialogFragment() {

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
                "Safeguard Features",
                "Protect sensitive spatial data before it is shared."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "1  Anonymise", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Halo masking or hexagonal aggregation reduces location precision.", 14f),
                MapSafeUi.outlineButton(context, "Anonymise") {
                    dismiss()
                    AnonymiseDialog().show(parentFragmentManager, "AnonymiseDialog")
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "2  Encrypt & Protect", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Encrypt once for verified public-key recipients.", 14f),
                MapSafeUi.outlineButton(context, "Encrypt") {
                    dismiss()
                    EncryptDialog().show(parentFragmentManager, "EncryptDialog")
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "3  Blockchain Notarisation", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Record a hash after the production blockchain connector is configured.", 14f),
                MapSafeUi.outlineButton(context, "Blockchain Notarisation") {
                    dismiss()
                    IntegrityRecordDialog.forSafeguardFeatures()
                        .show(parentFragmentManager, "IntegrityRecordDialog")
                }
            ))
        }
        return AlertDialog.Builder(requireContext())
            .setView(content)
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    private fun showParent() {
        MapSafeMainDialog().show(parentFragmentManager, MapSafeMainDialog.TAG)
    }
}
