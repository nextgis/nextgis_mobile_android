package com.nextgis.mobile.mapsafe.ui

import android.os.Bundle
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyInfo
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full MapSafe identity journey matching the safeguard screen visual language. */
class MapSafeIdentityActivity : AppCompatActivity() {
    private lateinit var repository: OpenPgpKeyRepository
    private lateinit var name: EditText
    private lateinit var organisation: EditText
    private lateinit var email: EditText
    private lateinit var passphrase: EditText
    private lateinit var confirmation: EditText
    private lateinit var progress: ProgressBar

    private val exportPublicKey = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pgp-keys")
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use(repository::exportLocalPublicKey)
                ?: throw OpenPgpException("The selected destination could not be opened.")
        }.onSuccess { toast("Public key exported. It is safe to share.") }
            .onFailure(::showError)
    }

    private val exportSecretKey = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pgp-keys")
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use(repository::exportLocalSecretKey)
                ?: throw OpenPgpException("The selected destination could not be opened.")
        }.onSuccess { toast("Protected private-key backup exported.") }
            .onFailure(::showError)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OpenPgpKeyRepository(applicationContext)
        MapSafeUi.configureActivity(this, "Encryption identity")
        showCreationScreen()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showCreationScreen() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val page = MapSafeUi.page(this)
        page.addView(MapSafeUi.screenHeading(
            this,
            "Create Encryption Identity",
            "Create your OpenPGP key pair to encrypt and decrypt protected datasets."
        ))

        name = input("Name")
        organisation = input("Organisation / community")
        email = input("Email (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.sectionTitle(this, "Name / Organisation"),
            name,
            organisation,
            email
        ))

        passphrase = password("Passphrase")
        confirmation = password("Confirm passphrase")
        val show = CheckBox(this).apply {
            text = "Show passphrase"
            setTextColor(MapSafeUi.TEXT)
        }
        fun updateSecureEntry() {
            if (passphrase.hasFocus() || confirmation.hasFocus() || show.isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        passphrase.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> updateSecureEntry() }
        confirmation.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> updateSecureEntry() }
        show.setOnCheckedChangeListener { _, checked ->
            passphrase.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
            confirmation.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
            passphrase.setSelection(passphrase.text.length)
            confirmation.setSelection(confirmation.text.length)
            updateSecureEntry()
        }
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.sectionTitle(this, "Protect your private key"),
            passphrase,
            MapSafeUi.text(this, "Use a strong passphrase of at least 12 characters.", 13f, MapSafeUi.MUTED),
            confirmation,
            show
        ))
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.text(this, "🔑  Your keys stay on this device", 15f, MapSafeUi.GREEN_TEXT, bold = true),
            MapSafeUi.text(
                this,
                "Your private key is protected by your passphrase and Android Keystore. Only your public key is shared.",
                14f
            ).apply { setPadding(0, MapSafeUi.dp(this@MapSafeIdentityActivity, 5), 0, 0) },
            pale = true
        ))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        page.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        page.addView(MapSafeUi.primaryButton(this, "🔑  Generate Key Pair", ::generateIdentity))
        page.addView(MapSafeUi.stepStrip(this, 0, "Generate", "Protect", "Share"))
        setContentView(MapSafeUi.activityFrame(
            this,
            ScrollView(this).apply { addView(page) },
            onBack =(::finish)
        ))
    }

    private fun generateIdentity() {
        val displayName = name.text.toString().trim()
        val organisationName = organisation.text.toString().trim()
        val emailAddress = email.text.toString().trim()
        val secret = passphrase.text.toString().toCharArray()
        val repeated = confirmation.text.toString().toCharArray()
        when {
            displayName.isBlank() -> toast("Enter your name.")
            organisationName.isBlank() -> toast("Enter your organisation or community.")
            secret.size < 12 -> toast("Use a passphrase of at least 12 characters.")
            !secret.contentEquals(repeated) -> toast("The passphrases do not match.")
            else -> {
                progress.visibility = View.VISIBLE
                val userLabel = "$displayName / $organisationName"
                val userId = if (emailAddress.isBlank()) userLabel else "$userLabel <$emailAddress>"
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            try {
                                OpenPgpKeyGenerator.generate(userId, secret)
                                    .also(repository::saveLocalIdentity)
                                    .info
                            } finally {
                                secret.fill('\u0000')
                            }
                        }
                    }
                    repeated.fill('\u0000')
                    progress.visibility = View.GONE
                    result.onSuccess(::showSuccessScreen).onFailure(::showError)
                }
                return
            }
        }
        secret.fill('\u0000')
        repeated.fill('\u0000')
    }

    private fun showSuccessScreen(info: OpenPgpKeyInfo) {
        passphrase.text?.clear()
        confirmation.text?.clear()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val page = MapSafeUi.page(this)
        page.addView(MapSafeUi.screenHeading(
            this,
            "Key Pair Created Successfully",
            "Your OpenPGP key pair has been created and is ready to use."
        ))
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.text(this, "✓  Key pair is ready", 17f, MapSafeUi.GREEN_TEXT, bold = true),
            MapSafeUi.text(this, "Your keys are stored securely on this device.", 14f).apply {
                setPadding(0, MapSafeUi.dp(this@MapSafeIdentityActivity, 5), 0, 0)
            },
            pale = true
        ))
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.sectionTitle(this, "Your Identity"),
            MapSafeUi.text(this, info.displayName, 16f, MapSafeUi.TEXT, bold = true),
            MapSafeUi.text(this, "Key ID: 0x${info.fingerprint.takeLast(16)}", 13f, MapSafeUi.MUTED),
            MapSafeUi.divider(this),
            MapSafeUi.text(this, formatFingerprint(info.fingerprint), 13f, MapSafeUi.TEXT)
        ))
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.text(this, "🔑  Public Key (share with others)", 15f, MapSafeUi.GREEN_TEXT, bold = true),
            MapSafeUi.text(this, "Safe to publish to your trusted NextGIS group.", 14f),
            MapSafeUi.outlineButton(this, "Export / Share") {
                exportPublicKey.launch("mapsafe-public-${info.fingerprint.takeLast(16)}.asc")
            }
        ))
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.text(this, "🔒  Private Key (kept on this device)", 15f, MapSafeUi.TEXT, bold = true),
            MapSafeUi.text(this, "Passphrase protected and wrapped by Android Keystore.", 14f),
            MapSafeUi.outlineButton(this, "Protected Backup") {
                exportSecretKey.launch("mapsafe-private-backup-${info.fingerprint.takeLast(16)}.asc")
            }
        ))
        page.addView(MapSafeUi.card(
            this,
            MapSafeUi.sectionTitle(this, "Next steps"),
            MapSafeUi.text(this, "1. Publish your public key to your trusted group", 14f),
            MapSafeUi.text(this, "2. Download and verify recipient fingerprints", 14f),
            MapSafeUi.text(this, "3. Encrypt your protected dataset", 14f)
        ))
        page.addView(MapSafeUi.primaryButton(this, "✓  Done") { finish() })
        setContentView(MapSafeUi.activityFrame(
            this,
            ScrollView(this).apply { addView(page) },
            onBack =(::finish)
        ))
    }

    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        textSize = 15f
        setTextColor(MapSafeUi.TEXT)
    }

    private fun password(hint: String): EditText = input(
        hint,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    ).apply { transformationMethod = PasswordTransformationMethod.getInstance() }

    private fun formatFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun showError(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("Identity setup failed")
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
