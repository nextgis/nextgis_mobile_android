package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Starts the default ordered MapSafe workflow: represent, encrypt, then share/notarise. */
class CombinedWorkflowDialog : DialogFragment() {
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
                "Protect & Share",
                "First choose what spatial representation recipients may see. The protected output will then continue directly into encryption."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Halo / donut masking"),
                MapSafeUi.text(context, "Randomly displace each precise point before encryption.", 14f),
                MapSafeUi.outlineButton(context, "Configure Halo Masking") {
                    dismiss()
                    DonutMaskingDialog.forCombinedWorkflow()
                        .show(parentFragmentManager, DonutMaskingDialog.TAG)
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "H3 hexagonal binning"),
                MapSafeUi.text(context, "Aggregate precise points into counted hexagonal cells before encryption.", 14f),
                MapSafeUi.outlineButton(context, "Configure Hexagonal Binning") {
                    dismiss()
                    HexabinningDialog.forCombinedWorkflow()
                        .show(parentFragmentManager, HexabinningDialog.TAG)
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Need a compatible point-vector layer?"),
                MapSafeUi.text(
                    context,
                    "Use MapSafe's bundled 30-point Suva dataset, select it automatically, then return here.",
                    14f
                ),
                MapSafeUi.outlineButton(context, "Use sample dataset") {
                    parentFragmentManager.setFragmentResult(
                        MapSafeMainDialog.REQUEST_LOAD_SAMPLE_POINTS,
                        Bundle().apply {
                            putBoolean(MapSafeMainDialog.RESULT_OPEN_COMBINED, true)
                        }
                    )
                    dismiss()
                },
                pale = true
            ))
        }
        return AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    private fun showParent() {
        MapSafeMainDialog().show(parentFragmentManager, MapSafeMainDialog.TAG)
    }
}
