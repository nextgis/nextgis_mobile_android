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
import com.nextgis.mobile.mapsafe.community.CommunityLayerDownload
import com.nextgis.mobile.mapsafe.community.CommunityLayerRecord
import com.nextgis.mobile.mapsafe.community.CommunityPackageDownload
import com.nextgis.mobile.mapsafe.community.CommunityPackageRecord
import com.nextgis.mobile.mapsafe.community.NextGisCommunityLayerClient
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPackageClient
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.keys.CachedPublicKeyRecord
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.keys.PublicKeySyncReport
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Browses every MapSafe resource visible through the selected community ACL. */
class AccessFeaturesDialog : DialogFragment() {
    private lateinit var packageClient: NextGisCommunityPackageClient
    private lateinit var layerClient: NextGisCommunityLayerClient
    private lateinit var directoryClient: NextGisPublicKeyDirectoryClient
    private lateinit var communityStatus: TextView
    private lateinit var publicKeys: LinearLayout
    private lateinit var anonymisedDatasets: LinearLayout
    private lateinit var encryptedPackages: LinearLayout
    private lateinit var communityProgress: ProgressBar
    private lateinit var refreshButton: Button
    private var refreshStarted = false

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val keyRepository = OpenPgpKeyRepository(context)
        val exchangeRepository = PublicKeyExchangeRepository(context)
        packageClient = NextGisCommunityPackageClient(context)
        layerClient = NextGisCommunityLayerClient(context)
        directoryClient = NextGisPublicKeyDirectoryClient(context, keyRepository, exchangeRepository)
        val selection = MapSafeSecurityPreferences.read(context)
        communityStatus = MapSafeUi.text(
            context,
            if (selection.hasGroup) {
                "Loading items from ${selection.groupName ?: "the selected community"}…"
            } else {
                "No NextGIS community selected. Choose one in Security & Sharing."
            },
            14f,
            MapSafeUi.MUTED
        )
        publicKeys = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        anonymisedDatasets = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        encryptedPackages = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        communityProgress = ProgressBar(context).apply {
            visibility = View.GONE
            contentDescription = "Loading community items"
        }
        refreshButton = MapSafeUi.outlineButton(context, "Refresh", ::refreshCommunity)

        val content = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), MapSafeUi.dp(context, 4), 0)
            addView(
                MapSafeUi.screenHeading(
                    context,
                    "Community Packages",
                    "Browse public keys, anonymised datasets, and encrypted packages shared with your selected community."
                )
            )
            addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(context, "Selected community", 17f, MapSafeUi.GREEN_TEXT, bold = true),
                    communityStatus,
                    communityProgress,
                    refreshButton
                )
            )
            addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.sectionTitle(context, "Public Keys"),
                    MapSafeUi.text(
                        context,
                        "Keys are downloaded to MapSafe's protected local cache. Confirm each fingerprint before using it for encryption.",
                        13f,
                        MapSafeUi.MUTED
                    ),
                    publicKeys
                )
            )
            addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.sectionTitle(context, "Anonymised Datasets"),
                    MapSafeUi.text(
                        context,
                        "Halo-masked and hexagonal-binned datasets can be downloaded as GeoJSON.",
                        13f,
                        MapSafeUi.MUTED
                    ),
                    anonymisedDatasets
                )
            )
            addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.sectionTitle(context, "Encrypted Packages"),
                    MapSafeUi.text(
                        context,
                        "A downloaded package is checked against its published SHA-256 before decryption.",
                        13f,
                        MapSafeUi.MUTED
                    ),
                    encryptedPackages
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
            refreshCommunity()
        }
    }

    private fun refreshCommunity() {
        if (!::packageClient.isInitialized) return
        val selection = MapSafeSecurityPreferences.read(requireContext())
        if (!selection.hasGroup) {
            setCommunityBusy(false)
            communityStatus.text = "No NextGIS community selected. Choose one in Security & Sharing."
            clearSections()
            showEmptySections()
            return
        }

        setCommunityBusy(true)
        communityStatus.text = "Loading items from ${selection.groupName ?: "the selected community"}…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    CommunityContents(
                        publicKeys = directoryClient.sync(
                            requireNotNull(selection.accountName),
                            requireNotNull(selection.groupId)
                        ),
                        layers = layerClient.listLayers(selection),
                        packages = packageClient.listPackages(selection)
                    )
                }
            }
            if (!isAdded) return@launch
            setCommunityBusy(false)
            result.onSuccess(::showCommunityContents).onFailure { error ->
                communityStatus.text = "Community items could not be loaded."
                AlertDialog.Builder(requireContext())
                    .setTitle("Community refresh failed")
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showCommunityContents(contents: CommunityContents) {
        clearSections()
        val visibleKeys = contents.publicKeys.records.filter {
            it.trustState != PublicKeyTrustState.MEMBER_REMOVED
        }
        val total = visibleKeys.size + contents.layers.size + contents.packages.size
        communityStatus.text = if (total == 0) {
            "No MapSafe items have been published to this community yet."
        } else {
            "$total community item${if (total == 1) "" else "s"} available."
        }
        showPublicKeys(visibleKeys)
        showLayers(contents.layers)
        showPackages(contents.packages)
    }

    private fun showPublicKeys(records: List<CachedPublicKeyRecord>) {
        val context = requireContext()
        if (records.isEmpty()) {
            publicKeys.addView(emptyText("No public keys published."))
            return
        }
        records.forEach { record ->
            publicKeys.addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(context, record.displayName, 15f, MapSafeUi.TEXT, bold = true),
                    MapSafeUi.valueRow(context, "Fingerprint", formatFingerprint(record.observedFingerprint)),
                    MapSafeUi.valueRow(context, "Trust", trustLabel(record.trustState), strongValue = true),
                    MapSafeUi.compactOutlineButton(context, "Review fingerprint") {
                        dismiss()
                        startActivity(Intent(context, MapSafeSecurityActivity::class.java))
                    }
                ),
                spacedParams()
            )
        }
    }

    private fun showLayers(records: List<CommunityLayerRecord>) {
        val context = requireContext()
        if (records.isEmpty()) {
            anonymisedDatasets.addView(emptyText("No anonymised datasets published."))
            return
        }
        records.forEach { record ->
            anonymisedDatasets.addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(context, record.fileName, 15f, MapSafeUi.TEXT, bold = true),
                    MapSafeUi.valueRow(context, "Type", record.artifactType.displayName),
                    MapSafeUi.valueRow(context, "Shared by", record.publisherName),
                    MapSafeUi.valueRow(context, "Added", formatTimestamp(record.createdAt)),
                    MapSafeUi.compactOutlineButton(context, "Download") { downloadLayer(record) }
                ),
                spacedParams()
            )
        }
    }

    private fun showPackages(records: List<CommunityPackageRecord>) {
        val context = requireContext()
        if (records.isEmpty()) {
            encryptedPackages.addView(emptyText("No encrypted packages published."))
            return
        }
        records.forEach { record ->
            val state = if (record.isNotarised) "Notarised" else "SHA-256 available"
            encryptedPackages.addView(
                MapSafeUi.card(
                    context,
                    MapSafeUi.text(context, record.fileName, 15f, MapSafeUi.TEXT, bold = true),
                    MapSafeUi.valueRow(context, "Shared by", record.publisherName),
                    MapSafeUi.valueRow(context, "Added", formatTimestamp(record.createdAt)),
                    MapSafeUi.valueRow(context, "Status", state, strongValue = true),
                    MapSafeUi.compactOutlineButton(context, "Download & verify") {
                        downloadAndVerify(record)
                    }
                ),
                spacedParams()
            )
        }
    }

    private fun downloadLayer(record: CommunityLayerRecord) {
        val selection = MapSafeSecurityPreferences.read(requireContext())
        setCommunityBusy(true)
        communityStatus.text = "Downloading ${record.fileName}…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { layerClient.downloadLayer(selection, record) }
            }
            if (!isAdded) return@launch
            setCommunityBusy(false)
            result.onSuccess(::showLayerDownloaded).onFailure { error ->
                communityStatus.text = "The selected dataset was not downloaded."
                showDownloadFailure(error)
            }
        }
    }

    private fun showLayerDownloaded(download: CommunityLayerDownload) {
        communityStatus.text = "Saved: ${download.fileName}"
        AlertDialog.Builder(requireContext())
            .setTitle("Dataset downloaded")
            .setMessage("Saved: ${download.fileName}")
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton("Open Folder") { _, _ ->
                MapSafeSaveFolderRepository.openFolder(requireContext())
            }
            .show()
    }

    private fun downloadAndVerify(record: CommunityPackageRecord) {
        val selection = MapSafeSecurityPreferences.read(requireContext())
        setCommunityBusy(true)
        communityStatus.text = "Downloading ${record.fileName}…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { packageClient.downloadPackage(selection, record) }
            }
            if (!isAdded) return@launch
            setCommunityBusy(false)
            result.onSuccess(::openDownloadedPackage).onFailure { error ->
                communityStatus.text = "The selected package was not downloaded."
                showDownloadFailure(error)
            }
        }
    }

    private fun openDownloadedPackage(download: CommunityPackageDownload) {
        Toast.makeText(requireContext(), "Saved: ${download.fileName}", Toast.LENGTH_LONG).show()
        val transactionReference = download.record.blockchain.explorerUrl
            ?: download.record.blockchain.transactionHash
        dismiss()
        IntegrityRecordDialog.forCommunityPackage(
            fileUri = download.uri,
            fileName = download.fileName,
            recordedFileName = download.record.fileName,
            calculatedSha256 = download.calculatedSha256,
            transactionReference = transactionReference
        ).show(parentFragmentManager, "IntegrityRecordDialog")
    }

    private fun showDownloadFailure(error: Throwable) {
        AlertDialog.Builder(requireContext())
            .setTitle("Community download failed")
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setCommunityBusy(busy: Boolean) {
        communityProgress.visibility = if (busy) View.VISIBLE else View.GONE
        refreshButton.isEnabled = !busy
        listOf(publicKeys, anonymisedDatasets, encryptedPackages).forEach { section ->
            section.isEnabled = !busy
            for (index in 0 until section.childCount) section.getChildAt(index).isEnabled = !busy
        }
    }

    private fun clearSections() {
        publicKeys.removeAllViews()
        anonymisedDatasets.removeAllViews()
        encryptedPackages.removeAllViews()
    }

    private fun showEmptySections() {
        publicKeys.addView(emptyText("No public keys loaded."))
        anonymisedDatasets.addView(emptyText("No anonymised datasets loaded."))
        encryptedPackages.addView(emptyText("No encrypted packages loaded."))
    }

    private fun emptyText(message: String): TextView =
        MapSafeUi.text(requireContext(), message, 13f, MapSafeUi.MUTED)

    private fun spacedParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, MapSafeUi.dp(requireContext(), 6), 0, 0) }

    private fun showParent() {
        MapSafeMainDialog.forTab(MapSafeMainDialog.DESTINATION_ACCESS)
            .show(parentFragmentManager, MapSafeMainDialog.TAG)
    }

    private fun formatTimestamp(value: String): String = runCatching {
        DISPLAY_TIME_FORMAT.format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault(value)

    private fun formatFingerprint(value: String): String = value.chunked(4).joinToString(" ")

    private fun trustLabel(state: PublicKeyTrustState): String = when (state) {
        PublicKeyTrustState.DISCOVERED -> "Review required"
        PublicKeyTrustState.ACCEPTED -> "Accepted"
        PublicKeyTrustState.CHANGE_PENDING -> "Changed — review required"
        PublicKeyTrustState.SUPERSEDED -> "Superseded"
        PublicKeyTrustState.REVOKED -> "Revoked"
        PublicKeyTrustState.MEMBER_REMOVED -> "Member removed"
    }

    private data class CommunityContents(
        val publicKeys: PublicKeySyncReport,
        val layers: List<CommunityLayerRecord>,
        val packages: List<CommunityPackageRecord>
    )

    companion object {
        private val DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern(
            "d MMM yyyy, HH:mm",
            Locale.getDefault()
        )
    }
}
