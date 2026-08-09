package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import androidx.fragment.app.DialogFragment

/** Map-overlay controls for H3 aggregation. */
class HexabinningDialog : DialogFragment() {
    private lateinit var resolution: Spinner

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapSafeUi.PAGE)
        }
        panel.addView(toolbar())
        val content = MapSafeUi.page(context).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MapSafeUi.screenHeading(
                context,
                "Hexagonal Binning",
                "Aggregate points into hexagonal cells to reduce spatial detail."
            ), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(context).apply {
                isChecked = true
                contentDescription = "Hexagonal binning enabled"
            })
        })

        resolution = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                RESOLUTION_LABELS
            )
            setSelection(DEFAULT_RESOLUTION - MIN_RESOLUTION)
        }
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Hexagon Size"),
            resolution,
            MapSafeUi.divider(context),
            MapSafeUi.valueRow(context, "Aggregation", "Count", strongValue = true)
        ))
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Privacy Rating  ⓘ"),
            SeekBar(context).apply {
                max = 100
                progress = 84
                isEnabled = false
            },
            MapSafeUi.valueRow(context, "Low Privacy", "High Privacy")
        ))
        content.addView(MapSafeUi.infoCard(
            context,
            "Spatial aggregation",
            "Larger hexagons provide stronger privacy. The output contains cell counts, not the original point locations."
        ))
        content.addView(MapSafeUi.primaryButton(context, "Apply Hexagonal Binning", ::applyHexbin))
        panel.addView(content)
        root.addView(panel)
        root.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setGravity(Gravity.TOP)
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    private fun toolbar(): LinearLayout = MapSafeUi.appBar(requireContext()) {
        dismiss()
        showParent()
    }

    private fun showParent() {
        if (continueToEncryption()) {
            CombinedWorkflowDialog().show(parentFragmentManager, "CombinedWorkflowDialog")
        } else {
            AnonymiseDialog().show(parentFragmentManager, "AnonymiseDialog")
        }
    }

    private fun applyHexbin() {
        val selectedResolution = MIN_RESOLUTION + resolution.selectedItemPosition
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putInt(RESULT_RESOLUTION, selectedResolution)
                putBoolean(RESULT_CONTINUE_TO_ENCRYPT, continueToEncryption())
            }
        )
        dismiss()
    }

    private fun continueToEncryption(): Boolean = arguments?.getBoolean(ARG_COMBINED, false) == true
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "HexabinningDialog"
        const val REQUEST_KEY = "mapsafe_hexabinning_request"
        const val RESULT_RESOLUTION = "h3_resolution"
        const val RESULT_CONTINUE_TO_ENCRYPT = "continue_to_encryption"
        private const val ARG_COMBINED = "combined_workflow"
        private const val MIN_RESOLUTION = 6
        private const val DEFAULT_RESOLUTION = 8
        private val RESOLUTION_LABELS = arrayOf(
            "Resolution 6  ·  ~3.2 km",
            "Resolution 7  ·  ~1.2 km",
            "Resolution 8  ·  ~460 m",
            "Resolution 9  ·  ~174 m",
            "Resolution 10 ·  ~66 m",
            "Resolution 11 ·  ~25 m",
            "Resolution 12 ·  ~9 m"
        )

        fun forCombinedWorkflow() = HexabinningDialog().apply {
            arguments = Bundle().apply { putBoolean(ARG_COMBINED, true) }
        }
    }
}
