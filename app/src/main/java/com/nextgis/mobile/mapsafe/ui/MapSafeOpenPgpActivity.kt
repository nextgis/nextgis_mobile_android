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
import android.widget.Button
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
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
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
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: LinearLayout
    private lateinit var backButton: Button

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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        repository = OpenPgpKeyRepository(applicationContext)
        loadInternalEncryptSource()
        setContentView(buildContent())
        refreshStatus()
        if (pendingInternalEncryptSource != null) {
            controls.post { beginEncrypt() }
        }
    }

    override fun onDestroy() {
        clearPendingEncryption(deleteInternalSource = true)
        clearPendingDecryption()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val padding = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = Button(this).apply {
            text = "Back"
            isAllCaps = false
            setOnClickListener { returnToParentMenu() }
        }
        header.addView(backButton)
        header.addView(TextView(this).apply {
            text = if (intent.getStringExtra(EXTRA_MODE) == MODE_DECRYPT) {
                "MapSafe Decryption"
            } else {
                "MapSafe Encryption"
            }
            textSize = 24f
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(header)
        statusText = TextView(this).apply {
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusText)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val preferredDecrypt = intent.getStringExtra(EXTRA_MODE) == MODE_DECRYPT
        if (preferredDecrypt) {
            controls.addAction("Decrypt OpenPGP file", ::beginDecrypt)
            controls.addAction("Encrypt file for recipients", ::beginEncrypt)
        } else {
            controls.addAction("Encrypt file for recipients", ::beginEncrypt)
            controls.addAction("Decrypt OpenPGP file", ::beginDecrypt)
        }
        controls.addSection("Identity")
        controls.addAction("Create local identity", ::confirmCreateIdentity)
        controls.addAction("Import secret-key backup", ::confirmImportSecretIdentity)
        controls.addAction("Export my public key") { beginExport(secret = false) }
        controls.addAction("Export protected secret-key backup") { beginExport(secret = true) }
        controls.addSection("Recipients")
        controls.addAction("Import recipient public key") {
            importPublicKey.launch(arrayOf("application/pgp-keys", "application/octet-stream", "text/plain"))
        }
        controls.addAction("View keys and fingerprints", ::showKeys)
        root.addView(controls)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshStatus(message: String? = null) {
        val identity = runCatching { repository.localIdentityInfo() }.getOrNull()
        val recipients = runCatching { repository.listKeyInfo().count { !it.hasSecretKey } }.getOrDefault(0)
        statusText.text = buildString {
            message?.let { append(it).append("\n\n") }
            if (identity == null) {
                append("No local identity. Create one or import a protected backup.")
            } else {
                append("Identity: ${identity.displayName}\n")
                append("Fingerprint: ${formatFingerprint(identity.fingerprint)}")
            }
            append("\nRecipient keys: $recipients")
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
        val name = inputField("Name")
        val email = inputField("Email (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val passphrase = passwordField("Recovery passphrase")
        val confirm = passwordField("Confirm passphrase")
        val form = verticalForm(name, email, passphrase, confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Create OpenPGP identity")
            .setMessage("Use a strong passphrase and export a protected backup after creation.")
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val displayName = name.text.toString().trim()
                val emailAddress = email.text.toString().trim()
                val secret = passphrase.text.toString().toCharArray()
                val repeated = confirm.text.toString().toCharArray()
                var handedToGenerator = false
                when {
                    displayName.isBlank() -> toast("Enter a name for the identity.")
                    secret.size < 12 -> toast("Use a recovery passphrase of at least 12 characters.")
                    !secret.contentEquals(repeated) -> toast("The passphrases do not match.")
                    else -> {
                        dialog.dismiss()
                        repeated.fill('\u0000')
                        passphrase.text?.clear()
                        confirm.text?.clear()
                        val userId = if (emailAddress.isBlank()) displayName else "$displayName <$emailAddress>"
                        handedToGenerator = true
                        runBusy(
                            message = "Generating a 3072-bit OpenPGP identity…",
                            operation = {
                                try {
                                    val material = OpenPgpKeyGenerator.generate(userId, secret)
                                    repository.saveLocalIdentity(material)
                                    material.info
                                } finally {
                                    secret.fill('\u0000')
                                }
                            },
                            onSuccess = { info ->
                                refreshStatus("Identity created: ${info.displayName}")
                            }
                        )
                    }
                }
                if (!handedToGenerator) secret.fill('\u0000')
                if (repeated.isNotEmpty()) repeated.fill('\u0000')
            }
        }
        dialog.show()
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
        val rings = repository.listPublicKeyRings().filter { OpenPgpKeyCodec.findEncryptionKey(it) != null }
        if (rings.isEmpty()) {
            toast("Create an identity or import at least one recipient public key first.")
            return
        }

        val checks = rings.map { ring ->
            val info = OpenPgpKeyCodec.keyInfo(ring, repository.localIdentityInfo()?.fingerprint ==
                OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint))
            CheckBox(this).apply {
                text = "${info.displayName}\n${formatFingerprint(info.fingerprint)}"
                isChecked = info.hasSecretKey
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
        val form = verticalForm(*checks.toTypedArray(), sign)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Select recipients")
            .setMessage("The file is encrypted once. Each selected recipient receives an encrypted session key.")
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
                    val ringsByFingerprint = repository.listPublicKeyRings().associateBy {
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
                refreshStatus("Encrypted for ${result.recipientFingerprints.size} recipient(s). $signing package saved.")
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
        if (::backButton.isInitialized && !backButton.isEnabled) return
        returnToParentMenu()
    }

    private fun returnToParentMenu() {
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
        return "Decrypted ${result.originalFileName}. Integrity check passed. $signature"
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
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("OpenPGP keys")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun promptForPassphrase(title: String, onPassphrase: (CharArray) -> Unit) {
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
        dialog.show()
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
        backButton.isEnabled = !busy
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

    private fun LinearLayout.addAction(label: String, action: () -> Unit) {
        addView(Button(this@MapSafeOpenPgpActivity).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun LinearLayout.addSection(label: String) {
        addView(TextView(this@MapSafeOpenPgpActivity).apply {
            text = label
            textSize = 18f
            setPadding(0, dp(18), 0, dp(4))
        })
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
        private const val MODE_ENCRYPT = "encrypt"
        private const val MODE_DECRYPT = "decrypt"

        fun intent(
            context: Context,
            decrypt: Boolean = false,
            sourceFile: File? = null,
            sourceDisplayName: String? = null
        ): Intent {
            return Intent(context, MapSafeOpenPgpActivity::class.java)
                .putExtra(EXTRA_MODE, if (decrypt) MODE_DECRYPT else MODE_ENCRYPT)
                .apply {
                    if (sourceFile != null) {
                        putExtra(EXTRA_INTERNAL_SOURCE_PATH, sourceFile.absolutePath)
                        putExtra(EXTRA_INTERNAL_SOURCE_NAME, sourceDisplayName ?: sourceFile.name)
                    }
                }
        }
    }
}
