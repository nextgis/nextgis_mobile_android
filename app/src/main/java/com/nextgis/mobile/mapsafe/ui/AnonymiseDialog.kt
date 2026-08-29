package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * MapSafe anonymisation dialog.
 *
 * Input dialogs publish Fragment results. MainActivity forwards those results
 * to the currently selected layer in MapFragment.
 */
class AnonymiseDialog : DialogFragment() {

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), 0)
            addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.ANONYMISE))
            addView(MapSafeUi.screenHeading(
                context,
                "Anonymise",
                "Choose how the selected map layer should reduce spatial precision."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "Halo Masking", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Move each point by a random distance between the minimum and maximum radius.", 14f),
                MapSafeUi.outlineButton(context, "Configure Halo Masking") {
                    dismiss()
                    DonutMaskingDialog().show(parentFragmentManager, DonutMaskingDialog.TAG)
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.text(context, "Hexagonal Binning", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.text(context, "Replace individual locations with H3 cells and aggregate counts.", 14f),
                MapSafeUi.outlineButton(context, "Configure Hexagonal Binning") {
                    dismiss()
                    HexabinningDialog().show(parentFragmentManager, HexabinningDialog.TAG)
                }
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Need a compatible point-vector layer?"),
                MapSafeUi.text(
                    context,
                    "Load MapSafe's bundled 30-point Suva dataset and select it automatically for masking or binning.",
                    14f
                ),
                MapSafeUi.outlineButton(context, "Use sample dataset") {
                    parentFragmentManager.setFragmentResult(
                        MapSafeMainDialog.REQUEST_LOAD_SAMPLE_POINTS,
                        Bundle().apply {
                            putBoolean(MapSafeMainDialog.RESULT_OPEN_ANONYMISE, true)
                        }
                    )
                    dismiss()
                },
                pale = true
            ))
        }
        return AlertDialog.Builder(requireContext())
            .setView(content)
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    private fun showParent() {
        SafeguardFeaturesDialog()
            .show(parentFragmentManager, "SafeguardFeaturesDialog")
    }
}
