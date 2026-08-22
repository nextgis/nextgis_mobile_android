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
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compact map-overlay result for a completed hexagonal aggregation. */
class HexabinningResultDialog : DialogFragment() {

    private var expandablePanel: MapSafeExpandableResultPanel? = null
    private lateinit var saveLocationText: TextView
    private lateinit var saveLocationRow: View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val panel = MapSafeExpandableResultPanel(
            context = context,
            title = "Hexagonal Binning Applied",
            onBack = ::rebin,
            expandedHeight = resources.displayMetrics.heightPixels / 2,
            initiallyExpanded = savedInstanceState?.getBoolean(STATE_RESULTS_EXPANDED) ?: true
        ).also { expandablePanel = it }

        val content = MapSafeUi.page(context).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.ANONYMISE))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Aggregated result"),
                MapSafeUi.valueRow(
                    context,
                    "Points grouped",
                    requireArguments().getInt(ARG_SOURCE_POINTS).toString()
                ),
                MapSafeUi.valueRow(
                    context,
                    "Hexagonal cells",
                    requireArguments().getInt(ARG_HEXAGONS).toString(),
                    strongValue = true
                ),
                MapSafeUi.valueRow(context, "Resolution", resolution().toString()),
                pale = true
            ))
        }
        val workflowActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(10))
            addView(MapSafeUi.pairedOutlineActions(
                context,
                leftLabel = "Bin Again",
                onLeft = ::rebin,
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
        showBinningSettings()
    }

    private fun rebin() {
        dismiss()
        showBinningSettings()
    }

    private fun showBinningSettings() {
        HexabinningDialog.forRebin(sourceLayerName(), resolution())
            .show(parentFragmentManager, HexabinningDialog.TAG)
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
            Toast.makeText(context, "The hexagonal layer is no longer available.", Toast.LENGTH_LONG).show()
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
                    "Saving the hexagonal layer failed: ${error.message ?: error.javaClass.simpleName}",
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

    private fun sourceLayerName(): String =
        requireArguments().getString(ARG_SOURCE_LAYER_NAME).orEmpty()
    private fun outputLayerName(): String =
        requireArguments().getString(ARG_OUTPUT_LAYER_NAME).orEmpty()
    private fun encryptionSourceLayerName(): String =
        requireArguments().getString(ARG_ENCRYPTION_SOURCE_LAYER_NAME) ?: sourceLayerName()
    private fun resolution(): Int = requireArguments().getInt(ARG_RESOLUTION)
    private fun safeGeoJsonName(layerName: String): String =
        layerName.replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim('.', ' ')
            .ifBlank { "mapsafe-hexagonal-layer" } + ".geojson"
    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "HexabinningResultDialog"
        private const val ARG_SOURCE_LAYER_NAME = "source_layer_name"
        private const val ARG_OUTPUT_LAYER_NAME = "output_layer_name"
        private const val ARG_ENCRYPTION_SOURCE_LAYER_NAME = "encryption_source_layer_name"
        private const val ARG_SOURCE_POINTS = "source_points"
        private const val ARG_HEXAGONS = "hexagons"
        private const val ARG_RESOLUTION = "resolution"
        private const val STATE_RESULTS_EXPANDED = "results_expanded"

        fun newInstance(
            sourceLayerName: String,
            outputLayerName: String,
            sourcePoints: Int,
            hexagons: Int,
            resolution: Int,
            encryptionSourceLayerName: String = sourceLayerName
        ) = HexabinningResultDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_LAYER_NAME, sourceLayerName)
                putString(ARG_OUTPUT_LAYER_NAME, outputLayerName)
                putString(ARG_ENCRYPTION_SOURCE_LAYER_NAME, encryptionSourceLayerName)
                putInt(ARG_SOURCE_POINTS, sourcePoints)
                putInt(ARG_HEXAGONS, hexagons)
                putInt(ARG_RESOLUTION, resolution)
            }
        }
    }
}
