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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/** Map-overlay controls for halo/donut masking. */
class DonutMaskingDialog : DialogFragment() {
    private lateinit var minValueLabel: TextView
    private lateinit var maxValueLabel: TextView
    private lateinit var minSlider: SeekBar
    private lateinit var maxSlider: SeekBar

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
                "Halo Masking",
                "Obfuscate point locations by adding a random offset within a safe halo."
            ), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(context).apply {
                isChecked = true
                contentDescription = "Halo masking enabled"
            })
        })

        val privacySlider = SeekBar(context).apply {
            max = 100
            progress = 82
            isEnabled = false
        }
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Privacy Rating  ⓘ"),
            privacySlider,
            MapSafeUi.valueRow(context, "Low Privacy", "High Privacy")
        ))

        minValueLabel = MapSafeUi.text(context, "", 14f, MapSafeUi.TEXT, bold = true)
        maxValueLabel = MapSafeUi.text(context, "", 14f, MapSafeUi.TEXT, bold = true)
        minSlider = SeekBar(context).apply {
            max = MINIMUM_STEPS
            progress = (initialMinimumMetres() / STEP_METRES).coerceIn(0, max)
        }
        maxSlider = SeekBar(context).apply {
            max = MAXIMUM_STEPS
            progress = ((initialMaximumMetres() - MAXIMUM_START_METRES) / STEP_METRES)
                .coerceIn(0, max)
        }
        minSlider.setOnSeekBarChangeListener(labelUpdater(::updateLabels))
        maxSlider.setOnSeekBarChangeListener(labelUpdater(::updateLabels))
        updateLabels()
        content.addView(MapSafeUi.card(
            context,
            minValueLabel,
            minSlider,
            rangeLabels("0 m", "1,000 m"),
            MapSafeUi.divider(context),
            maxValueLabel,
            maxSlider,
            rangeLabels("1,000 m", "5,000 m")
        ))
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.text(context, "●  Original location", 13f, 0xffd7191c.toInt()),
            MapSafeUi.text(context, "●  Masked location", 13f, 0xff1687c7.toInt())
        ))
        sourceLayerName()?.let { sourceName ->
            content.addView(MapSafeUi.infoCard(
                context,
                "Remasking from the precise source",
                "$sourceName will be masked again with these settings. The previous random " +
                    "offsets are not used as input."
            ))
        }
        content.addView(MapSafeUi.primaryButton(context, "Apply Halo Masking", ::applyMasking))
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

    private fun applyMasking() {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putDouble(RESULT_MIN_DISTANCE, minimumMetres().toDouble())
                putDouble(RESULT_MAX_DISTANCE, maximumMetres().toDouble())
                putBoolean(RESULT_CONTINUE_TO_ENCRYPT, continueToEncryption())
                sourceLayerName()?.let { putString(RESULT_SOURCE_LAYER_NAME, it) }
            }
        )
        dismiss()
    }

    private fun minimumMetres(): Int = minSlider.progress * STEP_METRES
    private fun maximumMetres(): Int = MAXIMUM_START_METRES + maxSlider.progress * STEP_METRES
    private fun continueToEncryption(): Boolean = arguments?.getBoolean(ARG_COMBINED, false) == true
    private fun sourceLayerName(): String? = arguments?.getString(ARG_SOURCE_LAYER_NAME)
    private fun initialMinimumMetres(): Int =
        arguments?.getDouble(ARG_INITIAL_MINIMUM, DEFAULT_MINIMUM_METRES.toDouble())?.toInt()
            ?: DEFAULT_MINIMUM_METRES
    private fun initialMaximumMetres(): Int =
        arguments?.getDouble(ARG_INITIAL_MAXIMUM, DEFAULT_MAXIMUM_METRES.toDouble())?.toInt()
            ?: DEFAULT_MAXIMUM_METRES

    private fun updateLabels() {
        minValueLabel.text = "Minimum distance: ${formatMetres(minimumMetres())}"
        maxValueLabel.text = "Maximum distance: ${formatMetres(maximumMetres())}"
    }

    private fun rangeLabels(start: String, end: String): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(MapSafeUi.text(requireContext(), start, 12f, MapSafeUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        addView(MapSafeUi.text(requireContext(), end, 12f, MapSafeUi.MUTED))
    }

    private fun labelUpdater(update: () -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun formatMetres(value: Int): String = String.format("%,d m", value)
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "DonutMaskingDialog"
        const val REQUEST_KEY = "mapsafe_donut_masking_request"
        const val RESULT_MIN_DISTANCE = "minimum_distance_metres"
        const val RESULT_MAX_DISTANCE = "maximum_distance_metres"
        const val RESULT_CONTINUE_TO_ENCRYPT = "continue_to_encryption"
        const val RESULT_SOURCE_LAYER_NAME = "source_layer_name"
        private const val ARG_COMBINED = "combined_workflow"
        private const val ARG_SOURCE_LAYER_NAME = "remask_source_layer_name"
        private const val ARG_INITIAL_MINIMUM = "initial_minimum_distance_metres"
        private const val ARG_INITIAL_MAXIMUM = "initial_maximum_distance_metres"

        fun forCombinedWorkflow() = DonutMaskingDialog().apply {
            arguments = Bundle().apply { putBoolean(ARG_COMBINED, true) }
        }

        fun forRemask(
            sourceLayerName: String,
            minDistanceMetres: Double,
            maxDistanceMetres: Double,
            continueToEncryption: Boolean
        ) = DonutMaskingDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_LAYER_NAME, sourceLayerName)
                putDouble(ARG_INITIAL_MINIMUM, minDistanceMetres)
                putDouble(ARG_INITIAL_MAXIMUM, maxDistanceMetres)
                putBoolean(ARG_COMBINED, continueToEncryption)
            }
        }

        private const val STEP_METRES = 10
        private const val MINIMUM_STEPS = 100
        private const val MAXIMUM_START_METRES = 1_000
        private const val MAXIMUM_STEPS = 400
        private const val DEFAULT_MINIMUM_METRES = 100
        private const val DEFAULT_MAXIMUM_METRES = 2_000
    }
}
