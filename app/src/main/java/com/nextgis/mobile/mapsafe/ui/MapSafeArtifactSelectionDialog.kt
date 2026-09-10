package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nextgis.maplib.api.ILayer
import com.nextgis.maplib.map.LayerGroup
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.mapsafe.MapSafeConstants
import com.nextgis.mobile.mapsafe.community.CommunityArtifactType
import com.nextgis.mobile.mapsafe.community.CommunityPackageAudienceRecord
import com.nextgis.mobile.mapsafe.community.CommunityPackageAudienceRepository
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublisher
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** Selects several MapSafe representations for separate encryption or publication. */
class MapSafeArtifactSelectionDialog : DialogFragment() {
    private enum class Mode { ENCRYPT, COMMUNITY_UPLOAD }
    private enum class Kind(val label: String, val representation: String) {
        PUBLIC_KEY("My public key", "Public encryption key"),
        ORIGINAL("Original", "Original dataset"),
        HALO_MASKED("Halo masked", "Halo-masked dataset"),
        HEXBIN("Hexagonal bin", "Hexagonal-binned dataset"),
        ENCRYPTED("Encrypted package", "Encrypted package")
    }

    private sealed interface Candidate {
        val id: String
        val name: String
        val kind: Kind

        data class MapLayer(
            val layerName: String,
            override val kind: Kind
        ) : Candidate {
            override val id: String = "layer:$layerName"
            override val name: String = layerName
        }

        data class Stored(
            val file: MapSafeSaveFolderRepository.StoredFile,
            override val kind: Kind
        ) : Candidate {
            override val id: String = "file:${file.uri}"
            override val name: String = file.fileName
        }

        data class LocalPublicKey(
            val fingerprint: String,
            override val name: String
        ) : Candidate {
            override val id: String = "public-key:$fingerprint"
            override val kind: Kind = Kind.PUBLIC_KEY
        }
    }

    private data class CommunityUploadPlan(
        val selection: MapSafeSecurityPreferences.Selection,
        val selected: List<Candidate>,
        val packageAudiences: Map<String, CommunityPackageAudienceRecord>
    )

    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var continueButton: Button
    private val candidateChecks = linkedMapOf<Candidate, CheckBox>()

    private val mode: Mode
        get() = if (requireArguments().getString(ARG_MODE) == MODE_UPLOAD) {
            Mode.COMMUNITY_UPLOAD
        } else {
            Mode.ENCRYPT
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val candidates = candidates()
        val title = if (mode == Mode.ENCRYPT) "Choose Datasets to Encrypt" else "Upload to Community"
        val description = if (mode == Mode.ENCRYPT) {
            "Select any combination. MapSafe creates a separate .pgp package for each dataset and asks you to review recipients for every package."
        } else {
            "Select your public key, anonymised datasets, encrypted packages, or any combination. An unencrypted original is never offered."
        }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        candidates.forEach { candidate ->
            val check = CheckBox(context).apply {
                text = "${candidate.kind.label}  ·  ${candidate.name}"
                isChecked = mode == Mode.ENCRYPT && candidate.kind == Kind.ORIGINAL
                setPadding(0, MapSafeUi.dp(context, 5), 0, MapSafeUi.dp(context, 5))
                setOnCheckedChangeListener { _, _ -> updateContinueState() }
            }
            candidateChecks[candidate] = check
            rows.addView(check)
        }
        progress = ProgressBar(context).apply {
            visibility = View.GONE
            contentDescription = if (mode == Mode.ENCRYPT) "Preparing selected datasets" else "Uploading selected outputs"
        }
        status = MapSafeUi.text(
            context,
            if (candidates.isEmpty()) {
                if (mode == Mode.ENCRYPT) {
                    "No active MapSafe dataset was found."
                } else {
                    "Create an encryption identity, anonymise a dataset, or encrypt a file first."
                }
            } else {
                "${candidates.size} available item${if (candidates.size == 1) "" else "s"}."
            },
            13f,
            MapSafeUi.MUTED
        )
        continueButton = MapSafeUi.primaryButton(
            context,
            if (mode == Mode.ENCRYPT) "Continue to Encryption" else "Upload Selected",
            ::continueSelection
        )
        val selectionActions = MapSafeUi.pairedOutlineActions(
            context,
            "Select all",
            { candidateChecks.values.forEach { it.isChecked = true } },
            "Clear",
            { candidateChecks.values.forEach { it.isChecked = false } }
        ).apply {
            visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
        }

        val page = MapSafeUi.page(context).apply {
            setPadding(MapSafeUi.dp(context, 8), MapSafeUi.dp(context, 8), MapSafeUi.dp(context, 8), 0)
            addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.ENCRYPT))
            addView(MapSafeUi.screenHeading(context, title, description))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(
                    context,
                    if (mode == Mode.ENCRYPT) "Available MapSafe outputs" else "Available community items"
                ),
                status,
                rows,
                selectionActions
            ))
            if (mode == Mode.ENCRYPT) {
                addView(MapSafeUi.outlineButton(context, "Choose Another File") {
                    dismiss()
                    startActivity(MapSafeOpenPgpActivity.intent(context))
                })
            }
            addView(progress)
            addView(continueButton)
        }
        updateContinueState()
        return AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(page) })
            .setNegativeButton("Back") { _, _ -> showParent() }
            .create()
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    private fun candidates(): List<Candidate> {
        val layerCandidates = relatedLayerCandidates()
        if (mode == Mode.ENCRYPT) return layerCandidates

        val publicKey = OpenPgpKeyRepository(requireContext()).localIdentityInfo()?.let { identity ->
            Candidate.LocalPublicKey(
                fingerprint = identity.fingerprint,
                name = "${identity.displayName} · ${identity.fingerprint.takeLast(16)}"
            )
        }

        val stored = MapSafeSaveFolderRepository.listStoredFiles(
            requireContext(),
            setOf("pgp", "gpg", "geojson", "json")
        ).mapNotNull { file ->
            val kind = classifyStored(file.fileName) ?: return@mapNotNull null
            Candidate.Stored(file, kind)
        }
        return (listOfNotNull(publicKey) + layerCandidates.filter { it.kind != Kind.ORIGINAL } + stored)
            .distinctBy(Candidate::id)
    }

    private fun relatedLayerCandidates(): List<Candidate.MapLayer> {
        val selected = (activity as? MainActivity)?.mapFragment?.selectedLayer ?: return emptyList()
        val rootName = rootLayerName(selected.name)
        val layers = mutableListOf<ILayer>()
        collectLayers((requireContext().applicationContext as MainApplication).map, layers)
        return layers.asSequence()
            .filterIsInstance<VectorLayer>()
            .filter { rootLayerName(it.name).equals(rootName, ignoreCase = true) }
            .map { layer -> Candidate.MapLayer(layer.name, classifyLayer(layer.name)) }
            .sortedWith(compareBy<Candidate.MapLayer>({ it.kind.ordinal }, { it.name.lowercase(Locale.ROOT) }))
            .toList()
    }

    private fun collectLayers(group: LayerGroup, destination: MutableList<ILayer>) {
        group.layers.forEach { layer ->
            destination += layer
            if (layer is LayerGroup) collectLayers(layer, destination)
        }
    }

    private fun classifyLayer(name: String): Kind = when {
        HEXBIN_SUFFIX.containsMatchIn(name) -> Kind.HEXBIN
        MASKED_SUFFIX.containsMatchIn(name) -> Kind.HALO_MASKED
        else -> Kind.ORIGINAL
    }

    private fun classifyStored(name: String): Kind? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".pgp") || lower.endsWith(".gpg") -> Kind.ENCRYPTED
            HEXBIN_SUFFIX.containsMatchIn(lower) && (lower.endsWith(".geojson") || lower.endsWith(".json")) -> Kind.HEXBIN
            MASKED_SUFFIX.containsMatchIn(lower) && (lower.endsWith(".geojson") || lower.endsWith(".json")) -> Kind.HALO_MASKED
            else -> null
        }
    }

    private fun rootLayerName(value: String): String {
        var current = value
        while (true) {
            val parent = current.replace(DERIVED_SUFFIX, "")
            if (parent == current) return current
            current = parent
        }
    }

    private fun continueSelection() {
        val selected = candidateChecks.filterValues(CheckBox::isChecked).keys.toList()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), "Select at least one item.", Toast.LENGTH_LONG).show()
            return
        }
        if (mode == Mode.ENCRYPT) prepareEncryption(selected.filterIsInstance<Candidate.MapLayer>())
        else uploadToCommunity(selected)
    }

    private fun prepareEncryption(selected: List<Candidate.MapLayer>) {
        if (selected.isEmpty()) return
        setBusy(true, "Preparing ${selected.size} dataset${if (selected.size == 1) "" else "s"}…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val app = requireContext().applicationContext as MainApplication
                    val root = File(requireContext().cacheDir, "mapsafe/exports/batch-${UUID.randomUUID()}")
                    selected.mapIndexed { index, candidate ->
                        val layer = app.map.getLayerByName(candidate.layerName) as? VectorLayer
                            ?: error("${candidate.name} is no longer available on the map.")
                        val exported = MapSafeGeoJsonWorkflow.exportLayer(layer, File(root, index.toString()))
                        MapSafeOpenPgpActivity.EncryptionSource(
                            file = exported.file,
                            displayName = exported.fileName,
                            pointCount = exported.featureCount,
                            representation = candidate.kind.representation
                        )
                    }
                }
            }
            if (!isAdded) return@launch
            setBusy(false, null)
            result.onSuccess { sources ->
                dismiss()
                startActivity(MapSafeOpenPgpActivity.intent(requireContext(), sources = sources))
            }.onFailure(::showFailure)
        }
    }

    private fun uploadToCommunity(selected: List<Candidate>) {
        val selection = MapSafeSecurityPreferences.read(requireContext())
        if (!selection.hasGroup) {
            AlertDialog.Builder(requireContext())
                .setTitle("Choose a NextGIS community")
                .setMessage("Choose the preconfigured community in Security & Sharing before uploading.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open Security & Sharing") { _, _ ->
                    startActivity(Intent(requireContext(), MapSafeSecurityActivity::class.java))
                }
                .show()
            return
        }
        setBusy(true, "Checking the selected community audiences…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val context = requireContext().applicationContext
                    val audienceRepository = CommunityPackageAudienceRepository(context)
                    val audiences = selected
                        .filterIsInstance<Candidate.Stored>()
                        .filter { it.kind == Kind.ENCRYPTED }
                        .associate { candidate ->
                            val digest = context.contentResolver.openInputStream(candidate.file.uri)?.use(HashUtils::sha256)
                                ?: error("${candidate.name} could not be opened.")
                            val audience = audienceRepository.find(digest, selection)
                                ?: error(
                                    "MapSafe cannot determine the NextGIS recipients for ${candidate.name}. " +
                                        "Encrypt it again after selecting this community and its accepted public keys."
                                )
                            check(audience.publisherUserId == selection.currentUserId) {
                                "${candidate.name} was not created by the currently selected community publisher."
                            }
                            check(audience.audience.isReadyForRestrictedUpload) {
                                "One or more recipients of ${candidate.name} are not accepted members of this community. " +
                                    "Refresh the community keys and encrypt it again."
                            }
                            candidate.id to audience
                        }
                    CommunityUploadPlan(selection, selected, audiences)
                }
            }
            if (!isAdded) return@launch
            setBusy(false, null)
            result.onSuccess(::confirmCommunityUpload).onFailure(::showFailure)
        }
    }

    private fun confirmCommunityUpload(plan: CommunityUploadPlan) {
        val packageSummary = plan.selected
            .filterIsInstance<Candidate.Stored>()
            .filter { it.kind == Kind.ENCRYPTED }
            .joinToString("\n\n") { candidate ->
                val audience = requireNotNull(plan.packageAudiences[candidate.id]).audience
                "${candidate.name}\n" + audience.members.joinToString("\n") { "  • ${it.displayName}" }
            }
        val communityItemCount = plan.selected.size - plan.packageAudiences.size
        val message = buildString {
            append("Community: ").append(plan.selection.groupName ?: "Selected community")
            if (communityItemCount > 0) {
                append("\n\n")
                append(communityItemCount).append(" public key or anonymised item")
                if (communityItemCount != 1) append("s")
                append(" will be available to the community.")
            }
            if (packageSummary.isNotBlank()) {
                append("\n\nEncrypted packages will be restricted to the publisher and the listed recipients:\n\n")
                append(packageSummary)
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Confirm community access")
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Upload") { _, _ -> performCommunityUpload(plan) }
            .show()
    }

    private fun performCommunityUpload(plan: CommunityUploadPlan) {
        val selection = plan.selection
        val selected = plan.selected
        setBusy(true, "Uploading 0 of ${selected.size}…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val context = requireContext().applicationContext
                    val app = context as MainApplication
                    val publisher = NextGisCommunityPublisher(context)
                    val directoryClient = NextGisPublicKeyDirectoryClient(
                        context,
                        OpenPgpKeyRepository(context),
                        PublicKeyExchangeRepository(context)
                    )
                    selected.mapIndexed { index, candidate ->
                        withContext(Dispatchers.Main) {
                            if (isAdded) status.text = "Uploading ${index + 1} of ${selected.size}: ${candidate.name}"
                        }
                        val temporaryRoot = File(context.cacheDir, "mapsafe-community-upload/${UUID.randomUUID()}")
                        try {
                            when (candidate) {
                                is Candidate.LocalPublicKey -> {
                                    directoryClient.publish(
                                        requireNotNull(selection.accountName),
                                        requireNotNull(selection.groupId)
                                    )
                                }
                                is Candidate.MapLayer -> {
                                    val layer = app.map.getLayerByName(candidate.layerName) as? VectorLayer
                                        ?: error("${candidate.name} is no longer available on the map.")
                                    val exported = MapSafeGeoJsonWorkflow.exportLayer(layer, temporaryRoot)
                                    publisher.publishGeoJson(
                                        selection,
                                        exported.file,
                                        exported.fileName,
                                        candidate.kind.communityType()
                                    )
                                }
                                is Candidate.Stored -> {
                                    val suffix = "." + candidate.name.substringAfterLast('.', "dat")
                                    val temporary = File.createTempFile("mapsafe-upload-", suffix, temporaryRoot.apply { mkdirs() })
                                    context.contentResolver.openInputStream(candidate.file.uri)?.use { input ->
                                        temporary.outputStream().use(input::copyTo)
                                    } ?: error("${candidate.name} could not be opened.")
                                    if (candidate.kind == Kind.ENCRYPTED) {
                                        publisher.publishAttachedFile(
                                            selection = selection,
                                            source = temporary,
                                            fileName = candidate.name,
                                            mimeType = "application/pgp-encrypted",
                                            artifactType = CommunityArtifactType.ENCRYPTED_PACKAGE,
                                            audience = requireNotNull(plan.packageAudiences[candidate.id]).audience
                                        )
                                    } else {
                                        publisher.publishGeoJson(
                                            selection,
                                            temporary,
                                            candidate.name,
                                            candidate.kind.communityType()
                                        )
                                    }
                                }
                            }
                        } finally {
                            temporaryRoot.deleteRecursively()
                        }
                    }
                }
            }
            if (!isAdded) return@launch
            setBusy(false, null)
            result.onSuccess { published ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Upload complete")
                    .setMessage(
                        "Uploaded ${published.size} item${if (published.size == 1) "" else "s"} to " +
                            "${selection.groupName ?: "the selected community"}."
                    )
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        dismiss()
                        showParent()
                    }
                    .show()
            }.onFailure(::showFailure)
        }
    }

    private fun Kind.communityType(): CommunityArtifactType = when (this) {
        Kind.PUBLIC_KEY -> CommunityArtifactType.PUBLIC_KEY
        Kind.HALO_MASKED -> CommunityArtifactType.HALO_MASKED
        Kind.HEXBIN -> CommunityArtifactType.HEXBIN
        Kind.ENCRYPTED -> CommunityArtifactType.ENCRYPTED_PACKAGE
        Kind.ORIGINAL -> error("The original dataset cannot be uploaded without encryption.")
    }

    private fun setBusy(busy: Boolean, message: String?) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        continueButton.isEnabled = !busy && candidateChecks.values.any(CheckBox::isChecked)
        candidateChecks.values.forEach { it.isEnabled = !busy }
        message?.let { status.text = it }
    }

    private fun updateContinueState() {
        if (::continueButton.isInitialized) {
            val hasSelection = candidateChecks.values.any(CheckBox::isChecked)
            continueButton.isEnabled = hasSelection
            continueButton.visibility = if (
                mode == Mode.COMMUNITY_UPLOAD && candidateChecks.isEmpty()
            ) View.GONE else View.VISIBLE
        }
    }

    private fun showFailure(error: Throwable) {
        status.text = "The operation did not complete."
        AlertDialog.Builder(requireContext())
            .setTitle("Operation failed")
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showParent() {
        MapSafeMainDialog.forTab(MapSafeMainDialog.DESTINATION_SAFEGUARD)
            .show(parentFragmentManager, MapSafeMainDialog.TAG)
    }

    companion object {
        const val TAG = "MapSafeArtifactSelectionDialog"
        private const val ARG_MODE = "mapsafe_artifact_selection_mode"
        private const val MODE_ENCRYPT = "encrypt"
        private const val MODE_UPLOAD = "upload"
        private val DERIVED_SUFFIX = Regex("_(?:masked|hexbin)(?:_\\d+)?$")
        private val MASKED_SUFFIX = Regex("_masked(?:_\\d+)?(?:\\.|$)", RegexOption.IGNORE_CASE)
        private val HEXBIN_SUFFIX = Regex("_hexbin(?:_\\d+)?(?:\\.|$)", RegexOption.IGNORE_CASE)

        fun forEncryption() = MapSafeArtifactSelectionDialog().apply {
            arguments = Bundle().apply { putString(ARG_MODE, MODE_ENCRYPT) }
        }

        fun forCommunityUpload() = MapSafeArtifactSelectionDialog().apply {
            arguments = Bundle().apply { putString(ARG_MODE, MODE_UPLOAD) }
        }
    }
}
