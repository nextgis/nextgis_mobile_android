package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Collects bounded minimum and maximum donut masking distances with sliders. */
class DonutMaskingDialog : DialogFragment() {

    private lateinit var minValueLabel: TextView
    private lateinit var maxValueLabel: TextView
    private lateinit var minSlider: SeekBar
    private lateinit var maxSlider: SeekBar

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        minValueLabel = TextView(requireContext())
        maxValueLabel = TextView(requireContext())

        minSlider = SeekBar(requireContext()).apply {
            max = MINIMUM_STEPS
            progress = DEFAULT_MINIMUM_METRES / STEP_METRES
        }
        maxSlider = SeekBar(requireContext()).apply {
            max = MAXIMUM_STEPS
            progress = (DEFAULT_MAXIMUM_METRES - MAXIMUM_START_METRES) / STEP_METRES
        }

        minSlider.setOnSeekBarChangeListener(labelUpdater { updateLabels() })
        maxSlider.setOnSeekBarChangeListener(labelUpdater { updateLabels() })
        updateLabels()

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))

            addView(minValueLabel)
            addView(minSlider)
            addView(rangeLabels("0 m", "1,000 m"))

            val spacer = TextView(requireContext()).apply { height = dp(16) }
            addView(spacer)

            addView(maxValueLabel)
            addView(maxSlider)
            addView(rangeLabels("1,000 m", "5,000 m"))
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Donut Masking")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply {
                        putDouble(RESULT_MIN_DISTANCE, minimumMetres().toDouble())
                        putDouble(RESULT_MAX_DISTANCE, maximumMetres().toDouble())
                    }
                )
            }
            .setNegativeButton("Back") { _, _ ->
                AnonymiseDialog().show(parentFragmentManager, "AnonymiseDialog")
            }
            .create()
    }

    private fun minimumMetres(): Int = minSlider.progress * STEP_METRES

    private fun maximumMetres(): Int = MAXIMUM_START_METRES + maxSlider.progress * STEP_METRES

    private fun updateLabels() {
        minValueLabel.text = "Minimum distance: ${formatMetres(minimumMetres())}"
        maxValueLabel.text = "Maximum distance: ${formatMetres(maximumMetres())}"
    }

    private fun rangeLabels(start: String, end: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(requireContext()).apply {
                text = start
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(requireContext()).apply { text = end })
        }
    }

    private fun labelUpdater(update: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                update()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun formatMetres(value: Int): String = String.format("%,d m", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "DonutMaskingDialog"
        const val REQUEST_KEY = "mapsafe_donut_masking_request"
        const val RESULT_MIN_DISTANCE = "minimum_distance_metres"
        const val RESULT_MAX_DISTANCE = "maximum_distance_metres"

        private const val STEP_METRES = 10
        private const val MINIMUM_STEPS = 100
        private const val MAXIMUM_START_METRES = 1_000
        private const val MAXIMUM_STEPS = 400
        private const val DEFAULT_MINIMUM_METRES = 100
        private const val DEFAULT_MAXIMUM_METRES = 2_000
    }
}
