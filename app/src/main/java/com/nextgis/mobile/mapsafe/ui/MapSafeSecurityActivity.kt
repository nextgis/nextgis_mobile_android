package com.nextgis.mobile.mapsafe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.nextgis.maplibui.activity.NGWSettingsActivity
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpException
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisAccountSummary
import com.nextgis.mobile.mapsafe.keys.NextGisGroupSummary
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.keys.PublicKeySyncReport
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-facing setup for the MapSafe trust chain.
 *
 * It deliberately reuses the app's existing NextGIS accounts. Authentication
 * groups define membership; matching resource groups contain public keys only.
 */
class MapSafeSecurityActivity : AppCompatActivity() {
    private lateinit var keyRepository: OpenPgpKeyRepository
    private lateinit var exchangeRepository: PublicKeyExchangeRepository
    private lateinit var directoryClient: NextGisPublicKeyDirectoryClient

    private lateinit var accountText: TextView
    private lateinit var groupText: TextView
    private lateinit var identityText: TextView
    private lateinit var directoryText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: LinearLayout
    private lateinit var chooseGroupButton: Button
    private lateinit var createGroupButton: Button
    private lateinit var publishButton: Button
    private lateinit var syncButton: Button
    private lateinit var reviewButton: Button

    private var selectedAccount: NextGisAccountSummary? = null
    private var selectedGroup: NextGisGroupSummary? = null
    private var lastSyncReport: PublicKeySyncReport? = null

    private val exportPublicKey = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pgp-keys")
    ) { uri ->
        if (uri != null) {
            runBusy(
                message = "Exporting public key...",
                operation = {
                    contentResolver.openOutputStream(uri, "wt")?.use(keyRepository::exportLocalPublicKey)
                        ?: throw OpenPgpException("The selected destination could not be opened.")
                    "Public key exported."
                },
                onSuccess =(::toast)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapSafeUi.configureActivity(this, "Security & sharing")
        keyRepository = OpenPgpKeyRepository(applicationContext)
        exchangeRepository = PublicKeyExchangeRepository(applicationContext)
        directoryClient = NextGisPublicKeyDirectoryClient(
            applicationContext,
            keyRepository,
            exchangeRepository
        )
        setContentView(MapSafeUi.activityFrame(this, buildContent(), onBack =(::finish)))
        restoreSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::accountText.isInitialized) restoreSelection()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildContent(): View {
        controls = MapSafeUi.page(this)
        controls.addView(MapSafeUi.screenHeading(
            this,
            "Security & Sharing",
            "Set up the people who may decrypt protected datasets. Public keys may be shared; private keys and passphrases stay on this device."
        ))

        accountText = bodyText()
        controls.addView(card("1. NextGIS account", accountText,
            actionButton("Choose connected account", ::chooseAccount),
            actionButton("Sign in / manage accounts") {
                startActivity(Intent(this, NGWSettingsActivity::class.java))
            }
        ))

        groupText = bodyText()
        chooseGroupButton = actionButton("Choose one of my groups", ::loadGroups)
        createGroupButton = actionButton("Create MapSafe group", ::showCreateGroupDialog)
        controls.addView(card("2. Trusted group", groupText, chooseGroupButton, createGroupButton))

        identityText = bodyText()
        controls.addView(card("3. Encryption identity", identityText,
            actionButton("Create / replace identity", ::confirmCreateIdentity),
            actionButton("Export public key") {
                val info = keyRepository.localIdentityInfo()
                if (info == null) toast("Create an encryption identity first.")
                else exportPublicKey.launch("mapsafe-public-${info.fingerprint.takeLast(16)}.asc")
            },
            actionButton("Copy fingerprint") { copyFingerprint() },
            actionButton("Advanced key backup / import") {
                startActivity(MapSafeOpenPgpActivity.intent(this, returnToSecurity = true))
            }
        ))

        directoryText = bodyText()
        publishButton = actionButton("Publish my public key", ::publishPublicKey)
        syncButton = actionButton("Download group member keys", ::synchroniseGroupKeys)
        reviewButton = actionButton("Review changed / new fingerprints", ::reviewFingerprints)
        controls.addView(card("4. Group public keys", directoryText, publishButton, syncButton, reviewButton))

        controls.addView(TextView(this).apply {
            text = "Encryption becomes available offline after recipient fingerprints are checked and accepted. A changed key is quarantined; it is never silently substituted."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(10), dp(4), dp(8))
        })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        controls.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        return ScrollView(this).apply { addView(controls) }
    }

    private fun restoreSelection() {
        val accounts = runCatching { directoryClient.accountSummaries() }.getOrDefault(emptyList())
        val saved = MapSafeSecurityPreferences.read(this)
        selectedAccount = accounts.firstOrNull { it.accountName == saved.accountName }
            ?: accounts.singleOrNull()
        selectedAccount?.let { account ->
            if (saved.accountName != account.accountName) {
                MapSafeSecurityPreferences.selectAccount(this, account)
            }
        }
        selectedGroup = if (saved.hasGroup && selectedAccount?.accountName == saved.accountName) {
            NextGisGroupSummary(
                id = requireNotNull(saved.groupId),
                displayName = saved.groupName ?: "Group ${saved.groupId}",
                keyname = "",
                memberIds = emptySet(),
                memberNames = emptyMap(),
                currentUserId = saved.currentUserId ?: -1L
            )
        } else {
            null
        }
        updateUi()
    }

    private fun updateUi() {
        val account = selectedAccount
        accountText.text = if (account == null) {
            "Not connected. Add or choose a NextGIS Web account. MapSafe will reuse that authenticated account."
        } else {
            "Connected: ${account.accountName}\n${account.serverUrl}\nLogin: ${account.login}"
        }

        val group = selectedGroup
        groupText.text = if (group == null) {
            "No group selected. A NextGIS authentication group defines who belongs to the community."
        } else {
            buildString {
                append(group.displayName).append("\nNextGIS group ID: ").append(group.id)
                if (group.memberIds.isNotEmpty()) append("\nMembers: ").append(group.memberIds.size)
            }
        }

        val identity = runCatching { keyRepository.localIdentityInfo() }.getOrNull()
        identityText.text = if (identity == null) {
            "No identity on this device. Create one before publishing or decrypting."
        } else {
            "${identity.displayName}\nFingerprint:\n${formatFingerprint(identity.fingerprint)}\nPrivate key: protected on this device"
        }

        val records = if (account != null && group != null) {
            runCatching { exchangeRepository.records(account.serverUrl, group.id) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        directoryText.text = directorySummary(group, records, lastSyncReport)

        chooseGroupButton.isEnabled = account != null
        createGroupButton.isEnabled = account != null
        publishButton.isEnabled = account != null && group != null && identity != null
        syncButton.isEnabled = account != null && group != null
        reviewButton.isEnabled = records.any { it.needsUserReview }
    }

    private fun chooseAccount() {
        val accounts = runCatching { directoryClient.accountSummaries() }.getOrElse { error ->
            toast(error.message ?: "Connected accounts could not be read.")
            return
        }
        if (accounts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No NextGIS account")
                .setMessage("Add a NextGIS Web account, then return here. MapSafe does not create a second login store.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Manage accounts") { _, _ ->
                    startActivity(Intent(this, NGWSettingsActivity::class.java))
                }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose NextGIS account")
            .setItems(accounts.map { "${it.accountName}\n${it.serverUrl}" }.toTypedArray()) { _, index ->
                selectedAccount = accounts[index]
                selectedGroup = null
                lastSyncReport = null
                MapSafeSecurityPreferences.selectAccount(this, accounts[index])
                updateUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadGroups() {
        val account = selectedAccount ?: return toast("Choose a NextGIS account first.")
        runBusy(
            message = "Loading your NextGIS groups...",
            operation = { directoryClient.membershipGroups(account.accountName) },
            onSuccess = onSuccess@{ groups ->
            if (groups.isEmpty()) {
                toast("This account does not belong to any available NextGIS groups.")
                return@onSuccess
            }
            AlertDialog.Builder(this)
                .setTitle("Choose trusted group")
                .setItems(groups.map { group ->
                    "${group.displayName}\n${group.memberIds.size} member${if (group.memberIds.size == 1) "" else "s"}"
                }.toTypedArray()) { _, index ->
                    selectedGroup = groups[index]
                    lastSyncReport = null
                    MapSafeSecurityPreferences.selectGroup(this, account, groups[index])
                    updateUi()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            }
        )
    }

    private fun showCreateGroupDialog() {
        val account = selectedAccount ?: return toast("Choose a NextGIS account first.")
        val name = inputField("Group name")
        val description = inputField("Description (optional)").apply {
            isSingleLine = false
            minLines = 2
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Create MapSafe group")
            .setMessage("This creates a real NextGIS authentication group. Your server permissions determine whether creation is allowed.")
            .setView(verticalForm(name, description))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val displayName = name.text.toString().trim()
                if (displayName.isBlank()) return@setOnClickListener toast("Enter a group name.")
                dialog.dismiss()
                runBusy(
                    message = "Creating $displayName...",
                    operation = {
                        directoryClient.createGroup(account.accountName, displayName, description.text.toString())
                    },
                    onSuccess = { group ->
                        selectedGroup = group
                        MapSafeSecurityPreferences.selectGroup(this, account, group)
                        updateUi()
                        toast("Created ${group.displayName} with you as the first member.")
                    }
                )
            }
        }
        dialog.show()
    }

    private fun confirmCreateIdentity() {
        if (!keyRepository.hasLocalIdentity()) {
            showCreateIdentityDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Replace encryption identity?")
            .setMessage("Old packages may become impossible to decrypt on this device. Export a protected secret-key backup before replacement.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Continue") { _, _ -> showCreateIdentityDialog() }
            .show()
    }

    private fun showCreateIdentityDialog() {
        startActivity(Intent(this, MapSafeIdentityActivity::class.java))
    }

    private fun publishPublicKey() {
        val account = selectedAccount ?: return toast("Choose a NextGIS account first.")
        val group = selectedGroup ?: return toast("Choose a trusted group first.")
        if (!keyRepository.hasLocalIdentity()) return toast("Create an encryption identity first.")
        runBusy(
            message = "Publishing public key to ${group.displayName}...",
            operation = { directoryClient.publish(account.accountName, group.id) },
            onSuccess = { result ->
                toast("Public key published. Version ${result.keyVersion}; fingerprint ${formatFingerprint(result.fingerprint)}")
                synchroniseGroupKeys()
            }
        )
    }

    private fun synchroniseGroupKeys() {
        val account = selectedAccount ?: return toast("Choose a NextGIS account first.")
        val group = selectedGroup ?: return toast("Choose a trusted group first.")
        runBusy(
            message = "Retrieving member public keys for ${group.displayName}...",
            operation = { directoryClient.sync(account.accountName, group.id) },
            onSuccess = { report ->
                lastSyncReport = report
                selectedGroup = group.copy(
                    memberIds = report.memberNames.keys,
                    memberNames = report.memberNames
                )
                updateUi()
                if (report.records.any { it.needsUserReview }) reviewFingerprints()
                else toast("Group keys synchronised. ${report.acceptedCount} accepted; ${report.missingMemberIds.size} missing.")
            }
        )
    }

    private fun reviewFingerprints() {
        val account = selectedAccount ?: return
        val group = selectedGroup ?: return
        val pending = exchangeRepository.records(account.serverUrl, group.id).filter { it.needsUserReview }
        if (pending.isEmpty()) return toast("No new or changed fingerprints require review.")
        val selected = BooleanArray(pending.size)
        val labels = pending.map { record ->
            buildString {
                append(record.displayName).append('\n').append(formatFingerprint(record.observedFingerprint))
                if (record.trustState == PublicKeyTrustState.CHANGE_PENDING) {
                    append("\nCHANGED from ")
                    append(record.acceptedFingerprint?.let(::formatFingerprint) ?: "unknown")
                }
            }
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Confirm public-key fingerprints")
            .setMessage("NextGIS confirms group membership, not key authenticity. Compare each full fingerprint through an independent trusted channel before accepting it.")
            .setMultiChoiceItems(labels, selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Accept checked", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = pending.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) return@setOnClickListener toast("Select only fingerprints you independently verified.")
                dialog.dismiss()
                runBusy(
                    message = "Saving trusted recipient keys...",
                    operation = {
                        chosen.forEach {
                            exchangeRepository.accept(it.recordId, keyRepository, System.currentTimeMillis())
                        }
                        chosen.size
                    },
                    onSuccess = { count ->
                        updateUi()
                        toast("Accepted $count fingerprint${if (count == 1) "" else "s"}. These recipients are now available offline.")
                    }
                )
            }
        }
        dialog.show()
    }

    private fun copyFingerprint() {
        val fingerprint = keyRepository.localIdentityInfo()?.fingerprint
            ?: return toast("Create an encryption identity first.")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MapSafe OpenPGP fingerprint", fingerprint))
        toast("Fingerprint copied.")
    }

    private fun directorySummary(
        group: NextGisGroupSummary?,
        records: List<com.nextgis.mobile.mapsafe.keys.CachedPublicKeyRecord>,
        report: PublicKeySyncReport?
    ): String {
        if (group == null) return "Choose a group before publishing or retrieving public keys."
        if (records.isEmpty() && report == null) {
            return "No cached keys for ${group.displayName}. Publish your key, then download group member keys."
        }
        return buildString {
            append(group.displayName).append('\n')
            if (records.isEmpty()) append("No published member keys found.")
            records.sortedBy { it.displayName }.forEach { record ->
                append("\n\n").append(record.displayName).append('\n')
                append(formatFingerprint(record.observedFingerprint)).append('\n')
                append(
                    when (record.trustState) {
                        PublicKeyTrustState.ACCEPTED -> "Available offline"
                        PublicKeyTrustState.DISCOVERED -> "Fingerprint check required"
                        PublicKeyTrustState.CHANGE_PENDING -> "Key changed - blocked pending review"
                        PublicKeyTrustState.SUPERSEDED -> "Superseded - blocked for new encryption"
                        PublicKeyTrustState.REVOKED -> "Revoked - blocked"
                        PublicKeyTrustState.MEMBER_REMOVED -> "No longer a current member"
                    }
                )
            }
            report?.missingMemberIds?.takeIf { it.isNotEmpty() }?.let { missing ->
                append("\n\nMissing public keys: ").append(missing.size)
                missing.sorted().forEach { id ->
                    append("\n- ").append(report.memberNames[id] ?: "Member $id")
                }
            }
            report?.invalidEntries?.takeIf { it.isNotEmpty() }?.let { invalid ->
                append("\n\nRejected invalid entries: ").append(invalid.size)
            }
        }
    }

    private fun <T> runBusy(message: String, operation: suspend () -> T, onSuccess: (T) -> Unit = {}) {
        setBusy(true)
        toast(message)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { operation() } }
            setBusy(false)
            result.onSuccess(onSuccess).onFailure { error ->
                val shown = error.message ?: error.javaClass.simpleName
                AlertDialog.Builder(this@MapSafeSecurityActivity)
                    .setTitle("MapSafe setup failed")
                    .setMessage(shown)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        for (index in 0 until controls.childCount) controls.getChildAt(index).isEnabled = !busy
        if (!busy) updateUi()
    }

    private fun card(title: String, status: TextView, vararg actions: Button): LinearLayout {
        return MapSafeUi.card(
            this,
            MapSafeUi.text(this, title, 18f, MapSafeUi.GREEN_TEXT, bold = true),
            status,
            *actions
        )
    }

    private fun bodyText(): TextView = TextView(this).apply {
        textSize = 14f
        setTextColor(MapSafeUi.TEXT)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        MapSafeUi.outlineButton(this, label, action)

    private fun inputField(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
    }

    private fun verticalForm(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), 0)
        views.forEach(::addView)
    }

    private fun formatFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
