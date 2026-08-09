package com.nextgis.mobile.mapsafe.ui

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
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
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpDecryptionResult
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.safeguard.encryption.EncryptFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Self-contained MapSafe OpenPGP identity, recipient, encryption and decryption screen. */
class MapSafeOpenPgpActivity : AppCompatActivity() {
    private lateinit var repository: OpenPgpKeyRepository
    private lateinit var exchangeRepository: PublicKeyExchangeRepository
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: LinearLayout
    private var resultPanel: View? = null

    private var pendingEncryptInput: Uri? = null
    private var pendingInternalEncryptSource: File? = null
    private var pendingInternalEncryptName: String? = null
    private var pendingEncryptRecipients: Set<String> = emptySet()
    private var pendingSigningPassphrase: CharArray? = null
    private var pendingDecryptInput: Uri? = null
    private var pendingDecryptPassphrase: CharArray? = null
    private var pendingSecretImport: Uri? = null

    private val importPublicKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importPublicKey)
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
            val sourceName = displayName(uri) ?: "mapsafe-data"
            createEncryptedOutput.launch("$sourceName.pgp")
        }
    }
    private val createEncryptedOutput = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pgp-encrypted")
    ) { uri ->
        if (uri == null) clearPendingEncryption() else encryptTo(uri)
    }
    private val selectDecryptInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingDecryptInput = uri
            promptForPassphrase("Unlock private key") { passphrase ->
                pendingDecryptPassphrase = passphrase
                val inputName = displayName(uri) ?: "mapsafe-data.pgp"
                val suggested = inputName.removeSuffix(".pgp").removeSuffix(".gpg")
                    .ifBlank { "mapsafe-decrypted-data" }
                createDecryptedOutput.launch(suggested)
            }
        }
    }
    private val createDecryptedOutput = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) clearPendingDecryption() else decryptTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapSafeUi.configureActivity(
            this,
            if (intent.getStringExtra(EXTRA_MODE) == MODE_DECRYPT) "Decrypt & access" else "Encrypt & protect"
        )
        repository = OpenPgpKeyRepository(applicationContext)
        exchangeRepository = PublicKeyExchangeRepository(applicationContext)
        loadInternalEncryptSource()
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
            controls.addView(MapSafeUi.screenHeading(
                this,
                "Decrypt & Access",
                "Decrypt a protected dataset you are authorised to access."
            ))
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Select Encrypted File"),
                MapSafeUi.text(this, "Choose an OpenPGP MapSafe package from this device.", 14f),
                MapSafeUi.outlineButton(this, "Browse protected files", ::beginDecrypt)
            ))
            statusText = MapSafeUi.text(this, "", 14f)
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Your Private Key"),
                statusText,
                MapSafeUi.outlineButton(this, "Change / manage identity") {
                    startActivity(Intent(this, MapSafeSecurityActivity::class.java))
                }
            ))
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.valueRow(this, "Encryption", "AES-256-GCM", strongValue = true),
                MapSafeUi.valueRow(this, "Protected by", "OpenPGP"),
                MapSafeUi.valueRow(this, "Integrity", "Verified before output")
            ))
            controls.addView(MapSafeUi.primaryButton(this, "🔓  Choose Package & Decrypt", ::beginDecrypt))
        } else {
            controls.addView(MapSafeUi.screenHeading(
                this,
                "Encrypt & Protect",
                "Encrypt the protected dataset before it leaves this device."
            ))
            val representation = intent.getStringExtra(EXTRA_SOURCE_REPRESENTATION)
                ?: "User-selected representation"
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Protected Dataset"),
                MapSafeUi.text(this, pendingInternalEncryptName ?: "Choose another file during encryption", 15f, MapSafeUi.TEXT, bold = true),
                MapSafeUi.text(this, "Representation: $representation", 13f, MapSafeUi.MUTED),
                MapSafeUi.divider(this),
                MapSafeUi.valueRow(this, "Encryption", "AES-256-GCM", strongValue = true)
            ))
            statusText = MapSafeUi.text(this, "", 14f)
            val setupIdentity = MapSafeUi.outlineButton(this, "Security & sharing setup") {
                startActivity(Intent(this, MapSafeSecurityActivity::class.java))
            }
            controls.addView(
                if (repository.hasLocalIdentity()) {
                    MapSafeUi.card(
                        this,
                        MapSafeUi.sectionTitle(this, "Your Encryption Identity"),
                        statusText,
                        setupIdentity
                    )
                } else {
                    MapSafeUi.card(
                        this,
                        MapSafeUi.sectionTitle(this, "Your Encryption Identity"),
                        statusText,
                        setupIdentity,
                        MapSafeUi.outlineButton(this, "Create local identity", ::confirmCreateIdentity)
                    )
                }
            )
            val selection = MapSafeSecurityPreferences.read(this)
            val groupRecords = if (selection.hasGroup) {
                exchangeRepository.records(requireNotNull(selection.serverUrl), requireNotNull(selection.groupId))
            } else emptyList()
            val accepted = groupRecords.count { it.trustState == PublicKeyTrustState.ACCEPTED }
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Recipients"),
                MapSafeUi.valueRow(this, selection.groupName ?: "Trusted recipients", "$accepted verified"),
                MapSafeUi.text(this, "Accepted group keys are selected by default. Missing or changed keys remain blocked.", 13f, MapSafeUi.MUTED),
                MapSafeUi.outlineButton(this, "+  Import individual recipient") {
                    importPublicKey.launch(arrayOf("application/pgp-keys", "application/octet-stream", "text/plain"))
                },
                MapSafeUi.outlineButton(this, "View keys & fingerprints", ::showKeys)
            ))
            controls.addView(MapSafeUi.card(
                this,
                MapSafeUi.sectionTitle(this, "Access Protection"),
                MapSafeUi.text(this, "◉  Encrypt for selected recipients", 14f, MapSafeUi.GREEN_TEXT, bold = true),
                MapSafeUi.divider(this),
                MapSafeUi.valueRow(this, "Content encryption", "AES-256-GCM"),
                MapSafeUi.valueRow(this, "Session-key protection", "OpenPGP"),
                MapSafeUi.valueRow(this, "Source data leaves device", "No", strongValue = true)
            ))
            controls.addView(MapSafeUi.primaryButton(this, "🔒  Select Recipients & Encrypt", ::beginEncrypt))
        }
        controls.addView(MapSafeUi.card(
            this,
            MapSafeUi.sectionTitle(this, "Key Backup & Recovery"),
            MapSafeUi.outlineButton(this, "Import protected key backup", ::confirmImportSecretIdentity),
            MapSafeUi.outlineButton(this, "Export my public key") { beginExport(secret = false) },
            MapSafeUi.outlineButton(this, "Export protected private-key backup") { beginExport(secret = true) }
        ))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        controls.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        return ScrollView(this).apply {
            setBackgroundColor(MapSafeUi.PAGE)
            addView(controls)
        }
    }

    private fun refreshStatus(message: String? = null) {
        val identity = runCatching { repository.localIdentityInfo() }.getOrNull()
        val recipients = runCatching {
            selectableEncryptionKeyRings().count { ring ->
                OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint) != identity?.fingerprint
            }
        }.getOrDefault(0)
        val awaitingReview = runCatching {
            exchangeRepository.records().count { it.needsUserReview }
        }.getOrDefault(0)
        statusText.text = buildString {
            message?.let { append(it).append("\n\n") }
            if (identity == null) {
                append("No local identity. Create one or import a protected backup.")
            } else {
                append("Identity: ${identity.displayName}\n")
                append("Fingerprint: ${formatFingerprint(identity.fingerprint)}")
            }
            append("\nAvailable recipient keys: $recipients")
            if (awaitingReview > 0) append("\nDirectory fingerprints awaiting review: $awaitingReview")
        }
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
        startActivity(Intent(this, MapSafeIdentityActivity::class.java))
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
        runBusy(
            message = "Importing recipient key…",
            operation = {
                contentResolver.openInputStream(uri)?.use(repository::importPublicKeys)
                    ?: throw OpenPgpException("The selected public-key file could not be opened.")
            },
            onSuccess = { imported ->
                refreshStatus("Imported ${imported.size} public key${if (imported.size == 1) "" else "s"}.")
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
            CheckBox(this).apply {
                text = when {
                    info.hasSecretKey -> "Only me - ${info.displayName}\n${formatFingerprint(info.fingerprint)}"
                    groupMemberName != null ->
                        "${selection.groupName ?: "Selected group"} / $groupMemberName\n" +
                            "${formatFingerprint(info.fingerprint)} - available offline"
                    else -> "Individual recipient - ${info.displayName}\n${formatFingerprint(info.fingerprint)}"
                }
                isChecked = info.hasSecretKey || groupMemberName != null
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
        val form = MapSafeUi.page(this).apply {
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(MapSafeUi.card(
                this@MapSafeOpenPgpActivity,
                MapSafeUi.sectionTitle(this@MapSafeOpenPgpActivity, "Verified recipients"),
                *checks.toTypedArray()
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("Select recipients")
            .setMessage(buildString {
                append("The dataset is encrypted once. Each checked recipient receives a separately encrypted session key.")
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
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (pendingInternalEncryptSource == null) "Choose file" else "Choose destination",
                null
            )
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = checks.filter { it.isChecked }.map { it.tag as String }.toSet()
                if (selected.isEmpty()) {
                    toast("Select at least one recipient.")
                    return@setOnClickListener
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
        val internalName = pendingInternalEncryptName
        if (pendingInternalEncryptSource != null && internalName != null) {
            createEncryptedOutput.launch("$internalName.pgp")
        } else {
            selectEncryptInput.launch(arrayOf("*/*"))
        }
    }

    private fun encryptTo(outputUri: Uri) {
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
                    val inputStream = internalSource?.inputStream()
                        ?: inputUri?.let(contentResolver::openInputStream)
                    inputStream?.use { input ->
                        contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                            EncryptFile.encrypt(
                                input = input,
                                output = output,
                                originalFileName = inputName,
                                recipients = recipients,
                                signingKeyRing = signingRing,
                                signingPassphrase = signingPassphrase
                            )
                        } ?: throw OpenPgpException("The encrypted output destination could not be opened.")
                    } ?: throw OpenPgpException("The source document could not be opened.")
                } catch (error: Exception) {
                    discardDocument(outputUri)
                    throw error
                } finally {
                    clearPendingEncryption(deleteInternalSource = true)
                }
            },
            onSuccess = { result ->
                val signing = if (result.signerFingerprint == null) "Unsigned" else "Signed"
                val message = "Encrypted once with ${result.contentProtection.displayName} for " +
                    "${result.recipientFingerprints.size} recipient(s). $signing package saved."
                refreshStatus(message)
                showResultPanel(
                    "✓  Dataset protected",
                    "${result.contentProtection.displayName} encryption",
                    "${result.recipientFingerprints.size} authorised recipient(s) · Ready to share"
                )
            }
        )
    }

    private fun beginDecrypt() {
        if (!repository.hasLocalIdentity()) {
            toast("Create or import the recipient's local identity first.")
            return
        }
        selectDecryptInput.launch(arrayOf("application/pgp-encrypted", "application/octet-stream", "*/*"))
    }

    private fun decryptTo(outputUri: Uri) {
        val inputUri = pendingDecryptInput ?: return clearPendingDecryption()
        val passphrase = pendingDecryptPassphrase ?: return clearPendingDecryption()
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
                        val temporaryKeySpec = SecretKeySpec(temporaryKey, "AES")
                        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.ENCRYPT_MODE, temporaryKeySpec, GCMParameterSpec(128, temporaryIv))
                        }
                        CipherOutputStream(temporary.outputStream().buffered(), encryptCipher).use { temporaryOutput ->
                            DecryptFile.decrypt(
                                input = input,
                                output = temporaryOutput,
                                secretKeyRings = listOf(secretRing),
                                passphrase = passphrase,
                                verificationKeyRings = repository.listPublicKeyRings()
                            )
                        }
                    } ?: throw OpenPgpException("The encrypted document could not be opened.")

                    contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                        val temporaryKeySpec = SecretKeySpec(temporaryKey, "AES")
                        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.DECRYPT_MODE, temporaryKeySpec, GCMParameterSpec(128, temporaryIv))
                        }
                        CipherInputStream(temporary.inputStream().buffered(), decryptCipher).use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw OpenPgpException("The decrypted output destination could not be opened.")
                    result
                } catch (error: Exception) {
                    discardDocument(outputUri)
                    throw error
                } finally {
                    temporaryKey.fill(0)
                    temporaryIv.fill(0)
                    temporary.delete()
                    clearPendingDecryption()
                }
            },
            onSuccess = { result ->
                refreshStatus(decryptionMessage(result))
                showResultPanel(
                    "✓  Decryption successful",
                    "${result.originalFileName} is ready to view",
                    "Integrity verified · ${result.signatureStatus.name.lowercase().replace('_', ' ')}"
                )
                offerGeoJsonImport(outputUri, result)
            }
        )
    }

    private fun offerGeoJsonImport(outputUri: Uri, result: OpenPgpDecryptionResult) {
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
            .setPositiveButton("Import and open") { _, _ ->
                importDecryptedGeoJson(outputUri, result.originalFileName)
            }
            .show()
    }

    private fun importDecryptedGeoJson(outputUri: Uri, originalFileName: String) {
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
            onSuccess = { imported -> openImportedLayer(imported) }
        )
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(verticalForm(input))
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

    private fun showResultPanel(title: String, subtitle: String, details: String) {
        resultPanel?.let(controls::removeView)
        resultPanel = MapSafeUi.card(
            this,
            MapSafeUi.text(this, title, 18f, MapSafeUi.GREEN_TEXT, bold = true),
            MapSafeUi.text(this, subtitle, 15f, MapSafeUi.TEXT, bold = true).apply {
                setPadding(0, dp(5), 0, 0)
            },
            MapSafeUi.text(this, details, 14f, MapSafeUi.GREEN_TEXT).apply {
                setPadding(0, dp(5), 0, 0)
            },
            pale = true
        )
        controls.addView(resultPanel, 1)
    }

    private fun <T> runBusy(message: String, operation: suspend () -> T, onSuccess: (T) -> Unit) {
        setBusy(true, message)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { operation() } }
            setBusy(false, null)
            result.onSuccess(onSuccess).onFailure { error ->
                val shown = when (error) {
                    is OpenPgpException -> error.message
                    else -> error.message ?: error.javaClass.simpleName
                }
                refreshStatus("Operation failed: $shown")
                AlertDialog.Builder(this@MapSafeOpenPgpActivity)
                    .setTitle("OpenPGP operation failed")
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
        pendingSigningPassphrase?.fill('\u0000')
        pendingSigningPassphrase = null
        pendingEncryptInput = null
        pendingEncryptRecipients = emptySet()
        if (deleteInternalSource) {
            pendingInternalEncryptSource?.let { source ->
                source.delete()
                source.parentFile?.delete()
            }
            pendingInternalEncryptSource = null
            pendingInternalEncryptName = null
        }
    }

    private fun loadInternalEncryptSource() {
        val path = intent.getStringExtra(EXTRA_INTERNAL_SOURCE_PATH) ?: return
        val source = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        val allowedRoot = runCatching { File(cacheDir, "mapsafe").canonicalFile }.getOrNull() ?: return
        val isInsideRoot = source.path.startsWith(allowedRoot.path + File.separator)
        if (!isInsideRoot || !source.isFile || source.length() <= 0L) return
        pendingInternalEncryptSource = source
        pendingInternalEncryptName = intent.getStringExtra(EXTRA_INTERNAL_SOURCE_NAME)
            ?.let { File(it).name }
            ?.takeIf { it.isNotBlank() }
            ?: source.name
    }

    private fun clearPendingDecryption() {
        pendingDecryptPassphrase?.fill('\u0000')
        pendingDecryptPassphrase = null
        pendingDecryptInput = null
    }

    private fun discardDocument(uri: Uri) {
        val deleted = runCatching {
            DocumentsContract.isDocumentUri(this, uri) &&
                DocumentsContract.deleteDocument(contentResolver, uri)
        }.getOrDefault(false)
        if (!deleted) runCatching { contentResolver.openOutputStream(uri, "wt")?.use { } }
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
        private const val EXTRA_SOURCE_REPRESENTATION = "mapsafe_openpgp_source_representation"
        private const val EXTRA_RETURN_TO_SECURITY = "mapsafe_openpgp_return_to_security"
        private const val MODE_ENCRYPT = "encrypt"
        private const val MODE_DECRYPT = "decrypt"

        fun intent(
            context: Context,
            decrypt: Boolean = false,
            sourceFile: File? = null,
            sourceDisplayName: String? = null,
            sourceRepresentation: String? = null,
            returnToSecurity: Boolean = false
        ): Intent {
            return Intent(context, MapSafeOpenPgpActivity::class.java)
                .putExtra(EXTRA_MODE, if (decrypt) MODE_DECRYPT else MODE_ENCRYPT)
                .putExtra(EXTRA_RETURN_TO_SECURITY, returnToSecurity)
                .apply {
                    if (sourceFile != null) {
                        putExtra(EXTRA_INTERNAL_SOURCE_PATH, sourceFile.absolutePath)
                        putExtra(EXTRA_INTERNAL_SOURCE_NAME, sourceDisplayName ?: sourceFile.name)
                        sourceRepresentation?.let { putExtra(EXTRA_SOURCE_REPRESENTATION, it) }
                    }
                }
        }
    }
}
