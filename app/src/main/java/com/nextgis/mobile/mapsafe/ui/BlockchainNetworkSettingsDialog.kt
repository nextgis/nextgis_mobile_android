package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nextgis.mobile.mapsafe.blockchain.BlockchainCheckState
import com.nextgis.mobile.mapsafe.blockchain.BlockchainCheckStep
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkCheckReport
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkEnvironment
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfile
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfileRepository
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfileValidator
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfiles
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkPresets
import com.nextgis.mobile.mapsafe.blockchain.BlockchainProfileValidation
import com.nextgis.mobile.mapsafe.blockchain.EthereumNetworkPreflightChecker
import com.nextgis.mobile.mapsafe.blockchain.MapSafeIntegrityRecordCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edits encrypted EVM network metadata without accepting wallet credentials. */
class BlockchainNetworkSettingsDialog : DialogFragment() {
    private lateinit var repository: BlockchainNetworkProfileRepository
    private lateinit var configuration: BlockchainNetworkProfiles
    private lateinit var profileSpinner: Spinner
    private lateinit var environmentSpinner: Spinner
    private lateinit var nameInput: EditText
    private lateinit var chainIdInput: EditText
    private lateinit var rpcInput: EditText
    private lateinit var explorerInput: EditText
    private lateinit var contractInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectionCheckButton: Button
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionStatusText: TextView
    private val preflightChecker = EthereumNetworkPreflightChecker()
    private var connectionCheckJob: Job? = null
    private var connectionCheckGeneration = 0
    private var selectedProfileIndex = 0
    private var loadingSelection = false
    private var loadError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = BlockchainNetworkProfileRepository(requireContext())
        configuration = try {
            repository.load()
        } catch (error: Exception) {
            loadError = error.message
            BlockchainNetworkPresets.defaults()
        }
        selectedProfileIndex = configuration.profiles.indexOfFirst {
            it.id == configuration.activeProfileId
        }.coerceAtLeast(0)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

        profileSpinner = Spinner(context).apply {
            contentDescription = "Blockchain network profile"
            adapter = spinnerAdapter(profileLabels())
            setSelection(selectedProfileIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    if (loadingSelection || position == selectedProfileIndex) return
                    selectedProfileIndex = position
                    populate(configuration.profiles[position])
                    invalidateConnectionCheck()
                    showStatus(
                        "Review the selected profile, then save it to make it active.",
                        MapSafeUi.MUTED
                    )
                }
            }
        }
        nameInput = editor("Network name", InputType.TYPE_CLASS_TEXT)
        chainIdInput = editor(
            "Chain ID",
            InputType.TYPE_CLASS_NUMBER
        )
        rpcInput = editor(
            "HTTPS RPC endpoint",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        explorerInput = editor(
            "Explorer base URL",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        contractInput = editor(
            "0x contract address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ).apply { typeface = android.graphics.Typeface.MONOSPACE }
        environmentSpinner = Spinner(context).apply {
            contentDescription = "Network environment"
            adapter = spinnerAdapter(
                BlockchainNetworkEnvironment.values().map { it.displayName }
            )
        }
        statusText = MapSafeUi.text(context, "", 13f, MapSafeUi.MUTED)
        connectionCheckButton = MapSafeUi.outlineButton(
            context,
            "Test RPC and contract",
            ::testConnection
        )
        connectionProgress = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
            contentDescription = "Testing blockchain network connection"
        }
        connectionStatusText = MapSafeUi.text(
            context,
            CONNECTION_NOT_TESTED,
            13f,
            MapSafeUi.MUTED
        )
        populate(configuration.profiles[selectedProfileIndex])
        observeProfileChanges()
        loadError?.let {
            showStatus("Saved settings could not be loaded: $it", ERROR_TEXT)
        }

        val page = MapSafeUi.page(context).apply {
            setPadding(dp(16), dp(10), dp(16), dp(24))
            addView(MapSafeUi.screenHeading(
                context,
                "Blockchain Network Settings",
                "Choose where MapSafe will verify records and, in a later release, prepare notarisation transactions."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Network Profile"),
                labelled("Profile", profileSpinner),
                MapSafeUi.text(
                    context,
                    "Sepolia is intended for testing. Mainnet and custom EVM profiles can use real funds.",
                    13f,
                    MapSafeUi.MUTED
                )
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Network Details"),
                labelled("Network name", nameInput),
                labelled("Environment", environmentSpinner),
                labelled("Chain ID", chainIdInput),
                labelled("RPC endpoint", rpcInput),
                labelled("Explorer base URL", explorerInput)
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "MapSafe Contract"),
                labelled("Contract address", contractInput),
                MapSafeUi.valueRow(
                    context,
                    "Interface",
                    configuration.profiles[selectedProfileIndex].contractInterface.displayName
                ),
                MapSafeUi.divider(context),
                MapSafeUi.text(context, "Public record format", 13f, MapSafeUi.MUTED, bold = true),
                MapSafeUi.text(
                    context,
                    MapSafeIntegrityRecordCodec.CANONICAL_PREFIX + "<64 lowercase hex>",
                    13f,
                    MapSafeUi.TEXT,
                    bold = true
                ).apply { typeface = android.graphics.Typeface.MONOSPACE },
                MapSafeUi.text(
                    context,
                    "Only the encrypted package hash is included; the file name remains private.",
                    13f,
                    MapSafeUi.MUTED
                )
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Read-only Connection Check"),
                MapSafeUi.text(
                    context,
                    "Tests the values currently shown by reading the RPC chain ID and deployed code, detecting the expected MapSafe selectors, and querying ERC-721 support. It does not save settings, connect a wallet, or submit a transaction.",
                    13f,
                    MapSafeUi.MUTED
                ),
                connectionCheckButton,
                connectionProgress,
                MapSafeUi.divider(context),
                connectionStatusText
            ))
            addView(MapSafeUi.infoCard(
                context,
                "Local protection",
                "These network settings are encrypted with Android Keystore and kept in app-private storage. MapSafe does not ask for or store a wallet recovery phrase or private key."
            ))
            addView(statusText)
            addView(MapSafeUi.primaryButton(context, "Save and use this network", ::validateAndSave))
            addView(MapSafeUi.outlineButton(context, "Cancel") { dismiss() })
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapSafeUi.PAGE)
            addView(MapSafeUi.appBar(context) { dismiss() })
            addView(
                ScrollView(context).apply { addView(page) },
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
        }
        dialog.setContentView(root)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(MapSafeUi.PAGE))
            setDimAmount(0f)
        }
    }

    override fun onDestroy() {
        connectionCheckJob?.cancel()
        super.onDestroy()
    }

    private fun populate(profile: BlockchainNetworkProfile) {
        loadingSelection = true
        nameInput.setText(profile.displayName)
        chainIdInput.setText(profile.chainId.takeIf { it > 0L }?.toString().orEmpty())
        rpcInput.setText(profile.rpcUrl)
        explorerInput.setText(profile.explorerBaseUrl)
        contractInput.setText(profile.contractAddress)
        environmentSpinner.setSelection(profile.environment.ordinal)
        loadingSelection = false
    }

    private fun validateAndSave() {
        val candidate = profileFromInputs()
        when (val validation = BlockchainNetworkProfileValidator.validate(candidate)) {
            is BlockchainProfileValidation.Invalid -> showStatus(
                validation.errors.joinToString(separator = "\n") { "- $it" },
                ERROR_TEXT
            )

            is BlockchainProfileValidation.Valid -> {
                if (validation.profile.isProduction) {
                    confirmProductionSave(validation.profile)
                } else {
                    save(validation.profile)
                }
            }
        }
    }

    private fun profileFromInputs(): BlockchainNetworkProfile {
        val current = configuration.profiles[selectedProfileIndex]
        return current.copy(
            displayName = nameInput.text?.toString().orEmpty(),
            environment = BlockchainNetworkEnvironment.values()[environmentSpinner.selectedItemPosition],
            chainId = chainIdInput.text?.toString()?.trim()?.toLongOrNull() ?: 0L,
            rpcUrl = rpcInput.text?.toString().orEmpty(),
            explorerBaseUrl = explorerInput.text?.toString().orEmpty(),
            contractAddress = contractInput.text?.toString().orEmpty()
        )
    }

    private fun testConnection() {
        val profile = when (val validation = BlockchainNetworkProfileValidator.validate(profileFromInputs())) {
            is BlockchainProfileValidation.Valid -> validation.profile
            is BlockchainProfileValidation.Invalid -> {
                showConnectionStatus(
                    "The connection check cannot start:\n" +
                        validation.errors.joinToString(separator = "\n") { "- $it" },
                    ERROR_TEXT
                )
                return
            }
        }

        connectionCheckJob?.cancel()
        val generation = ++connectionCheckGeneration
        connectionCheckButton.isEnabled = false
        connectionProgress.visibility = View.VISIBLE
        showConnectionStatus(
            "Checking ${profile.displayName}. No wallet or transaction is involved...",
            MapSafeUi.MUTED
        )
        connectionCheckJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { preflightChecker.check(profile) }
            }
            if (!isAdded || generation != connectionCheckGeneration) return@launch
            connectionProgress.visibility = View.GONE
            connectionCheckButton.isEnabled = true
            result.onSuccess(::showConnectionReport)
                .onFailure {
                    showConnectionStatus(
                        "The read-only connection check could not be completed.",
                        ERROR_TEXT
                    )
                }
        }
    }

    private fun showConnectionReport(report: BlockchainNetworkCheckReport) {
        val message = buildString {
            append("Network: ${report.networkName}\n")
            append(stepLine("RPC", report.rpc))
            append('\n')
            append(stepLine("Chain ID", report.chain))
            append('\n')
            append(stepLine("Contract", report.contract))
            append('\n')
            append(stepLine("Interface", report.contractInterface))
            append("\nNo wallet was connected and no transaction was submitted.")
        }
        showConnectionStatus(
            message,
            if (report.succeeded) MapSafeUi.GREEN_TEXT else ERROR_TEXT
        )
        connectionStatusText.contentDescription =
            if (report.succeeded) "Blockchain connection check passed" else "Blockchain connection check failed"
    }

    private fun stepLine(label: String, step: BlockchainCheckStep): String =
        "$label - ${step.state.displayName}: ${step.message}"

    private val BlockchainCheckState.displayName: String
        get() = when (this) {
            BlockchainCheckState.PASS -> "PASS"
            BlockchainCheckState.FAIL -> "FAIL"
            BlockchainCheckState.SKIPPED -> "NOT RUN"
        }

    private fun observeProfileChanges() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) = invalidateConnectionCheck()
        }
        listOf(nameInput, chainIdInput, rpcInput, explorerInput, contractInput)
            .forEach { it.addTextChangedListener(watcher) }
        environmentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = invalidateConnectionCheck()
        }
    }

    private fun invalidateConnectionCheck() {
        connectionCheckGeneration++
        connectionCheckJob?.cancel()
        if (!::connectionStatusText.isInitialized) return
        connectionProgress.visibility = View.GONE
        connectionCheckButton.isEnabled = true
        connectionStatusText.contentDescription = null
        showConnectionStatus(CONNECTION_NOT_TESTED, MapSafeUi.MUTED)
    }

    private fun showConnectionStatus(message: String, color: Int) {
        connectionStatusText.text = message
        connectionStatusText.setTextColor(color)
    }

    private fun confirmProductionSave(profile: BlockchainNetworkProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Use a production network?")
            .setMessage(
                "${profile.displayName} is marked as a production network. Future notarisation transactions may spend real funds. This screen saves settings only and will not submit a transaction."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save network") { _, _ -> save(profile) }
            .show()
    }

    private fun save(profile: BlockchainNetworkProfile) {
        try {
            val profileIndex = configuration.profiles.indexOfFirst { it.id == profile.id }
            val updatedProfiles = configuration.profiles.toMutableList().apply {
                if (profileIndex >= 0) set(profileIndex, profile) else add(profile)
            }
            configuration = BlockchainNetworkProfiles(
                activeProfileId = profile.id,
                profiles = updatedProfiles
            )
            repository.save(configuration)
            selectedProfileIndex = configuration.profiles.indexOfFirst { it.id == profile.id }
            loadingSelection = true
            profileSpinner.adapter = spinnerAdapter(profileLabels())
            profileSpinner.setSelection(selectedProfileIndex, false)
            loadingSelection = false
            showStatus(
                "${profile.displayName} is now the active network. No blockchain request was made.",
                MapSafeUi.GREEN_TEXT
            )
            parentFragmentManager.setFragmentResult(
                REQUEST_NETWORK_PROFILE_CHANGED,
                Bundle().apply { putString(RESULT_ACTIVE_PROFILE_ID, profile.id) }
            )
        } catch (error: Exception) {
            showStatus(
                "The network settings could not be saved: ${error.message ?: error.javaClass.simpleName}",
                ERROR_TEXT
            )
        }
    }

    private fun editor(hintValue: String, inputTypeValue: Int): EditText =
        EditText(requireContext()).apply {
            hint = hintValue
            inputType = inputTypeValue
            isSingleLine = true
            textSize = 14f
            setTextColor(MapSafeUi.TEXT)
        }

    private fun labelled(label: String, child: android.view.View): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(MapSafeUi.text(requireContext(), label, 13f, MapSafeUi.MUTED, bold = true))
            addView(child)
            setPadding(0, 0, 0, dp(8))
        }

    private fun spinnerAdapter(values: List<String>): ArrayAdapter<String> =
        ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            values
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun profileLabels(): List<String> = configuration.profiles.map { profile ->
        profile.displayName + if (profile.id == configuration.activeProfileId) " (active)" else ""
    }

    private fun showStatus(message: String, color: Int) {
        statusText.text = message
        statusText.setTextColor(color)
        statusText.setPadding(0, 0, 0, dp(8))
    }

    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        const val TAG = "BlockchainNetworkSettingsDialog"
        const val REQUEST_NETWORK_PROFILE_CHANGED = "mapsafe_blockchain_profile_changed"
        const val RESULT_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val ERROR_TEXT = 0xffb3261e.toInt()
        private const val CONNECTION_NOT_TESTED =
            "Not tested for the values currently shown."
    }
}
