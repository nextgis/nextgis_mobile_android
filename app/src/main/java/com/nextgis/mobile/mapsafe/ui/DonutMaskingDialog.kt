package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/** Map-overlay controls for halo/donut masking. */
class DonutMaskingDialog : DialogFragment() {
    private lateinit var minValueLabel: TextView
    private lateinit var maxValueLabel: TextView
    private lateinit var rangeSlider: MapSafeRangeSlider

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
            "Halo Masking",
            "Choose the minimum and maximum displacement distance."
        ))

        minValueLabel = MapSafeUi.text(context, "", 14f, MapSafeUi.TEXT, bold = true)
        maxValueLabel = MapSafeUi.text(context, "", 14f, MapSafeUi.TEXT, bold = true)
        rangeSlider = MapSafeRangeSlider(
            context = context,
            valueFrom = RANGE_START_METRES,
            valueTo = RANGE_END_METRES,
            stepSize = STEP_METRES
        ).apply {
            val initialMin = initialMinimumMetres().coerceIn(RANGE_START_METRES, RANGE_END_METRES)
            val initialMax = initialMaximumMetres().coerceIn(initialMin, RANGE_END_METRES)
            setValues(initialMin, initialMax)
            setOnValuesChangedListener { _, _ -> updateLabels() }
        }
        updateLabels()
        content.addView(MapSafeUi.card(
            context,
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(minValueLabel, LinearLayout.LayoutParams(0, -2, 1f))
                addView(maxValueLabel)
            },
            rangeSlider,
            rangeLabels("0 m", "5,000 m")
        ))
        content.addView(MapSafeUi.primaryButton(context, "Apply Halo Masking", ::applyMasking))
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

    private fun applyMasking() {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putDouble(RESULT_MIN_DISTANCE, minimumMetres().toDouble())
                putDouble(RESULT_MAX_DISTANCE, maximumMetres().toDouble())
                sourceLayerName()?.let { putString(RESULT_SOURCE_LAYER_NAME, it) }
            }
        )
        dismiss()
    }

    private fun minimumMetres(): Int = rangeSlider.lowerValue
    private fun maximumMetres(): Int = rangeSlider.upperValue
    private fun sourceLayerName(): String? = arguments?.getString(ARG_SOURCE_LAYER_NAME)
    private fun initialMinimumMetres(): Int =
        arguments?.getDouble(ARG_INITIAL_MINIMUM, DEFAULT_MINIMUM_METRES.toDouble())?.toInt()
            ?: DEFAULT_MINIMUM_METRES
    private fun initialMaximumMetres(): Int =
        arguments?.getDouble(ARG_INITIAL_MAXIMUM, DEFAULT_MAXIMUM_METRES.toDouble())?.toInt()
            ?: DEFAULT_MAXIMUM_METRES

    private fun updateLabels() {
        minValueLabel.text = "Min: ${formatMetres(minimumMetres())}"
        maxValueLabel.text = "Max: ${formatMetres(maximumMetres())}"
    }

    private fun rangeLabels(start: String, end: String): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(MapSafeUi.text(requireContext(), start, 12f, MapSafeUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        addView(MapSafeUi.text(requireContext(), end, 12f, MapSafeUi.MUTED))
    }

    private fun formatMetres(value: Int): String = String.format("%,d m", value)
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "DonutMaskingDialog"
        const val REQUEST_KEY = "mapsafe_donut_masking_request"
        const val RESULT_MIN_DISTANCE = "minimum_distance_metres"
        const val RESULT_MAX_DISTANCE = "maximum_distance_metres"
        const val RESULT_SOURCE_LAYER_NAME = "source_layer_name"
        private const val ARG_SOURCE_LAYER_NAME = "remask_source_layer_name"
        private const val ARG_INITIAL_MINIMUM = "initial_minimum_distance_metres"
        private const val ARG_INITIAL_MAXIMUM = "initial_maximum_distance_metres"

        fun forRemask(
            sourceLayerName: String,
            minDistanceMetres: Double,
            maxDistanceMetres: Double
        ) = DonutMaskingDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_LAYER_NAME, sourceLayerName)
                putDouble(ARG_INITIAL_MINIMUM, minDistanceMetres)
                putDouble(ARG_INITIAL_MAXIMUM, maxDistanceMetres)
            }
        }

        private const val STEP_METRES = 10
        private const val RANGE_START_METRES = 0
        private const val RANGE_END_METRES = 5_000
        private const val DEFAULT_MINIMUM_METRES = 100
        private const val DEFAULT_MAXIMUM_METRES = 2_000
    }
}
