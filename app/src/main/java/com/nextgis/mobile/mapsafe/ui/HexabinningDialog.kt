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
import android.widget.ScrollView
import android.widget.Spinner
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
        content.addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.ANONYMISE))
        content.addView(MapSafeUi.screenHeading(
            context,
            "Hexagonal Binning",
            "Choose the hexagon size for the aggregated output."
        ))

        resolution = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                RESOLUTION_LABELS
            )
            setSelection(initialResolution() - MIN_RESOLUTION)
        }
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Hexagon Size"),
            resolution,
            MapSafeUi.divider(context),
            MapSafeUi.valueRow(context, "Aggregation", "Count", strongValue = true)
        ))
        content.addView(MapSafeUi.primaryButton(context, "Apply Hexagonal Binning", ::applyHexbin))
        panel.addView(
            ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        root.addView(
            panel,
            LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels / 2)
        )
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
        AnonymiseDialog().show(parentFragmentManager, "AnonymiseDialog")
    }

    private fun applyHexbin() {
        val selectedResolution = MIN_RESOLUTION + resolution.selectedItemPosition
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putInt(RESULT_RESOLUTION, selectedResolution)
                sourceLayerName()?.let { putString(RESULT_SOURCE_LAYER_NAME, it) }
            }
        )
        dismiss()
    }

    private fun sourceLayerName(): String? = arguments?.getString(ARG_SOURCE_LAYER_NAME)
    private fun initialResolution(): Int = arguments
        ?.getInt(ARG_INITIAL_RESOLUTION, DEFAULT_RESOLUTION)
        ?.coerceIn(MIN_RESOLUTION, MAX_RESOLUTION)
        ?: DEFAULT_RESOLUTION
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "HexabinningDialog"
        const val REQUEST_KEY = "mapsafe_hexabinning_request"
        const val RESULT_RESOLUTION = "h3_resolution"
        const val RESULT_SOURCE_LAYER_NAME = "source_layer_name"
        private const val ARG_SOURCE_LAYER_NAME = "rebin_source_layer_name"
        private const val ARG_INITIAL_RESOLUTION = "initial_h3_resolution"
        private const val MIN_RESOLUTION = 6
        private const val MAX_RESOLUTION = 12
        private const val DEFAULT_RESOLUTION = 8

        fun forRebin(sourceLayerName: String, resolution: Int) = HexabinningDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_LAYER_NAME, sourceLayerName)
                putInt(ARG_INITIAL_RESOLUTION, resolution)
            }
        }

        private val RESOLUTION_LABELS = arrayOf(
            "Resolution 6  ·  ~3.2 km",
            "Resolution 7  ·  ~1.2 km",
            "Resolution 8  ·  ~460 m",
            "Resolution 9  ·  ~174 m",
            "Resolution 10 ·  ~66 m",
            "Resolution 11 ·  ~25 m",
            "Resolution 12 ·  ~9 m"
        )
    }
}
