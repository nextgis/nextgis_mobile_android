package com.nextgis.mobile.mapsafe.ui

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.mapsafe.access.decrypt.DecryptFile
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkPresets
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfileRepository
import com.nextgis.mobile.mapsafe.community.CommunityArtifactType
import com.nextgis.mobile.mapsafe.community.CommunityBlockchainReference
import com.nextgis.mobile.mapsafe.community.CommunityPackageAudienceRecord
import com.nextgis.mobile.mapsafe.community.CommunityPackageAudienceRepository
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublishException
import com.nextgis.mobile.mapsafe.community.NextGisCommunityPublisher
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpDecryptionResult
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEncryptionResult
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafePerformanceLogRepository
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.safeguard.encryption.EncryptFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Self-contained MapSafe OpenPGP identity, recipient, encryption and decryption screen. */
class MapSafeOpenPgpActivity : AppCompatActivity() {
    data class EncryptionSource(
        val file: File,
        val displayName: String,
        val pointCount: Int?,
        val representation: String
    )

    private data class InternalEncryptionSource(
        val file: File,
        val displayName: String,
        val pointCount: Int?,
        val representation: String
    )

    private data class TimedEncryptionResult(
        val result: OpenPgpEncryptionResult,
        val durationNanos: Long,
        val inputBytes: Long?
    )

    private data class TimedDecryptionResult(
        val result: OpenPgpDecryptionResult,
        val durationNanos: Long,
        val inputBytes: Long?
    )

    private data class SavedEncryptionBundle(
        val saved: MapSafeSaveFolderRepository.SavedFile<TimedEncryptionResult>,
        val audience: CommunityPackageAudienceRecord?
    )

    private data class PreparedCommunityPackage(
        val source: File,
        val fileName: String,
        val selection: MapSafeSecurityPreferences.Selection,
        val audienceRecord: CommunityPackageAudienceRecord
    )

    private lateinit var repository: OpenPgpKeyRepository
    private lateinit var exchangeRepository: PublicKeyExchangeRepository
    private lateinit var directoryClient: NextGisPublicKeyDirectoryClient
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: LinearLayout
    private var resultPanel: View? = null
    private var createIdentityButton: Button? = null
    private var includeLocalIdentityCheckBox: CheckBox? = null
    private var encryptActionButton: Button? = null
    private var decryptActionButton: Button? = null
    private var datasetTitleText: TextView? = null
    private var datasetNameText: TextView? = null
    private var reopenRecipientSelectionAfterImport = false
    private var latestEncryptedUri: Uri? = null
    private var latestEncryptedFileName: String? = null

    private var pendingEncryptInput: Uri? = null
    private var pendingInternalEncryptSource: File? = null
    private var pendingInternalEncryptName: String? = null
    private var pendingInternalEncryptPointCount: Int? = null
    private var pendingInternalEncryptRepresentation: String? = null
    private val queuedInternalEncryptSources = ArrayDeque<InternalEncryptionSource>()
    private var encryptionBatchTotal = 1
    private var encryptionBatchIndex = 1
    private var pendingEncryptRecipients: Set<String> = emptySet()
    private var pendingSigningPassphrase: CharArray? = null
    private var pendingDecryptInput: Uri? = null
    private var pendingDecryptPassphrase: CharArray? = null
    private var pendingSecretImport: Uri? = null
    private var verifiedDecryptInput: Uri? = null
    private var verifiedDecryptSha256: String? = null
    private var verifiedDecryptDisplayName: String? = null
    private var verifiedDecryptNetworkName: String? = null
    private var verifiedDecryptTransactionHash: String? = null

    private val importPublicKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            reopenRecipientSelectionAfterImport = false
        } else {
            importPublicKey(uri)
        }
    }
    private val createIdentity = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        refreshStatus()
        if (result.resultCode == RESULT_OK && repository.hasLocalIdentity()) {
            toast("Identity created. Add or review recipient keys, then select recipients and encrypt.")
        }
    }
    private val importSecretKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingSecretImport = uri
            promptForPassphrase("Unlock imported identity") { passphrase ->
                importSecretIdentity(uri, passphrase)
            }
        }
    }
    private val exportPublicKey = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pgp-keys")
    ) { uri ->
        uri?.let { exportKey(it, secret = false) }
    }
    private val exportSecretKey = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pgp-keys")
    ) { uri ->
        uri?.let { exportKey(it, secret = true) }
    }
    private val selectEncryptInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            clearPendingEncryption()
        } else {
            pendingEncryptInput = uri
            pendingInternalEncryptPointCount = null
            encryptToSaveFolder()
        }
    }
    private val selectDecryptInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            clearVerifiedDecryptSource()
            prepareDecryptInput(uri)
        }
    }
    private val replaceEncryptInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::replaceEncryptionInput)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapSafeUi.configureActivity(
            this,
            if (intent.getStringExtra(EXTRA_MODE) == MODE_DECRYPT) "Decrypt & access" else "Encrypt & protect"
        )
        repository = OpenPgpKeyRepository(applicationContext)
        exchangeRepository = PublicKeyExchangeRepository(applicationContext)
        directoryClient = NextGisPublicKeyDirectoryClient(
            applicationContext,
            repository,
            exchangeRepository
        )
        loadInternalEncryptSource()
        loadVerifiedDecryptSource()
        setContentView(MapSafeUi.activityFrame(this, buildContent(), onBack =(::returnToParentMenu)))
        refreshStatus()
    }

    override fun onDestroy() {
        clearPendingEncryption(deleteInternalSource = true)
        clearPendingDecryption()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
    }

    private fun buildContent(): View {
        controls = MapSafeUi.page(this)
        val preferredDecrypt = intent.getStringExtra(EXTRA_MODE) == MODE_DECRYPT
        if (preferredDecrypt) {
            controls.addView(MapSafeUi.accessStepStrip(this, MapSafeUi.AccessStep.DECRYPT))
            controls.addView(MapSafeUi.screenHeading(
                this,
                "Decrypt & Access",
                "Decrypt a protected dataset you are authorised to access."
            ))
            controls.addView(
                if (verifiedDecryptInput != null) {
                    verifiedDecryptSourceCard()
                } else {
                    MapSafeUi.card(
                        this,
                        MapSafeUi.sectionTitle(this, "Select Encrypted File"),
                        MapSafeUi.text(this, "Choose an OpenPGP MapSafe package from this device.", 14f),
                        MapSafeUi.outlineButton(this, "Browse protected files", ::beginDecrypt)
                    )
                }
            )
            statusText = MapSafeUi.text(this, "", 14f)
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Your Private Key"),
                statusText,
                MapSafeUi.outlineButton(this, "Change / manage identity") {
                    startActivity(Intent(this, MapSafeSecurityActivity::class.java))
                }
            ))
            val decryptActionLabel = if (verifiedDecryptInput != null) {
                "Decrypt Verified File"
            } else {
                "Choose Package & Decrypt"
            }
            decryptActionButton = MapSafeUi.primaryButton(this, decryptActionLabel, ::beginDecrypt)
            controls.addView(requireNotNull(decryptActionButton))
        } else {
            controls.addView(MapSafeUi.safeguardStepStrip(this, MapSafeUi.SafeguardStep.ENCRYPT))
            controls.addView(MapSafeUi.screenHeading(
                this,
                "Encrypt & Protect",
                "Choose who can access the dataset, then encrypt it."
            ))
            val representation = pendingInternalEncryptRepresentation
                ?: intent.getStringExtra(EXTRA_SOURCE_REPRESENTATION)
                ?: "User-selected representation"
            val inputName = pendingInternalEncryptName?.let { name ->
                if (encryptionBatchTotal > 1) "$name  ·  $encryptionBatchIndex of $encryptionBatchTotal" else name
            } ?: "Choose a dataset during encryption"
            val datasetSectionTitle = when {
                isAnonymisedDataset(inputName, representation) -> "Anonymised Dataset"
                pendingInternalEncryptSource != null ||
                    representation.contains("original", ignoreCase = true) -> "Original Dataset"
                else -> "Dataset to Encrypt"
            }
            datasetTitleText = MapSafeUi.text(
                this,
                datasetSectionTitle,
                16f,
                MapSafeUi.GREEN_TEXT,
                bold = true
            )
            datasetNameText = MapSafeUi.text(this, inputName, 14f, MapSafeUi.TEXT).apply {
                setPadding(0, dp(5), 0, 0)
            }
            val datasetHeaderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(requireNotNull(datasetTitleText), LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    MapSafeUi.compactOutlineButton(this@MapSafeOpenPgpActivity, "Choose") {
                        chooseDifferentEncryptionInput()
                    }.apply {
                        textSize = 13f
                        minimumHeight = 0
                        minHeight = 0
                        setPadding(dp(10), dp(2), dp(10), dp(2))
                    },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(10), 0, 0, 0) }
                )
            }
            controls.addView(MapSafeUi.card(
                this,
                datasetHeaderRow,
                requireNotNull(datasetNameText)
            ))
            statusText = MapSafeUi.text(this, "", 14f)
            includeLocalIdentityCheckBox = CheckBox(this).apply {
                text = "Include me"
                isChecked = true
                setTextColor(MapSafeUi.TEXT)
                contentDescription = "Include my identity as an encryption recipient"
            }
            val identityRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    requireNotNull(includeLocalIdentityCheckBox),
                    LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, 0, 0) }
                )
            }
            createIdentityButton = MapSafeUi.compactOutlineButton(
                this,
                "Create encryption identity",
                ::confirmCreateIdentity
            )
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Identity"),
                identityRow,
                requireNotNull(createIdentityButton)
            ))
            val selection = MapSafeSecurityPreferences.read(this)
            val groupRecords = if (selection.hasGroup) {
                exchangeRepository.records(requireNotNull(selection.serverUrl), requireNotNull(selection.groupId))
            } else emptyList()
            val accepted = groupRecords.count { it.trustState == PublicKeyTrustState.ACCEPTED }
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Recipients"),
                MapSafeUi.valueRow(this, selection.groupName ?: "Trusted group", "$accepted verified"),
                MapSafeUi.outlineButton(this, "Refresh community keys", ::synchroniseCommunityKeys),
                MapSafeUi.outlineButton(this, "+  Import individual recipient") {
                    importPublicKey.launch(arrayOf("application/pgp-keys", "application/octet-stream", "text/plain"))
                },
                MapSafeUi.outlineButton(this, "View keys & fingerprints", ::showKeys)
            ))
            encryptActionButton = MapSafeUi.primaryButton(
                this,
                "Select Recipients & Encrypt",
                ::beginEncrypt
            )
            controls.addView(requireNotNull(encryptActionButton))
        }
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SECURITY, false)) {
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Key Backup & Recovery"),
                MapSafeUi.outlineButton(this, "Import protected key backup", ::confirmImportSecretIdentity),
                MapSafeUi.outlineButton(this, "Export my public key") { beginExport(secret = false) },
                MapSafeUi.outlineButton(this, "Export protected private-key backup") { beginExport(secret = true) }
            ))
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        controls.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        return ScrollView(this).apply {
            setBackgroundColor(MapSafeUi.PAGE)
            addView(controls)
        }
    }

    private fun refreshStatus(message: String? = null) {
        val identity = runCatching { repository.localIdentityInfo() }.getOrNull()
        createIdentityButton?.visibility = if (identity == null) View.VISIBLE else View.GONE
        includeLocalIdentityCheckBox?.visibility = if (identity == null) View.GONE else View.VISIBLE
        statusText.text = buildString {
            message?.let { append(it).append("\n\n") }
            if (identity == null) {
                append("No encryption identity was found on this device.\n")
                append("Create it once and MapSafe will reuse it for future encryption.")
            } else {
                append(identity.displayName)
            }
        }
    }

    private fun isAnonymisedDataset(fileName: String, representation: String): Boolean {
        val description = "$fileName $representation".lowercase(Locale.ROOT)
        return listOf("_masked", "masked", "halo", "_hexbin", "hexbin", "hexagonal")
            .any(description::contains)
    }

    private fun confirmCreateIdentity() {
        if (repository.hasLocalIdentity()) {
            AlertDialog.Builder(this)
                .setTitle("Replace local identity?")
                .setMessage(
                    "Replacing the identity can make existing packages impossible to decrypt. " +
                        "Export a protected secret-key backup first."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Continue") { _, _ -> showCreateIdentityDialog() }
                .show()
        } else {
            showCreateIdentityDialog()
        }
    }

    private fun showCreateIdentityDialog() {
        createIdentity.launch(Intent(this, MapSafeIdentityActivity::class.java))
    }

    private fun confirmImportSecretIdentity() {
        val launch = {
            importSecretKey.launch(arrayOf("application/pgp-keys", "application/octet-stream", "text/plain"))
        }
        if (repository.hasLocalIdentity()) {
            AlertDialog.Builder(this)
                .setTitle("Replace local identity?")
                .setMessage("Importing a secret key replaces the current identity. Back it up first if needed.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Continue") { _, _ -> launch() }
                .show()
        } else {
            launch()
        }
    }

    private fun importSecretIdentity(uri: Uri, passphrase: CharArray) {
        runBusy(
            message = "Importing protected identity…",
            operation = {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        repository.importLocalIdentity(input, passphrase)
                    } ?: throw OpenPgpException("The selected key file could not be opened.")
                } finally {
                    passphrase.fill('\u0000')
                    pendingSecretImport = null
                }
            },
            onSuccess = { info ->
                refreshStatus("Identity imported: ${info.displayName}")
            }
        )
    }

    private fun importPublicKey(uri: Uri) {
        val reopenRecipients = reopenRecipientSelectionAfterImport
        reopenRecipientSelectionAfterImport = false
        runBusy(
            message = "Importing recipient key…",
            operation = {
                contentResolver.openInputStream(uri)?.use(repository::importPublicKeys)
                    ?: throw OpenPgpException("The selected public-key file could not be opened.")
            },
            onSuccess = { imported ->
                refreshStatus("Imported ${imported.size} public key${if (imported.size == 1) "" else "s"}.")
                if (reopenRecipients) beginEncrypt()
            }
        )
    }

    private fun beginExport(secret: Boolean) {
        val info = repository.localIdentityInfo()
        if (info == null) {
            toast("Create or import a local identity first.")
            return
        }
        val suffix = info.fingerprint.takeLast(16)
        if (secret) {
            AlertDialog.Builder(this)
                .setTitle("Export secret-key backup?")
                .setMessage(
                    "Anyone with this file and its recovery passphrase can decrypt your packages and sign as you. " +
                        "Store it securely."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Export") { _, _ -> exportSecretKey.launch("mapsafe-secret-$suffix.asc") }
                .show()
        } else {
            exportPublicKey.launch("mapsafe-public-$suffix.asc")
        }
    }

    private fun exportKey(uri: Uri, secret: Boolean) {
        runBusy(
            message = if (secret) "Exporting protected backup…" else "Exporting public key…",
            operation = {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    if (secret) repository.exportLocalSecretKey(output) else repository.exportLocalPublicKey(output)
                } ?: throw OpenPgpException("The selected destination could not be opened.")
            },
            onSuccess = {
                refreshStatus(if (secret) "Protected secret-key backup exported." else "Public key exported.")
            }
        )
    }

    private fun chooseDifferentEncryptionInput() {
        replaceEncryptInput.launch(arrayOf("*/*"))
    }

    private fun replaceEncryptionInput(uri: Uri) {
        pendingInternalEncryptSource?.let(::deleteTemporaryEncryptionSource)
        queuedInternalEncryptSources.forEach { deleteTemporaryEncryptionSource(it.file) }
        queuedInternalEncryptSources.clear()
        encryptionBatchTotal = 1
        encryptionBatchIndex = 1
        pendingInternalEncryptSource = null
        pendingInternalEncryptName = null
        pendingInternalEncryptPointCount = null
        pendingInternalEncryptRepresentation = null
        pendingEncryptInput = uri
        datasetTitleText?.text = "Dataset"
        datasetNameText?.text = displayName(uri) ?: "Selected file"
        resultPanel?.let(controls::removeView)
        resultPanel = null
        encryptActionButton?.text = "Select Recipients & Encrypt"
        toast("Selected another file. Review the recipients before encrypting.")
    }

    private fun beginEncrypt() {
        val rings = selectableEncryptionKeyRings()
        if (rings.isEmpty()) {
            toast("Create an identity or import at least one recipient public key first.")
            return
        }

        val selection = MapSafeSecurityPreferences.read(this)
        val groupRecords = if (selection.hasGroup) {
            exchangeRepository.records(requireNotNull(selection.serverUrl), requireNotNull(selection.groupId))
        } else {
            emptyList()
        }
        val acceptedGroupNames = groupRecords
            .filter { it.trustState == PublicKeyTrustState.ACCEPTED }
            .associate { it.observedFingerprint to it.displayName }
        val localFingerprint = repository.localIdentityInfo()?.fingerprint
        val otherRecipientCount = rings.count { ring ->
            OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint) != localFingerprint
        }
        val orderedRings = rings.sortedWith(
            compareBy<org.bouncycastle.openpgp.PGPPublicKeyRing> {
                val fingerprint = OpenPgpKeyCodec.fingerprint(it.publicKey.fingerprint)
                when {
                    fingerprint == localFingerprint -> 0
                    fingerprint in acceptedGroupNames -> 1
                    else -> 2
                }
            }.thenBy { OpenPgpKeyCodec.keyInfo(it).displayName.lowercase() }
        )

        val checks = orderedRings.map { ring ->
            val info = OpenPgpKeyCodec.keyInfo(ring, repository.localIdentityInfo()?.fingerprint ==
                OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint))
            val groupMemberName = acceptedGroupNames[info.fingerprint]
            val isLocalIdentity = info.fingerprint == localFingerprint
            CheckBox(this).apply {
                text = when {
                    isLocalIdentity -> "Me - ${info.displayName}\n${formatFingerprint(info.fingerprint)}"
                    groupMemberName != null ->
                        "${selection.groupName ?: "Selected group"} / $groupMemberName\n" +
                            "${formatFingerprint(info.fingerprint)} - available offline"
                    else -> "Individual recipient - ${info.displayName}\n${formatFingerprint(info.fingerprint)}"
                }
                isChecked = when {
                    isLocalIdentity -> includeLocalIdentityCheckBox?.isChecked != false
                    groupMemberName != null -> true
                    else -> false
                }
                tag = info.fingerprint
                setPadding(0, dp(5), 0, dp(5))
            }
        }
        val sign = CheckBox(this).apply {
            text = "Sign with my local identity"
            isEnabled = repository.hasLocalIdentity()
            isChecked = isEnabled
            setPadding(0, dp(12), 0, 0)
        }
        lateinit var dialog: AlertDialog
        val addRecipientButton = MapSafeUi.outlineButton(this, "+  Add recipient public key") {
            reopenRecipientSelectionAfterImport = true
            dialog.dismiss()
            importPublicKey.launch(
                arrayOf("application/pgp-keys", "application/octet-stream", "text/plain")
            )
        }
        val recipientViews = mutableListOf<View>(
            MapSafeUi.sectionTitle(this, "Verified recipients")
        ).apply {
            addAll(checks)
            add(addRecipientButton)
        }
        val form = MapSafeUi.page(this).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(MapSafeUi.card(
                this@MapSafeOpenPgpActivity,
                *recipientViews.toTypedArray()
            ))
            addView(MapSafeUi.card(
                this@MapSafeOpenPgpActivity,
                MapSafeUi.sectionTitle(this@MapSafeOpenPgpActivity, "Package authenticity"),
                sign
            ))
        }
        val availableMemberIds = groupRecords
            .filter { it.trustState == PublicKeyTrustState.ACCEPTED }
            .mapTo(mutableSetOf()) { it.identity.userId }
            .apply {
                if (localFingerprint != null) selection.currentUserId?.let(::add)
            }
        val missingMembers = (selection.groupMemberCount - availableMemberIds.size).coerceAtLeast(0)
        val pendingMembers = groupRecords.count { it.needsUserReview }
        val recipientScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(form, ViewGroup.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(420), resources.displayMetrics.heightPixels / 2)
            )
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Select recipients")
            .setMessage(buildString {
                append("The dataset is encrypted once. Each checked recipient receives a separately encrypted session key.")
                append("\n\nYou may select only your identity, only other recipients, or both. " +
                    "Your identity is selected by default so you can decrypt your own copy.")
                if (otherRecipientCount == 0) {
                    append("\n\nNo other recipient keys are available yet. Import a recipient public key here, " +
                        "or cancel and download trusted group keys from Security & Sharing.")
                }
                if (selection.hasGroup) {
                    append("\n\nGroup: ").append(selection.groupName ?: selection.groupId)
                    append("\nAvailable member keys: ").append(availableMemberIds.size)
                    if (missingMembers > 0) append("\nMissing member keys: ").append(missingMembers)
                    if (pendingMembers > 0) append("\nFingerprints awaiting review: ").append(pendingMembers)
                    if (missingMembers > 0 || pendingMembers > 0) {
                        append("\n\nUnchecked or unavailable members will not be able to decrypt this package.")
                    }
                }
            })
            .setView(recipientScroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = checks.filter { it.isChecked }.map { it.tag as String }.toSet()
                if (selected.isEmpty()) {
                    toast("Select at least one recipient.")
                    return@setOnClickListener
                }
                localFingerprint?.let { fingerprint ->
                    includeLocalIdentityCheckBox?.isChecked = fingerprint in selected
                }
                pendingEncryptRecipients = selected
                dialog.dismiss()
                if (sign.isChecked) {
                    promptForPassphrase("Unlock signing key") { passphrase ->
                        pendingSigningPassphrase = passphrase
                        continueEncryptionSelection()
                    }
                } else {
                    continueEncryptionSelection()
                }
            }
        }
        dialog.show()
    }

    private fun continueEncryptionSelection() {
        if (pendingInternalEncryptSource != null || pendingEncryptInput != null) {
            encryptToSaveFolder()
        } else {
            selectEncryptInput.launch(arrayOf("*/*"))
        }
    }

    private fun synchroniseCommunityKeys() {
        val selection = MapSafeSecurityPreferences.read(this)
        if (!selection.hasGroup) {
            AlertDialog.Builder(this)
                .setTitle("Choose a NextGIS community")
                .setMessage(
                    "Connect a NextGIS Web account and choose the trusted community in " +
                        "Security & Sharing before retrieving member keys."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open Security & Sharing") { _, _ ->
                    startActivity(Intent(this, MapSafeSecurityActivity::class.java))
                }
                .show()
            return
        }

        val accountName = requireNotNull(selection.accountName)
        val groupId = requireNotNull(selection.groupId)
        runBusy(
            message = "Refreshing public keys for ${selection.groupName ?: "the selected community"}…",
            operation = { directoryClient.sync(accountName, groupId) },
            onSuccess = { report ->
                refreshStatus(
                    "Community keys refreshed: ${report.acceptedCount} verified; " +
                        "${report.missingMemberIds.size} missing."
                )
                val pending = report.records.count { it.needsUserReview }
                if (pending > 0) {
                    AlertDialog.Builder(this)
                        .setTitle("Fingerprint verification required")
                        .setMessage(
                            "$pending new or changed public-key fingerprint" +
                                if (pending == 1) {
                                    " requires review before it can be selected for encryption."
                                } else {
                                    "s require review before they can be selected for encryption."
                                }
                        )
                        .setNegativeButton("Use verified keys", null)
                        .setPositiveButton("Review") { _, _ ->
                            startActivity(Intent(this, MapSafeSecurityActivity::class.java))
                        }
                        .show()
                } else {
                    toast("Community recipient keys are up to date.")
                }
            }
        )
    }

    private fun encryptToSaveFolder() {
        val inputUri = pendingEncryptInput
        val internalSource = pendingInternalEncryptSource
        if (inputUri == null && internalSource == null) return clearPendingEncryption()
        val selected = pendingEncryptRecipients
        val signingPassphrase = pendingSigningPassphrase
        runBusy(
            message = "Encrypting for ${selected.size} recipient${if (selected.size == 1) "" else "s"}…",
            operation = {
                try {
                    val ringsByFingerprint = selectableEncryptionKeyRings().associateBy {
                        OpenPgpKeyCodec.fingerprint(it.publicKey.fingerprint)
                    }
                    val recipients = selected.mapNotNull(ringsByFingerprint::get)
                    if (recipients.size != selected.size) {
                        throw OpenPgpException("One or more selected recipient keys are no longer available.")
                    }
                    val signingRing = if (signingPassphrase != null) repository.loadLocalSecretKeyRing() else null
                    val inputName = pendingInternalEncryptName
                        ?: inputUri?.let(::displayName)
                        ?: "mapsafe-data"
                    val inputBytes = internalSource?.length()?.takeIf { it >= 0L }
                        ?: inputUri?.let(::contentSize)
                    val saved = MapSafeSaveFolderRepository.save(
                        this@MapSafeOpenPgpActivity,
                        "application/pgp-encrypted",
                        "$inputName.pgp"
                    ) { outputUri ->
                        val inputStream = internalSource?.inputStream()
                            ?: inputUri?.let(contentResolver::openInputStream)
                        inputStream?.use { input ->
                            contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                                val startedAt = SystemClock.elapsedRealtimeNanos()
                                val result = EncryptFile.encrypt(
                                    input = input,
                                    output = output,
                                    originalFileName = inputName,
                                    recipients = recipients,
                                    signingKeyRing = signingRing,
                                    signingPassphrase = signingPassphrase
                                )
                                TimedEncryptionResult(
                                    result = result,
                                    durationNanos = SystemClock.elapsedRealtimeNanos() - startedAt,
                                    inputBytes = inputBytes
                                )
                            } ?: throw OpenPgpException("The Save Folder did not open the encrypted file.")
                        } ?: throw OpenPgpException("The source document could not be opened.")
                    }
                    val packageHash = contentResolver.openInputStream(saved.uri)?.use(HashUtils::sha256)
                        ?: throw OpenPgpException("The saved encrypted package could not be reopened.")
                    val selection = MapSafeSecurityPreferences.read(this@MapSafeOpenPgpActivity)
                    val audience = CommunityPackageAudienceRepository.fromEncryptionRecipients(
                        sha256 = packageHash,
                        fileName = saved.fileName,
                        selection = selection,
                        recipientFingerprints = saved.value.result.recipientFingerprints,
                        localFingerprint = repository.localIdentityInfo()?.fingerprint,
                        directoryRecords = exchangeRepository.records()
                    )
                    audience?.let {
                        CommunityPackageAudienceRepository(this@MapSafeOpenPgpActivity).record(it)
                    }
                    SavedEncryptionBundle(saved, audience)
                } finally {
                    clearEncryptionAttempt()
                }
            },
            onSuccess = { bundle ->
                val saved = bundle.saved
                val timed = saved.value
                val result = timed.result
                val signing = if (result.signerFingerprint == null) "Unsigned" else "Signed"
                MapSafePerformanceLogRepository.recordAsync(
                    this,
                    MapSafePerformanceLogRepository.Record(
                        operation = MapSafePerformanceLogRepository.Operation.OPENPGP_ENCRYPT,
                        datasetName = pendingInternalEncryptName
                            ?: pendingEncryptInput?.let(::displayName)
                            ?: "mapsafe-data",
                        pointCount = pendingInternalEncryptPointCount,
                        inputBytes = timed.inputBytes,
                        outputBytes = contentSize(saved.uri),
                        durationNanos = timed.durationNanos,
                        recipientCount = result.recipientFingerprints.size,
                        signed = result.signerFingerprint != null
                    )
                )
                latestEncryptedUri = saved.uri
                latestEncryptedFileName = saved.fileName
                refreshStatus()
                createIdentityButton?.visibility = View.GONE
                encryptActionButton?.text = "Reselect Recipients & Encrypt"
                val hasAnotherDataset = queuedInternalEncryptSources.isNotEmpty()
                showResultPanel(
                    "✓  Dataset protected",
                    "${result.contentProtection.displayName} encryption",
                    "${result.recipientFingerprints.size} authorised recipient(s) · $signing package ready to share",
                    nextLabel = if (hasAnotherDataset) {
                        "Next: Encrypt (${queuedInternalEncryptSources.size} remaining)"
                    } else {
                        "Next: Notarise"
                    },
                    onNext = if (hasAnotherDataset) ::advanceToNextEncryptionSource else ::openNotarisation,
                    onStop = ::stopWorkflow,
                    placeAfterEncryptionAction = true,
                    savedLocation = saved.displayLocation,
                    communityActionLabel = "Upload to Community",
                    onCommunityAction = ::publishLatestEncryptedPackage
                )
            }
        )
    }

    private fun beginDecrypt() {
        if (!repository.hasLocalIdentity()) {
            toast("Create or import the recipient's local identity first.")
            return
        }
        if (verifiedDecryptInput != null) {
            recheckVerifiedDecryptInput()
        } else {
            selectDecryptInput.launch(arrayOf("application/pgp-encrypted", "application/octet-stream", "*/*"))
        }
    }

    private fun verifiedDecryptSourceCard(): View {
        val fileName = verifiedDecryptDisplayName
            ?: verifiedDecryptInput?.let(::displayName)
            ?: "Verified encrypted file"
        val network = verifiedDecryptNetworkName?.takeIf(String::isNotBlank)
        val transactionHash = verifiedDecryptTransactionHash?.takeIf(String::isNotBlank)
        val blockchainMatched = network != null && transactionHash != null
        val details = mutableListOf<View>(
            MapSafeUi.sectionTitle(
                this,
                if (blockchainMatched) "Blockchain-Verified File" else "Hash-Checked Encrypted File"
            ),
            MapSafeUi.text(this, fileName, 15f, MapSafeUi.TEXT, bold = true)
        )
        if (blockchainMatched) {
            details += MapSafeUi.divider(this)
            details += MapSafeUi.valueRow(this, "Network", requireNotNull(network))
            details += MapSafeUi.text(this, "Transaction", 12f, MapSafeUi.MUTED)
            details += MapSafeUi.text(this, requireNotNull(transactionHash), 12f).apply {
                setTextIsSelectable(true)
            }
        }
        return MapSafeUi.card(this, *details.toTypedArray(), pale = true)
    }

    private fun recheckVerifiedDecryptInput() {
        val uri = verifiedDecryptInput ?: return
        val expectedHash = verifiedDecryptSha256 ?: return
        runBusy(
            message = "Rechecking the selected encrypted file…",
            operation = {
                contentResolver.openInputStream(uri)?.use(HashUtils::sha256)
                    ?: throw OpenPgpException("The selected encrypted file could not be opened.")
            },
            onSuccess = { currentHash ->
                if (currentHash != expectedHash) {
                    clearVerifiedDecryptSource()
                    AlertDialog.Builder(this)
                        .setTitle("Verified file changed")
                        .setMessage(
                            "This file no longer matches the hash verified on the blockchain. " +
                                "Decryption has been stopped; return to Verify and check the file again."
                        )
                        .setCancelable(false)
                        .setPositiveButton("Return to Verify") { _, _ -> finish() }
                        .show()
                } else {
                    prepareDecryptInput(uri)
                }
            }
        )
    }

    private fun prepareDecryptInput(uri: Uri) {
        pendingDecryptInput = uri
        promptForPassphrase("Unlock private key") { passphrase ->
            pendingDecryptPassphrase = passphrase
            val inputName = verifiedDecryptDisplayName ?: displayName(uri) ?: "mapsafe-data.pgp"
            val suggested = inputName.removeSuffix(".pgp").removeSuffix(".gpg")
                .ifBlank { "mapsafe-decrypted-data" }
            decryptToSaveFolder(suggested)
        }
    }

    private fun decryptToSaveFolder(outputName: String) {
        val inputUri = pendingDecryptInput ?: return clearPendingDecryption()
        val passphrase = pendingDecryptPassphrase ?: return clearPendingDecryption()
        val expectedVerifiedHash = verifiedDecryptSha256
        val encryptedInputBytes = contentSize(inputUri)
        runBusy(
            message = "Decrypting and checking integrity…",
            operation = {
                val temporary = File.createTempFile("mapsafe-decrypted-", ".tmp", cacheDir)
                val temporaryKey = ByteArray(32).also(SecureRandom()::nextBytes)
                val temporaryIv = ByteArray(12).also(SecureRandom()::nextBytes)
                try {
                    val secretRing = repository.loadLocalSecretKeyRing()
                        ?: throw OpenPgpException("The local OpenPGP identity is unavailable.")
                    val result = contentResolver.openInputStream(inputUri)?.use { input ->
                        val digest = expectedVerifiedHash?.let { MessageDigest.getInstance("SHA-256") }
                        val protectedInput = digest?.let { DigestInputStream(input, it) } ?: input
                        val temporaryKeySpec = SecretKeySpec(temporaryKey, "AES")
                        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.ENCRYPT_MODE, temporaryKeySpec, GCMParameterSpec(128, temporaryIv))
                        }
                        val startedAt = SystemClock.elapsedRealtimeNanos()
                        val decryptionResult = CipherOutputStream(
                            temporary.outputStream().buffered(),
                            encryptCipher
                        ).use { temporaryOutput ->
                            DecryptFile.decrypt(
                                input = protectedInput,
                                output = temporaryOutput,
                                secretKeyRings = listOf(secretRing),
                                passphrase = passphrase,
                                verificationKeyRings = repository.listPublicKeyRings()
                            )
                        }
                        val decryptionDurationNanos = SystemClock.elapsedRealtimeNanos() - startedAt
                        if (digest != null) {
                            val remainder = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (protectedInput.read(remainder) >= 0) Unit
                            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                            if (actualHash != expectedVerifiedHash) {
                                throw OpenPgpException(
                                    "The encrypted file changed after blockchain verification. " +
                                        "Decryption was stopped before any output was released."
                                )
                            }
                        }
                        TimedDecryptionResult(
                            result = decryptionResult,
                            durationNanos = decryptionDurationNanos,
                            inputBytes = encryptedInputBytes
                        )
                    } ?: throw OpenPgpException("The encrypted document could not be opened.")

                    val recoveredName = result.result.originalFileName.takeIf(String::isNotBlank) ?: outputName
                    MapSafeSaveFolderRepository.save(
                        this@MapSafeOpenPgpActivity,
                        if (MapSafeGeoJsonWorkflow.isGeoJsonFileName(recoveredName)) {
                            "application/geo+json"
                        } else {
                            "application/octet-stream"
                        },
                        recoveredName
                    ) { outputUri ->
                        contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                            val temporaryKeySpec = SecretKeySpec(temporaryKey, "AES")
                            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                                init(Cipher.DECRYPT_MODE, temporaryKeySpec, GCMParameterSpec(128, temporaryIv))
                            }
                            CipherInputStream(temporary.inputStream().buffered(), decryptCipher).use { input ->
                                input.copyTo(output)
                            }
                        } ?: throw OpenPgpException("The Save Folder did not open the decrypted file.")
                        result
                    }
                } finally {
                    temporaryKey.fill(0)
                    temporaryIv.fill(0)
                    temporary.delete()
                    clearPendingDecryption()
                }
            },
            onSuccess = { saved ->
                val timed = saved.value
                val result = timed.result
                val outputUri = saved.uri
                MapSafePerformanceLogRepository.recordAsync(
                    this,
                    MapSafePerformanceLogRepository.Record(
                        operation = MapSafePerformanceLogRepository.Operation.OPENPGP_DECRYPT_VERIFY,
                        datasetName = result.originalFileName,
                        inputBytes = timed.inputBytes,
                        outputBytes = contentSize(outputUri),
                        durationNanos = timed.durationNanos,
                        signed = result.signatureStatus != OpenPgpSignatureStatus.NOT_SIGNED
                    )
                )
                decryptActionButton?.let(controls::removeView)
                decryptActionButton = null
                refreshStatus(decryptionMessage(result))
                showResultPanel(
                    "✓  Decryption successful",
                    "${result.originalFileName} is ready to view",
                    "Integrity verified · ${result.signatureStatus.name.lowercase().replace('_', ' ')}",
                    nextLabel = "Next: Access",
                    onNext = { offerGeoJsonImport(outputUri, result, saved.displayLocation) },
                    onStop = ::stopWorkflow,
                    savedLocation = saved.displayLocation
                )
                offerGeoJsonImport(outputUri, result, saved.displayLocation)
            }
        )
    }

    private fun offerGeoJsonImport(
        outputUri: Uri,
        result: OpenPgpDecryptionResult,
        savedLocation: String
    ) {
        if (!MapSafeGeoJsonWorkflow.isGeoJsonFileName(result.originalFileName)) return
        if (result.signatureStatus == OpenPgpSignatureStatus.INVALID) {
            toast("The decrypted GeoJSON was not offered for import because its signature is invalid.")
            return
        }

        val trustNote = when (result.signatureStatus) {
            OpenPgpSignatureStatus.VALID -> "Its signature is valid."
            OpenPgpSignatureStatus.UNKNOWN_SIGNER ->
                "Its signer is unknown, so authenticity has not been verified."
            OpenPgpSignatureStatus.NOT_SIGNED -> "The package was not signed."
            OpenPgpSignatureStatus.INVALID -> return
        }

        AlertDialog.Builder(this)
            .setTitle("Import decrypted layer?")
            .setMessage(
                "${result.originalFileName} passed the OpenPGP integrity check. $trustNote " +
                    "Import it as a NextGIS vector layer and zoom to it?"
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Import and continue") { _, _ ->
                importDecryptedGeoJson(outputUri, result.originalFileName, savedLocation)
            }
            .show()
    }

    private fun importDecryptedGeoJson(
        outputUri: Uri,
        originalFileName: String,
        savedLocation: String
    ) {
        runBusy(
            message = "Importing decrypted GeoJSON layer...",
            operation = {
                val importDirectory = File(cacheDir, "mapsafe/imports")
                require(importDirectory.exists() || importDirectory.mkdirs()) {
                    "The temporary MapSafe import directory could not be created."
                }
                val temporary = File.createTempFile("mapsafe-import-", ".geojson", importDirectory)
                try {
                    contentResolver.openInputStream(outputUri)?.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw OpenPgpException("The decrypted GeoJSON output could not be reopened.")
                    MapSafeGeoJsonWorkflow.importLayer(
                        application as MainApplication,
                        temporary,
                        originalFileName
                    )
                } finally {
                    temporary.delete()
                }
            },
            onSuccess = { imported -> showImportedDatasetActions(imported, savedLocation) }
        )
    }

    private fun showImportedDatasetActions(
        imported: MapSafeGeoJsonWorkflow.ImportResult,
        savedLocation: String
    ) {
        refreshStatus(
            "Imported ${imported.featureCount} verified feature(s) into ${imported.layerName}."
        )
        showResultPanel(
            "✓  Dataset ready to access",
            imported.layerName,
            "Integrity checked · ${imported.featureCount} feature(s) ready to display on the map",
            nextLabel = "Next: Access",
            onNext = { openImportedLayer(imported) },
            onStop = ::stopWorkflow,
            placeAtBottom = true,
            savedLocation = savedLocation
        )
    }

    private fun openNotarisation() {
        val encryptedUri = latestEncryptedUri
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_MAPSAFE_OPEN_NOTARISATION)
                .apply {
                    encryptedUri?.let {
                        putExtra(MainActivity.EXTRA_MAPSAFE_ENCRYPTED_URI, it.toString())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    latestEncryptedFileName?.let {
                        putExtra(MainActivity.EXTRA_MAPSAFE_ENCRYPTED_FILE_NAME, it)
                    }
                }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun publishLatestEncryptedPackage() {
        val encryptedUri = latestEncryptedUri
        if (encryptedUri == null) {
            toast("Encrypt a dataset before uploading it to the community.")
            return
        }
        val selection = MapSafeSecurityPreferences.read(this)
        if (!selection.hasGroup) {
            AlertDialog.Builder(this)
                .setTitle("Choose a NextGIS community")
                .setMessage(
                    "Sign in to NextGIS and choose the preconfigured community in Security & Sharing before uploading."
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open Security & Sharing") { _, _ ->
                    startActivity(Intent(this, MapSafeSecurityActivity::class.java))
                }
                .show()
            return
        }
        val fileName = latestEncryptedFileName
            ?.takeIf(String::isNotBlank)
            ?: "mapsafe-package.pgp"
        runBusy(
            message = "Checking the encrypted package audience…",
            operation = {
                val temporary = File.createTempFile("mapsafe-community-package-", ".pgp", cacheDir)
                try {
                    contentResolver.openInputStream(encryptedUri)?.use { input ->
                        temporary.outputStream().use(input::copyTo)
                    } ?: throw NextGisCommunityPublishException(
                        "The encrypted package could not be opened for upload."
                    )
                    val digest = HashUtils.sha256(temporary)
                    val audience = CommunityPackageAudienceRepository(applicationContext)
                        .find(digest, selection)
                        ?: throw NextGisCommunityPublishException(
                            "MapSafe cannot determine this package's NextGIS recipients. " +
                                "Encrypt it again after selecting the community and its accepted public keys."
                        )
                    PreparedCommunityPackage(
                        source = temporary,
                        fileName = fileName,
                        selection = selection,
                        audienceRecord = audience
                    )
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
            },
            onSuccess = ::confirmCommunityPackageAudience
        )
    }

    private fun confirmCommunityPackageAudience(prepared: PreparedCommunityPackage) {
        val audience = prepared.audienceRecord.audience
        if (!audience.isReadyForRestrictedUpload) {
            prepared.source.delete()
            val missing = audience.unmappedFingerprints.size
            AlertDialog.Builder(this)
                .setTitle("Community audience incomplete")
                .setMessage(
                    "$missing encrypted recipient${if (missing == 1) " is" else "s are"} not linked " +
                        "to accepted members of ${prepared.selection.groupName ?: "the selected community"}. " +
                        "Refresh and verify the community keys, then encrypt the dataset again."
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val names = audience.members.joinToString("\n") { "• ${it.displayName}" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirm community access")
            .setMessage(
                "${prepared.fileName}\n\nOnly the publisher and these OpenPGP recipients will be " +
                    "allowed to list and download the package:\n\n$names\n\n" +
                    "Other community members may still receive separately published anonymised layers."
            )
            .setNegativeButton(android.R.string.cancel) { _, _ -> prepared.source.delete() }
            .setPositiveButton("Upload") { _, _ -> uploadPreparedCommunityPackage(prepared) }
            .create()
        dialog.setOnCancelListener { prepared.source.delete() }
        dialog.show()
    }

    private fun uploadPreparedCommunityPackage(prepared: PreparedCommunityPackage) {
        runBusy(
            message = "Uploading encrypted package to " +
                "${prepared.selection.groupName ?: "the selected community"}…",
            operation = {
                try {
                    val profile = runCatching {
                        BlockchainNetworkProfileRepository(applicationContext).load().activeProfile
                    }.getOrElse { BlockchainNetworkPresets.defaults().activeProfile }
                    NextGisCommunityPublisher(applicationContext).publishAttachedFile(
                        selection = prepared.selection,
                        source = prepared.source,
                        fileName = prepared.fileName,
                        mimeType = "application/pgp-encrypted",
                        artifactType = CommunityArtifactType.ENCRYPTED_PACKAGE,
                        blockchain = CommunityBlockchainReference(
                            networkName = profile.displayName,
                            chainId = profile.chainId,
                            contractAddress = profile.contractAddress
                        ),
                        audience = prepared.audienceRecord.audience
                    )
                } finally {
                    prepared.source.delete()
                }
            },
            onSuccess = { published ->
                refreshStatus("Encrypted package uploaded to ${published.communityName}.")
                AlertDialog.Builder(this)
                    .setTitle("Uploaded to ${published.communityName}")
                    .setMessage(
                        "${published.fileName} and its SHA-256 were stored in NextGIS Web for the confirmed recipients. " +
                            "If this package is notarised next, MapSafe will add the confirmed network and transaction to its matching community record."
                    )
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton("Open Web GIS") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(published.resourceWebUrl)))
                    }
                    .show()
            }
        )
    }

    private fun stopWorkflow() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun openSaveFolder() {
        if (!MapSafeSaveFolderRepository.openFolder(this)) {
            toast("Downloads/MapSafe could not be opened in the Files app.")
        }
    }

    private fun openImportedLayer(imported: MapSafeGeoJsonWorkflow.ImportResult) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_MAPSAFE_LAYER_IMPORTED)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_MAPSAFE_LAYER_NAME, imported.layerName)
                .putExtra(MainActivity.EXTRA_MAPSAFE_FEATURE_COUNT, imported.featureCount)
                .putExtra(MainActivity.EXTRA_MAPSAFE_MIN_X, imported.extent.minX.toDouble())
                .putExtra(MainActivity.EXTRA_MAPSAFE_MAX_X, imported.extent.maxX.toDouble())
                .putExtra(MainActivity.EXTRA_MAPSAFE_MIN_Y, imported.extent.minY.toDouble())
                .putExtra(MainActivity.EXTRA_MAPSAFE_MAX_Y, imported.extent.maxY.toDouble())
        )
        finish()
    }

    @Deprecated("AndroidX dispatch remains compatible with this activity's existing back behavior")
    override fun onBackPressed() {
        if (::controls.isInitialized && !controls.isEnabled) return
        returnToParentMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (controls.isEnabled) returnToParentMenu()
        return true
    }

    private fun returnToParentMenu() {
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_SECURITY, false)) {
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_MAPSAFE_SHOW_PARENT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(
                    MainActivity.EXTRA_MAPSAFE_ACCESS_PARENT,
                    intent.getStringExtra(EXTRA_MODE) == MODE_DECRYPT
                )
        )
        finish()
    }

    private fun decryptionMessage(result: OpenPgpDecryptionResult): String {
        val signature = when (result.signatureStatus) {
            OpenPgpSignatureStatus.NOT_SIGNED -> "Package was not signed."
            OpenPgpSignatureStatus.VALID -> "Signature is cryptographically valid."
            OpenPgpSignatureStatus.INVALID -> "WARNING: Signature is invalid."
            OpenPgpSignatureStatus.UNKNOWN_SIGNER -> "Signature could not be checked because the signer key is unknown."
        }
        return "Decrypted ${result.originalFileName} (${result.contentProtection.displayName}). " +
            "Integrity check passed. $signature"
    }

    private fun showKeys() {
        val keys = repository.listKeyInfo()
        val message = if (keys.isEmpty()) {
            "No OpenPGP keys have been added."
        } else {
            keys.joinToString("\n\n") { info ->
                buildString {
                    append(if (info.hasSecretKey) "Local identity\n" else "Recipient\n")
                    append(info.displayName).append('\n')
                    append(formatFingerprint(info.fingerprint))
                    if (!info.canEncrypt) append("\nNo usable encryption key")
                    exchangeRepository.trustStateForFingerprint(info.fingerprint)?.let { state ->
                        append("\nDirectory trust: ").append(state.displayLabel())
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("OpenPGP keys")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun selectableEncryptionKeyRings(): List<org.bouncycastle.openpgp.PGPPublicKeyRing> {
        val localFingerprint = repository.localIdentityInfo()?.fingerprint
        val blocked = exchangeRepository.nonSelectableFingerprints()
        return repository.listPublicKeyRings().filter { ring ->
            val fingerprint = OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint)
            OpenPgpKeyCodec.findEncryptionKey(ring) != null &&
                (fingerprint == localFingerprint || fingerprint !in blocked)
        }
    }

    private fun PublicKeyTrustState.displayLabel(): String = when (this) {
        PublicKeyTrustState.DISCOVERED -> "discovered — confirmation required"
        PublicKeyTrustState.ACCEPTED -> "accepted"
        PublicKeyTrustState.CHANGE_PENDING -> "changed — confirmation required"
        PublicKeyTrustState.SUPERSEDED -> "superseded"
        PublicKeyTrustState.REVOKED -> "revoked"
        PublicKeyTrustState.MEMBER_REMOVED -> "member removed"
    }

    private fun promptForPassphrase(title: String, onPassphrase: (CharArray) -> Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val input = passwordField("Recovery passphrase")
        val entry = passphraseEntry(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(verticalForm(entry))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toCharArray()
                if (value.isEmpty()) {
                    toast("Enter the private-key passphrase.")
                } else {
                    input.text?.clear()
                    dialog.dismiss()
                    onPassphrase(value)
                }
            }
        }
        dialog.setOnDismissListener {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
    }

    private fun showResultPanel(
        title: String,
        subtitle: String,
        details: String,
        nextLabel: String? = null,
        onNext: (() -> Unit)? = null,
        onStop: (() -> Unit)? = null,
        placeAfterEncryptionAction: Boolean = false,
        placeAtBottom: Boolean = false,
        savedLocation: String? = null,
        communityActionLabel: String? = null,
        onCommunityAction: (() -> Unit)? = null
    ) {
        resultPanel?.let(controls::removeView)
        val summaryChildren = mutableListOf<View>(
            MapSafeUi.text(this, title, 18f, MapSafeUi.GREEN_TEXT, bold = true),
            MapSafeUi.text(this, subtitle, 15f, MapSafeUi.TEXT, bold = true).apply {
                setPadding(0, dp(5), 0, 0)
            },
            MapSafeUi.text(this, details, 14f, MapSafeUi.GREEN_TEXT).apply {
                setPadding(0, dp(5), 0, 0)
            }
        )
        savedLocation?.let { location ->
            summaryChildren += MapSafeUi.savedLocationRow(
                this,
                MapSafeUi.text(
                    this,
                    "Saved: ${location.substringAfterLast('/')}",
                    13f,
                    MapSafeUi.GREEN_TEXT,
                    bold = true
                ),
                ::openSaveFolder
            )
        }
        if (communityActionLabel != null && onCommunityAction != null) {
            summaryChildren += MapSafeUi.outlineButton(
                this,
                communityActionLabel,
                onCommunityAction
            )
        }
        val actions = if (nextLabel != null && onNext != null && onStop != null) {
            MapSafeUi.nextStopActions(this, nextLabel, onNext, onStop)
        } else {
            null
        }
        resultPanel = if (placeAfterEncryptionAction) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(MapSafeUi.card(
                    this@MapSafeOpenPgpActivity,
                    *summaryChildren.toTypedArray(),
                    pale = true
                ))
                actions?.let { actionRow ->
                    actionRow.setPadding(0, dp(4), 0, dp(20))
                    addView(actionRow)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
        } else {
            actions?.let(summaryChildren::add)
            MapSafeUi.card(
                this,
                *summaryChildren.toTypedArray(),
                pale = true
            )
        }
        if (placeAfterEncryptionAction) {
            val actionIndex = encryptActionButton?.let(controls::indexOfChild) ?: -1
            controls.addView(resultPanel, if (actionIndex >= 0) actionIndex + 1 else controls.childCount)
        } else if (placeAtBottom) {
            controls.addView(resultPanel)
        } else {
            controls.addView(resultPanel, 1)
        }
    }

    private fun <T> runBusy(message: String, operation: suspend () -> T, onSuccess: (T) -> Unit) {
        setBusy(true, message)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { operation() } }
            setBusy(false, null)
            result.onSuccess(onSuccess).onFailure { error ->
                val shown = when (error) {
                    is OpenPgpException, is NextGisCommunityPublishException -> error.message
                    else -> error.message ?: error.javaClass.simpleName
                }
                refreshStatus("Operation failed: $shown")
                AlertDialog.Builder(this@MapSafeOpenPgpActivity)
                    .setTitle("Operation failed")
                    .setMessage(shown)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String?) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        controls.isEnabled = !busy
        for (index in 0 until controls.childCount) controls.getChildAt(index).isEnabled = !busy
        if (message != null) statusText.text = message
    }

    private fun clearPendingEncryption(deleteInternalSource: Boolean = false) {
        clearEncryptionAttempt()
        pendingEncryptInput = null
        pendingInternalEncryptPointCount = null
        if (deleteInternalSource) {
            pendingInternalEncryptSource?.let(::deleteTemporaryEncryptionSource)
            queuedInternalEncryptSources.forEach { deleteTemporaryEncryptionSource(it.file) }
            queuedInternalEncryptSources.clear()
            pendingInternalEncryptSource = null
            pendingInternalEncryptName = null
            pendingInternalEncryptRepresentation = null
        }
    }

    private fun clearEncryptionAttempt() {
        pendingSigningPassphrase?.fill('\u0000')
        pendingSigningPassphrase = null
        pendingEncryptRecipients = emptySet()
    }

    private fun loadInternalEncryptSource() {
        val batchPaths = intent.getStringArrayListExtra(EXTRA_BATCH_SOURCE_PATHS)
        if (!batchPaths.isNullOrEmpty()) {
            val names = intent.getStringArrayListExtra(EXTRA_BATCH_SOURCE_NAMES).orEmpty()
            val representations = intent.getStringArrayListExtra(EXTRA_BATCH_SOURCE_REPRESENTATIONS).orEmpty()
            val counts = intent.getIntegerArrayListExtra(EXTRA_BATCH_SOURCE_POINT_COUNTS).orEmpty()
            val sources = batchPaths.mapIndexedNotNull { index, path ->
                validatedInternalSource(path)?.let { source ->
                    InternalEncryptionSource(
                        file = source,
                        displayName = names.getOrNull(index)?.let { File(it).name }?.takeIf(String::isNotBlank)
                            ?: source.name,
                        pointCount = counts.getOrNull(index)?.takeIf { it >= 0 },
                        representation = representations.getOrNull(index)?.takeIf(String::isNotBlank)
                            ?: "Selected dataset"
                    )
                }
            }
            if (sources.isNotEmpty()) {
                encryptionBatchTotal = sources.size
                encryptionBatchIndex = 1
                applyInternalEncryptionSource(sources.first())
                queuedInternalEncryptSources.addAll(sources.drop(1))
                return
            }
        }
        val path = intent.getStringExtra(EXTRA_INTERNAL_SOURCE_PATH) ?: return
        val source = validatedInternalSource(path) ?: return
        applyInternalEncryptionSource(
            InternalEncryptionSource(
                file = source,
                displayName = intent.getStringExtra(EXTRA_INTERNAL_SOURCE_NAME)
                    ?.let { File(it).name }
                    ?.takeIf { it.isNotBlank() }
                    ?: source.name,
                pointCount = intent.getIntExtra(EXTRA_INTERNAL_SOURCE_POINT_COUNT, -1)
                    .takeIf { it >= 0 },
                representation = intent.getStringExtra(EXTRA_SOURCE_REPRESENTATION)
                    ?.takeIf(String::isNotBlank)
                    ?: "Selected dataset"
            )
        )
    }

    private fun validatedInternalSource(path: String): File? {
        val source = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val allowedRoot = runCatching { File(cacheDir, "mapsafe").canonicalFile }.getOrNull() ?: return null
        val isInsideRoot = source.path.startsWith(allowedRoot.path + File.separator)
        return source.takeIf { isInsideRoot && it.isFile && it.length() > 0L }
    }

    private fun applyInternalEncryptionSource(source: InternalEncryptionSource) {
        pendingInternalEncryptSource = source.file
        pendingInternalEncryptName = source.displayName
        pendingInternalEncryptPointCount = source.pointCount
        pendingInternalEncryptRepresentation = source.representation
    }

    private fun advanceToNextEncryptionSource() {
        val next = queuedInternalEncryptSources.removeFirstOrNull() ?: return openNotarisation()
        pendingInternalEncryptSource?.let(::deleteTemporaryEncryptionSource)
        applyInternalEncryptionSource(next)
        encryptionBatchIndex++
        latestEncryptedUri = null
        latestEncryptedFileName = null
        resultPanel?.let(controls::removeView)
        resultPanel = null
        val representation = pendingInternalEncryptRepresentation ?: "Selected dataset"
        val name = pendingInternalEncryptName ?: next.file.name
        datasetTitleText?.text = when {
            isAnonymisedDataset(name, representation) -> "Anonymised Dataset"
            representation.contains("original", ignoreCase = true) -> "Original Dataset"
            else -> "Dataset to Encrypt"
        }
        datasetNameText?.text = "$name  ·  $encryptionBatchIndex of $encryptionBatchTotal"
        encryptActionButton?.text = "Select Recipients & Encrypt"
        refreshStatus("Next dataset ready. Review its recipients before encrypting.")
    }

    private fun deleteTemporaryEncryptionSource(source: File) {
        source.delete()
        val itemDirectory = source.parentFile
        itemDirectory?.delete()
        itemDirectory?.parentFile?.takeIf { it.name.startsWith("batch-") }?.delete()
    }

    private fun loadVerifiedDecryptSource() {
        if (intent.getStringExtra(EXTRA_MODE) != MODE_DECRYPT) return
        val uri = intent.getStringExtra(EXTRA_VERIFIED_SOURCE_URI)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
            ?: return
        val expectedHash = intent.getStringExtra(EXTRA_VERIFIED_SOURCE_SHA256)
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(SHA_256_PATTERN) }
            ?: return

        verifiedDecryptInput = uri
        verifiedDecryptSha256 = expectedHash
        verifiedDecryptDisplayName = intent.getStringExtra(EXTRA_VERIFIED_SOURCE_DISPLAY_NAME)
            ?.takeIf(String::isNotBlank)
        verifiedDecryptNetworkName = intent.getStringExtra(EXTRA_VERIFICATION_NETWORK_NAME)
            ?.takeIf(String::isNotBlank)
        verifiedDecryptTransactionHash = intent.getStringExtra(EXTRA_VERIFICATION_TRANSACTION_HASH)
            ?.takeIf(String::isNotBlank)
    }

    private fun clearVerifiedDecryptSource() {
        verifiedDecryptInput = null
        verifiedDecryptSha256 = null
        verifiedDecryptDisplayName = null
        verifiedDecryptNetworkName = null
        verifiedDecryptTransactionHash = null
    }

    private fun clearPendingDecryption() {
        pendingDecryptPassphrase?.fill('\u0000')
        pendingDecryptPassphrase = null
        pendingDecryptInput = null
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        } finally {
            cursor?.close()
        }
    }

    private fun contentSize(uri: Uri): Long? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf(File::isFile)?.length()
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() == true && !cursor.isNull(0)) cursor.getLong(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun inputField(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = type
            isSingleLine = true
        }
    }

    private fun passwordField(hint: String): EditText {
        return inputField(
            hint,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
    }

    private fun passphraseEntry(input: EditText): LinearLayout {
        var visible = false
        val visibilityButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setColorFilter(MapSafeUi.GREEN_TEXT)
            background = null
            contentDescription = "Show passphrase"
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                visible = !visible
                input.transformationMethod = if (visible) {
                    null
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                input.setSelection(input.text?.length ?: 0)
                contentDescription = if (visible) "Hide passphrase" else "Show passphrase"
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                visibilityButton,
                LinearLayout.LayoutParams(dp(48), dp(48))
            )
        }
    }

    private fun verticalForm(vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            views.forEach(::addView)
        }
    }

    private fun formatFingerprint(fingerprint: String): String {
        return fingerprint.chunked(4).joinToString(" ")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MODE = "mapsafe_openpgp_mode"
        private const val EXTRA_INTERNAL_SOURCE_PATH = "mapsafe_openpgp_internal_source_path"
        private const val EXTRA_INTERNAL_SOURCE_NAME = "mapsafe_openpgp_internal_source_name"
        private const val EXTRA_INTERNAL_SOURCE_POINT_COUNT =
            "mapsafe_openpgp_internal_source_point_count"
        private const val EXTRA_SOURCE_REPRESENTATION = "mapsafe_openpgp_source_representation"
        private const val EXTRA_BATCH_SOURCE_PATHS = "mapsafe_openpgp_batch_source_paths"
        private const val EXTRA_BATCH_SOURCE_NAMES = "mapsafe_openpgp_batch_source_names"
        private const val EXTRA_BATCH_SOURCE_POINT_COUNTS = "mapsafe_openpgp_batch_source_point_counts"
        private const val EXTRA_BATCH_SOURCE_REPRESENTATIONS =
            "mapsafe_openpgp_batch_source_representations"
        private const val EXTRA_RETURN_TO_SECURITY = "mapsafe_openpgp_return_to_security"
        private const val EXTRA_VERIFIED_SOURCE_URI = "mapsafe_openpgp_verified_source_uri"
        private const val EXTRA_VERIFIED_SOURCE_DISPLAY_NAME =
            "mapsafe_openpgp_verified_source_display_name"
        private const val EXTRA_VERIFIED_SOURCE_SHA256 = "mapsafe_openpgp_verified_source_sha256"
        private const val EXTRA_VERIFICATION_NETWORK_NAME = "mapsafe_openpgp_verification_network_name"
        private const val EXTRA_VERIFICATION_TRANSACTION_HASH =
            "mapsafe_openpgp_verification_transaction_hash"
        private const val MODE_ENCRYPT = "encrypt"
        private const val MODE_DECRYPT = "decrypt"
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")

        fun intent(
            context: Context,
            decrypt: Boolean = false,
            sourceFile: File? = null,
            sourceDisplayName: String? = null,
            sourcePointCount: Int? = null,
            sourceRepresentation: String? = null,
            returnToSecurity: Boolean = false,
            verifiedSourceUri: Uri? = null,
            verifiedSourceDisplayName: String? = null,
            verifiedSourceSha256: String? = null,
            verificationNetworkName: String? = null,
            verificationTransactionHash: String? = null
        ): Intent {
            return Intent(context, MapSafeOpenPgpActivity::class.java)
                .putExtra(EXTRA_MODE, if (decrypt) MODE_DECRYPT else MODE_ENCRYPT)
                .putExtra(EXTRA_RETURN_TO_SECURITY, returnToSecurity)
                .apply {
                    if (sourceFile != null) {
                        putExtra(EXTRA_INTERNAL_SOURCE_PATH, sourceFile.absolutePath)
                        putExtra(EXTRA_INTERNAL_SOURCE_NAME, sourceDisplayName ?: sourceFile.name)
                        sourcePointCount?.let { putExtra(EXTRA_INTERNAL_SOURCE_POINT_COUNT, it) }
                        sourceRepresentation?.let { putExtra(EXTRA_SOURCE_REPRESENTATION, it) }
                    }
                    if (verifiedSourceUri != null) {
                        putExtra(EXTRA_VERIFIED_SOURCE_URI, verifiedSourceUri.toString())
                        verifiedSourceDisplayName?.let {
                            putExtra(EXTRA_VERIFIED_SOURCE_DISPLAY_NAME, it)
                        }
                        verifiedSourceSha256?.let {
                            putExtra(EXTRA_VERIFIED_SOURCE_SHA256, it.lowercase(Locale.ROOT))
                        }
                        verificationNetworkName?.let {
                            putExtra(EXTRA_VERIFICATION_NETWORK_NAME, it)
                        }
                        verificationTransactionHash?.let {
                            putExtra(EXTRA_VERIFICATION_TRANSACTION_HASH, it)
                        }
                    }
                }
        }

        fun intent(context: Context, sources: List<EncryptionSource>): Intent {
            require(sources.isNotEmpty()) { "At least one encryption source is required." }
            return Intent(context, MapSafeOpenPgpActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_ENCRYPT)
                .putStringArrayListExtra(
                    EXTRA_BATCH_SOURCE_PATHS,
                    ArrayList(sources.map { it.file.absolutePath })
                )
                .putStringArrayListExtra(
                    EXTRA_BATCH_SOURCE_NAMES,
                    ArrayList(sources.map(EncryptionSource::displayName))
                )
                .putIntegerArrayListExtra(
                    EXTRA_BATCH_SOURCE_POINT_COUNTS,
                    ArrayList(sources.map { it.pointCount ?: -1 })
                )
                .putStringArrayListExtra(
                    EXTRA_BATCH_SOURCE_REPRESENTATIONS,
                    ArrayList(sources.map(EncryptionSource::representation))
                )
        }
    }
}
