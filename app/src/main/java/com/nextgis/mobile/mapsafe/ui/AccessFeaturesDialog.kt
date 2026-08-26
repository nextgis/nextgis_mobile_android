package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nextgis.mobile.mapsafe.community.CommunityPackageDownload
import com.nextgis.mobile.mapsafe.community.CommunityPackageRecord
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPackageClient
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Entry screen for community packages and encrypted files already on the device. */
class AccessFeaturesDialog : DialogFragment() {
    private lateinit var communityClient: NextGisCommunityPackageClient
    private lateinit var communityStatus: TextView
    private lateinit var communityPackages: LinearLayout
    private lateinit var communityProgress: ProgressBar
    private lateinit var refreshButton: Button
    private var refreshStarted = false

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        communityClient = NextGisCommunityPackageClient(context)
        val selection = MapSafeSecurityPreferences.read(context)
        communityStatus = MapSafeUi.text(
            context,
            if (selection.hasGroup) {
                "Loading protected datasets from ${selection.groupName ?: "the selected community"}…"
            } else {
                "No NextGIS community selected. Choose one in Security & Sharing."
            },
            14f,
            MapSafeUi.MUTED
        )
        communityPackages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        communityProgress = ProgressBar(context).apply {
            visibility = View.GONE
            contentDescription = "Loading community datasets"
        }
        refreshButton = MapSafeUi.outlineButton(context, "Refresh", ::refreshCommunityPackages)

        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), 0)
            addView(
                MapSafeUi.screenHeading(
                    context,
                    "Access Features",
                    "Choose a protected dataset from your community or from this device."
                )
            )
            addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(
                        context,
                        "Community datasets",
                        17f,
                        MapSafeUi.GREEN_TEXT,
                        bold = true
                    ),
                    communityStatus,
                    communityProgress,
                    refreshButton,
                    communityPackages,
                    MapSafeUi.compactOutlineButton(context, "Community settings") {
                        startActivity(Intent(context, MapSafeSecurityActivity::class.java))
                    }
                )
            )
            addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(
                        context,
                        "Encrypted file on this device",
                        17f,
                        MapSafeUi.GREEN_TEXT,
                        bold = true
                    ),
                    MapSafeUi.text(
                        context,
                        "Choose a .pgp package from local storage, calculate its SHA-256, and continue to decryption.",
                        14f
                    ),
                    MapSafeUi.outlineButton(context, "Choose encrypted file", ::openVerification)
                )
            )
        }
        return AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    override fun onStart() {
        super.onStart()
        if (!refreshStarted) {
            refreshStarted = true
            refreshCommunityPackages()
        }
    }

    private fun refreshCommunityPackages() {
        if (!::communityClient.isInitialized) return
        val selection = MapSafeSecurityPreferences.read(requireContext())
        if (!selection.hasGroup) {
            communityProgress.visibility = View.GONE
            refreshButton.isEnabled = true
            communityStatus.text = "No NextGIS community selected. Choose one in Security & Sharing."
            communityPackages.removeAllViews()
            return
        }

        setCommunityBusy(true)
        communityStatus.text =
            "Loading protected datasets from ${selection.groupName ?: "the selected community"}…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { communityClient.listPackages(selection) }
            }
            setCommunityBusy(false)
            result.onSuccess(::showCommunityPackages).onFailure { error ->
                communityStatus.text = "Community datasets could not be loaded."
                AlertDialog.Builder(requireContext())
                    .setTitle("Community refresh failed")
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showCommunityPackages(records: List<CommunityPackageRecord>) {
        val context = requireContext()
        communityPackages.removeAllViews()
        communityStatus.text = if (records.isEmpty()) {
            "No encrypted datasets have been published to this community yet."
        } else {
            "${records.size} protected dataset${if (records.size == 1) "" else "s"} available."
        }
        records.forEachIndexed { index, record ->
            val title = "Protected dataset ${index + 1}"
            val state = if (record.isNotarised) "Notarised" else "SHA-256 available"
            communityPackages.addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(context, title, 15f, MapSafeUi.TEXT, bold = true),
                    MapSafeUi.valueRow(context, "Shared by", record.publisherName),
                    MapSafeUi.valueRow(context, "Added", formatTimestamp(record.createdAt)),
                    MapSafeUi.valueRow(context, "Status", state, strongValue = true),
                    MapSafeUi.outlineButton(context, "Download & verify") {
                        downloadAndVerify(record)
                    }
                )
            )
        }
    }

    private fun downloadAndVerify(record: CommunityPackageRecord) {
        val selection = MapSafeSecurityPreferences.read(requireContext())
        setCommunityBusy(true)
        communityStatus.text = "Downloading the selected protected dataset…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    communityClient.downloadPackage(selection, record)
                }
            }
            setCommunityBusy(false)
            result.onSuccess(::openDownloadedPackage).onFailure { error ->
                communityStatus.text = "The selected package was not downloaded."
                AlertDialog.Builder(requireContext())
                    .setTitle("Community download failed")
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun openDownloadedPackage(download: CommunityPackageDownload) {
        Toast.makeText(
            requireContext(),
            "Saved: ${download.fileName}",
            Toast.LENGTH_LONG
        ).show()
        val transactionReference = download.record.blockchain.explorerUrl
            ?: download.record.blockchain.transactionHash
        dismiss()
        IntegrityRecordDialog.forCommunityPackage(
            fileUri = download.uri,
            fileName = download.fileName,
            calculatedSha256 = download.calculatedSha256,
            transactionReference = transactionReference
        ).show(parentFragmentManager, "IntegrityRecordDialog")
    }

    private fun setCommunityBusy(busy: Boolean) {
        communityProgress.visibility = if (busy) View.VISIBLE else View.GONE
        refreshButton.isEnabled = !busy
        communityPackages.isEnabled = !busy
        for (index in 0 until communityPackages.childCount) {
            communityPackages.getChildAt(index).isEnabled = !busy
        }
    }

    private fun openVerification() {
        dismiss()
        IntegrityRecordDialog.forAccessFeatures()
            .show(parentFragmentManager, "IntegrityRecordDialog")
    }

    private fun showParent() {
        MapSafeMainDialog().show(parentFragmentManager, MapSafeMainDialog.TAG)
    }

    private fun formatTimestamp(value: String): String = runCatching {
        DISPLAY_TIME_FORMAT.format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault(value)

    companion object {
        private val DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern(
            "d MMM yyyy, HH:mm",
            Locale.getDefault()
        )
    }
}
