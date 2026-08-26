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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.community.CommunityArtifactType
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublisher
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** Shows one halo-masking privacy result and offers a safe retry from the precise source. */
class DonutMaskingResultDialog : DialogFragment() {

    private var expandablePanel: MapSafeExpandableResultPanel? = null
    private lateinit var saveLocationText: TextView
    private lateinit var saveLocationRow: View
    private lateinit var communityUploadButton: Button
    private lateinit var communityUploadStatus: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val panel = MapSafeExpandableResultPanel(
            context = context,
            title = "Halo Masking Applied",
            onBack = ::returnToMaskingSettings,
            expandedHeight = resources.displayMetrics.heightPixels / 2,
            initiallyExpanded = savedInstanceState?.getBoolean(STATE_RESULTS_EXPANDED) ?: true
        ).also { expandablePanel = it }

        val content = MapSafeUi.page(context).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.ANONYMISE))

        val privacyRating = requireArguments().getDouble(ARG_PRIVACY_RATING)
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

        val workflowActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(10))
            addView(MapSafeUi.pairedOutlineActions(
                context,
                leftLabel = "Remask",
                onLeft = ::remask,
                rightLabel = "Save Layer",
                onRight = ::saveOutputLayer
            ))
            saveLocationText = MapSafeUi.text(context, "", 13f, MapSafeUi.GREEN_TEXT, bold = true)
            saveLocationRow = MapSafeUi.savedLocationRow(
                context,
                saveLocationText,
                ::openSaveFolder
            ).apply { visibility = View.GONE }
            addView(saveLocationRow)
            communityUploadButton = MapSafeUi.outlineButton(
                context,
                "Upload to Community",
                ::uploadOutputLayer
            )
            addView(communityUploadButton)
            communityUploadStatus = MapSafeUi.text(
                context,
                "",
                13f,
                MapSafeUi.GREEN_TEXT,
                bold = true
            ).apply { visibility = View.GONE }
            addView(communityUploadStatus)
            addView(MapSafeUi.nextStopActions(
                context,
                nextLabel = "Next: Encrypt",
                onNext = ::nextToEncryption,
                onStop = ::stopWorkflow
            ))
        }

        panel.body.addView(
            ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        panel.body.addView(workflowActions)
        root.addView(panel.view)
        root.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RESULTS_EXPANDED, expandablePanel?.isExpanded ?: true)
        super.onSaveInstanceState(outState)
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
            maxDistanceMetres = maxDistance()
        ).show(parentFragmentManager, DonutMaskingDialog.TAG)
    }

    private fun stopWorkflow() {
        dismiss()
    }

    private fun saveOutputLayer() {
        saveLayerToFolder()
    }

    private fun saveLayerToFolder() {
        val context = requireContext()
        val app = context.applicationContext as MainApplication
        val layer = app.map.getLayerByName(outputLayerName()) as? VectorLayer
        if (layer == null) {
            Toast.makeText(context, "The masked layer is no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    MapSafeSaveFolderRepository.save(
                        context,
                        "application/geo+json",
                        safeGeoJsonName(outputLayerName())
                    ) { uri ->
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            MapSafeGeoJsonWorkflow.exportLayer(layer, output)
                        } ?: error("The Save Folder did not open the new file.")
                    }
                }
            }
            result.onSuccess { saved ->
                saveLocationText.text = "Saved: ${saved.fileName}"
                saveLocationRow.visibility = View.VISIBLE
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "Saving the masked layer failed: ${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openSaveFolder() {
        if (!MapSafeSaveFolderRepository.openFolder(requireContext())) {
            Toast.makeText(requireContext(), "Downloads/MapSafe could not be opened.", Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadOutputLayer() {
        val context = requireContext()
        val selection = MapSafeSecurityPreferences.read(context)
        if (!selection.hasGroup) {
            showCommunitySetupRequired()
            return
        }
        val app = context.applicationContext as MainApplication
        val layer = app.map.getLayerByName(outputLayerName()) as? VectorLayer
        if (layer == null) {
            Toast.makeText(context, "The masked layer is no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        communityUploadButton.isEnabled = false
        communityUploadStatus.apply {
            text = "Uploading to ${selection.groupName ?: "the selected community"}…"
            visibility = View.VISIBLE
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val temporary = File(
                        context.cacheDir,
                        "mapsafe-community-upload/${UUID.randomUUID()}"
                    )
                    try {
                        val exported = MapSafeGeoJsonWorkflow.exportLayer(layer, temporary)
                        NextGisCommunityPublisher(context).publishGeoJson(
                            selection = selection,
                            source = exported.file,
                            fileName = safeGeoJsonName(outputLayerName()),
                            artifactType = CommunityArtifactType.HALO_MASKED
                        )
                    } finally {
                        temporary.deleteRecursively()
                    }
                }
            }
            communityUploadButton.isEnabled = true
            result.onSuccess { published ->
                communityUploadStatus.text =
                    "Uploaded: ${published.fileName} to ${published.communityName}"
                Toast.makeText(context, "Masked layer uploaded to ${published.communityName}.", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                communityUploadStatus.visibility = View.GONE
                AlertDialog.Builder(context)
                    .setTitle("Community upload failed")
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showCommunitySetupRequired() {
        AlertDialog.Builder(requireContext())
            .setTitle("Choose a NextGIS community")
            .setMessage(
                "Sign in to NextGIS and choose the preconfigured community in Security & Sharing before uploading."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Open Security & Sharing") { _, _ ->
                startActivity(android.content.Intent(requireContext(), MapSafeSecurityActivity::class.java))
            }
            .show()
    }

    private fun nextToEncryption() {
        parentFragmentManager.setFragmentResult(
            EncryptDialog.REQUEST_OPEN_ENCRYPTION,
            Bundle().apply {
                putBoolean(EncryptDialog.RESULT_SELECTED_LAYER, true)
                putString(EncryptDialog.RESULT_SOURCE_LAYER_NAME, encryptionSourceLayerName())
            }
        )
        dismiss()
    }

    private fun sourceLayerName(): String = requireArguments().getString(ARG_SOURCE_LAYER_NAME).orEmpty()
    private fun outputLayerName(): String = requireArguments().getString(ARG_OUTPUT_LAYER_NAME).orEmpty()
    private fun encryptionSourceLayerName(): String =
        requireArguments().getString(ARG_ENCRYPTION_SOURCE_LAYER_NAME) ?: sourceLayerName()
    private fun minDistance(): Double = requireArguments().getDouble(ARG_MIN_DISTANCE)
    private fun maxDistance(): Double = requireArguments().getDouble(ARG_MAX_DISTANCE)
    private fun privacyBand(score: Double): String = when {
        score >= 80.0 -> "High privacy"
        score >= 60.0 -> "Good privacy"
        score >= 40.0 -> "Moderate privacy"
        else -> "Low privacy - consider remasking"
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun safeGeoJsonName(layerName: String): String =
        layerName.replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim('.', ' ')
            .ifBlank { "mapsafe-masked-layer" } + ".geojson"
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "DonutMaskingResultDialog"
        private const val ARG_SOURCE_LAYER_NAME = "source_layer_name"
        private const val ARG_OUTPUT_LAYER_NAME = "output_layer_name"
        private const val ARG_ENCRYPTION_SOURCE_LAYER_NAME = "encryption_source_layer_name"
        private const val ARG_MIN_DISTANCE = "min_distance_metres"
        private const val ARG_MAX_DISTANCE = "max_distance_metres"
        private const val ARG_TOTAL_POINTS = "total_points"
        private const val ARG_MASKED_POINTS = "masked_points"
        private const val ARG_AVERAGE_DISTANCE = "average_distance_metres"
        private const val ARG_DISCLOSURE_RISK = "disclosure_risk_percent"
        private const val ARG_PRIVACY_RATING = "privacy_rating_percent"
        private const val ARG_PARENT_NEAREST = "parent_nearest_count"
        private const val ARG_EVALUATED_POINTS = "evaluated_points"
        private const val STATE_RESULTS_EXPANDED = "results_expanded"
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
            encryptionSourceLayerName: String = sourceLayerName
        ) = DonutMaskingResultDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_LAYER_NAME, sourceLayerName)
                putString(ARG_OUTPUT_LAYER_NAME, outputLayerName)
                putString(ARG_ENCRYPTION_SOURCE_LAYER_NAME, encryptionSourceLayerName)
                putDouble(ARG_MIN_DISTANCE, minDistanceMetres)
                putDouble(ARG_MAX_DISTANCE, maxDistanceMetres)
                putInt(ARG_TOTAL_POINTS, totalPoints)
                putInt(ARG_MASKED_POINTS, maskedPoints)
                putDouble(ARG_AVERAGE_DISTANCE, averageDistanceMetres)
                putDouble(ARG_DISCLOSURE_RISK, disclosureRiskPercent)
                putDouble(ARG_PRIVACY_RATING, privacyRatingPercent)
                putInt(ARG_PARENT_NEAREST, parentNearestCount)
                putInt(ARG_EVALUATED_POINTS, evaluatedPoints)
            }
        }
    }
}
