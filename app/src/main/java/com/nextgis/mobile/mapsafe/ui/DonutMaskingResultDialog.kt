package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import java.util.Locale

/** Shows one halo-masking privacy result and offers a safe retry from the precise source. */
class DonutMaskingResultDialog : DialogFragment() {

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
        panel.addView(MapSafeUi.appBar(context, ::returnToMaskingSettings))

        val content = MapSafeUi.page(context).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(MapSafeUi.screenHeading(
            context,
            "Halo Masking Applied",
            "Review the privacy result on the masked layer before accepting it or remasking."
        ))

        val privacyRating = requireArguments().getDouble(ARG_PRIVACY_RATING)
        val disclosureRisk = requireArguments().getDouble(ARG_DISCLOSURE_RISK)
        val scoreBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = privacyRating.toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(MapSafeUi.GREEN)
            contentDescription = "Inverted Spruill score ${format(privacyRating)} out of 100"
        }
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Inverted Spruill Privacy Score"),
            MapSafeUi.text(
                context,
                "${format(privacyRating)} / 100",
                30f,
                MapSafeUi.GREEN_TEXT,
                bold = true
            ),
            MapSafeUi.text(context, privacyBand(privacyRating), 14f, MapSafeUi.GREEN_TEXT, bold = true),
            scoreBar,
            MapSafeUi.text(
                context,
                "Higher is better. Privacy score = 100 - Spruill disclosure risk.",
                13f,
                MapSafeUi.MUTED
            ).apply { setPadding(0, dp(6), 0, 0) }
        ).apply {
            background = MapSafeUi.rounded(context, MapSafeUi.GREEN_PALE, MapSafeUi.BORDER, 7)
        })

        val evaluatedPoints = requireArguments().getInt(ARG_EVALUATED_POINTS)
        val parentNearest = requireArguments().getInt(ARG_PARENT_NEAREST)
        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "How this score was calculated"),
            MapSafeUi.valueRow(context, "Spruill disclosure risk", "${format(disclosureRisk)}%"),
            MapSafeUi.valueRow(context, "Parent remains nearest", "$parentNearest / $evaluatedPoints"),
            MapSafeUi.valueRow(
                context,
                "Points masked",
                "${requireArguments().getInt(ARG_MASKED_POINTS)} / ${requireArguments().getInt(ARG_TOTAL_POINTS)}"
            ),
            MapSafeUi.valueRow(
                context,
                "Average displacement",
                "${format(requireArguments().getDouble(ARG_AVERAGE_DISTANCE))} m"
            ),
            MapSafeUi.divider(context),
            MapSafeUi.text(
                context,
                "A parent-nearest point is one whose masked position is still closest to its " +
                    "own original location. Fewer parent-nearest points produce a higher inverted score.",
                13f,
                MapSafeUi.MUTED
            )
        ))

        content.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Masking result"),
            MapSafeUi.valueRow(context, "Precise source", sourceLayerName()),
            MapSafeUi.valueRow(context, "Masked layer", outputLayerName(), strongValue = true),
            MapSafeUi.valueRow(
                context,
                "Halo range",
                "${formatDistance(minDistance())} - ${formatDistance(maxDistance())}"
            )
        ))

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MapSafeUi.outlineButton(context, "Remask", ::remask),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(6), 0)
                })
            addView(MapSafeUi.primaryButton(
                context,
                if (continueToEncryption()) "Use & Continue" else "Use This Result",
                ::useResult
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6), 0, 0, 0)
            })
        })
        content.addView(MapSafeUi.text(
            context,
            "Remask always starts from the precise source, never from an already masked layer.",
            12f,
            MapSafeUi.MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        })

        panel.addView(ScrollView(context).apply { addView(content) })
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
        showMaskingSettings()
    }

    private fun remask() {
        returnToMaskingSettings()
    }

    private fun returnToMaskingSettings() {
        dismiss()
        showMaskingSettings()
    }

    private fun showMaskingSettings() {
        DonutMaskingDialog.forRemask(
            sourceLayerName = sourceLayerName(),
            minDistanceMetres = minDistance(),
            maxDistanceMetres = maxDistance(),
            continueToEncryption = continueToEncryption()
        ).show(parentFragmentManager, DonutMaskingDialog.TAG)
    }

    private fun useResult() {
        parentFragmentManager.setFragmentResult(
            REQUEST_USE_RESULT,
            Bundle().apply {
                putString(RESULT_OUTPUT_LAYER_NAME, outputLayerName())
                putBoolean(RESULT_CONTINUE_TO_ENCRYPT, continueToEncryption())
            }
        )
        dismiss()
    }

    private fun sourceLayerName(): String = requireArguments().getString(ARG_SOURCE_LAYER_NAME).orEmpty()
    private fun outputLayerName(): String = requireArguments().getString(ARG_OUTPUT_LAYER_NAME).orEmpty()
    private fun minDistance(): Double = requireArguments().getDouble(ARG_MIN_DISTANCE)
    private fun maxDistance(): Double = requireArguments().getDouble(ARG_MAX_DISTANCE)
    private fun continueToEncryption(): Boolean =
        requireArguments().getBoolean(ARG_CONTINUE_TO_ENCRYPT, false)

    private fun privacyBand(score: Double): String = when {
        score >= 80.0 -> "High privacy"
        score >= 60.0 -> "Good privacy"
        score >= 40.0 -> "Moderate privacy"
        else -> "Low privacy - consider remasking"
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun formatDistance(value: Double): String = String.format(Locale.US, "%,.0f m", value)
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "DonutMaskingResultDialog"
        const val REQUEST_USE_RESULT = "mapsafe_use_donut_masking_result"
        const val RESULT_OUTPUT_LAYER_NAME = "output_layer_name"
        const val RESULT_CONTINUE_TO_ENCRYPT = "continue_to_encryption"

        private const val ARG_SOURCE_LAYER_NAME = "source_layer_name"
        private const val ARG_OUTPUT_LAYER_NAME = "output_layer_name"
        private const val ARG_MIN_DISTANCE = "min_distance_metres"
        private const val ARG_MAX_DISTANCE = "max_distance_metres"
        private const val ARG_TOTAL_POINTS = "total_points"
        private const val ARG_MASKED_POINTS = "masked_points"
        private const val ARG_AVERAGE_DISTANCE = "average_distance_metres"
        private const val ARG_DISCLOSURE_RISK = "disclosure_risk_percent"
        private const val ARG_PRIVACY_RATING = "privacy_rating_percent"
        private const val ARG_PARENT_NEAREST = "parent_nearest_count"
        private const val ARG_EVALUATED_POINTS = "evaluated_points"
        private const val ARG_CONTINUE_TO_ENCRYPT = "continue_to_encrypt"

        fun newInstance(
            sourceLayerName: String,
            outputLayerName: String,
            minDistanceMetres: Double,
            maxDistanceMetres: Double,
            totalPoints: Int,
            maskedPoints: Int,
            averageDistanceMetres: Double,
            disclosureRiskPercent: Double,
            privacyRatingPercent: Double,
            parentNearestCount: Int,
            evaluatedPoints: Int,
            continueToEncryption: Boolean
        ) = DonutMaskingResultDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_LAYER_NAME, sourceLayerName)
                putString(ARG_OUTPUT_LAYER_NAME, outputLayerName)
                putDouble(ARG_MIN_DISTANCE, minDistanceMetres)
                putDouble(ARG_MAX_DISTANCE, maxDistanceMetres)
                putInt(ARG_TOTAL_POINTS, totalPoints)
                putInt(ARG_MASKED_POINTS, maskedPoints)
                putDouble(ARG_AVERAGE_DISTANCE, averageDistanceMetres)
                putDouble(ARG_DISCLOSURE_RISK, disclosureRiskPercent)
                putDouble(ARG_PRIVACY_RATING, privacyRatingPercent)
                putInt(ARG_PARENT_NEAREST, parentNearestCount)
                putInt(ARG_EVALUATED_POINTS, evaluatedPoints)
                putBoolean(ARG_CONTINUE_TO_ENCRYPT, continueToEncryption)
            }
        }
    }
}
