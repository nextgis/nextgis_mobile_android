package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Styled notarisation placeholder; it never presents a simulated transaction as real. */
class IntegrityRecordDialog : DialogFragment() {
    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return if (requireArguments().getString(ARG_PARENT) == PARENT_ACCESS) {
            AlertDialog.Builder(requireContext())
                .setTitle("Verification")
                .setMessage("The MapSafe verification interface will be designed after the safeguard screens.")
                .setNegativeButton("Back") { _, _ -> showParent() }
                .setPositiveButton("Close", null)
                .create()
        } else {
            buildNotarisationScreen()
        }
    }

    override fun onStart() {
        super.onStart()
        if (requireArguments().getString(ARG_PARENT) != PARENT_ACCESS) {
            dialog?.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(MapSafeUi.PAGE))
                setDimAmount(0f)
            }
        }
    }

    private fun buildNotarisationScreen(): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val page = MapSafeUi.page(context)
        page.setPadding(dp(16), dp(10), dp(16), dp(24))
        page.addView(MapSafeUi.stepStrip(context, 3, "Collect", "Represent", "Encrypt", "Notarise", "Share"))
        page.addView(MapSafeUi.screenHeading(
            context,
            "Notarise on Blockchain",
            "Record the SHA-256 hash of an encrypted file without exposing the protected dataset."
        ))
        page.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Encrypted File"),
            MapSafeUi.text(context, "No encrypted package selected", 15f, MapSafeUi.TEXT, bold = true),
            MapSafeUi.text(context, "The encrypted file picker will be connected with the production blockchain client.", 13f, MapSafeUi.MUTED),
            MapSafeUi.divider(context),
            MapSafeUi.valueRow(context, "File Hash (SHA-256)", "Not generated")
        ))
        page.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Network"),
            MapSafeUi.text(context, "Production network not configured", 15f, MapSafeUi.TEXT, bold = true),
            MapSafeUi.text(context, "No blockchain transaction will be submitted by this build.", 13f, MapSafeUi.MUTED)
        ))
        page.addView(MapSafeUi.infoCard(
            context,
            "Privacy boundary",
            "Only a file hash and minimal metadata may be recorded. The encrypted file must remain private and off-chain."
        ))
        val unavailable = MapSafeUi.primaryButton(context, "🛡  Notarisation unavailable") {}.apply {
            isEnabled = false
            alpha = 0.55f
        }
        page.addView(unavailable)
        page.addView(MapSafeUi.outlineButton(context, "Back to Safeguard Features") {
            dismiss()
            showParent()
        })

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapSafeUi.PAGE)
            addView(toolbar())
            addView(ScrollView(context).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.setContentView(root)
        return dialog
    }

    private fun toolbar(): LinearLayout = MapSafeUi.appBar(requireContext()) {
        dismiss()
        showParent()
    }

    private fun showParent() {
        when (requireArguments().getString(ARG_PARENT)) {
            PARENT_ACCESS -> AccessFeaturesDialog().show(parentFragmentManager, "AccessFeaturesDialog")
            else -> SafeguardFeaturesDialog().show(parentFragmentManager, "SafeguardFeaturesDialog")
        }
    }

    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

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
