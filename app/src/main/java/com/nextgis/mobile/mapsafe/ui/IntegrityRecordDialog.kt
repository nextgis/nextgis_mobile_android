package com.nextgis.mobile.mapsafe.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nextgis.mobile.mapsafe.access.check.EvmReferenceValidation
import com.nextgis.mobile.mapsafe.access.check.EvmTransactionReferenceParser
import com.nextgis.mobile.mapsafe.access.check.VerifiedDecryptHandoffGate
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfile
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfileRepository
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfileValidator
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkPresets
import com.nextgis.mobile.mapsafe.blockchain.BlockchainProfileValidation
import com.nextgis.mobile.mapsafe.blockchain.EthereumTransactionVerificationReport
import com.nextgis.mobile.mapsafe.blockchain.EthereumTransactionVerificationState
import com.nextgis.mobile.mapsafe.blockchain.EthereumTransactionVerifier
import com.nextgis.mobile.mapsafe.blockchain.MapSafeIntegrityRecordFormat
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local hash verification and styled notarisation screens. */
class IntegrityRecordDialog : DialogFragment() {
    private lateinit var verificationFileName: TextView
    private lateinit var verificationHash: TextView
    private lateinit var verificationStatus: TextView
    private lateinit var verificationProgress: ProgressBar
    private lateinit var selectVerificationFileButton: Button
    private lateinit var transactionReferenceInput: EditText
    private lateinit var transactionReferenceStatus: TextView
    private lateinit var comparisonProgress: ProgressBar
    private lateinit var comparisonStatus: TextView
    private lateinit var retrieveAndCompareButton: Button
    private lateinit var continueToDecryptButton: Button
    private lateinit var networkRepository: BlockchainNetworkProfileRepository
    private var networkSummary: TextView? = null
    private var activeNetworkProfile = BlockchainNetworkPresets.defaults().activeProfile
    private var selectedVerificationFileName = NO_FILE_SELECTED
    private var selectedVerificationFileUri: Uri? = null
    private var calculatedVerificationHash: String? = null
    private var transactionReferenceValue = ""
    private var validatedTransactionHash: String? = null
    private var validatedTransactionProfileId: String? = null
    private var verificationHashJob: Job? = null
    private var transactionVerificationJob: Job? = null
    private var comparisonRequestId = 0L
    private var comparisonRunning = false
    private var comparisonState: EthereumTransactionVerificationState? = null
    private var notarisationFileUri: Uri? = null
    private var notarisationFileDisplayName = NO_FILE_SELECTED
    private var calculatedNotarisationHash: String? = null
    private var notarisationHashJob: Job? = null

    private val selectVerificationFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::hashVerificationFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkRepository = BlockchainNetworkProfileRepository(requireContext())
        activeNetworkProfile = runCatching { networkRepository.load().activeProfile }
            .getOrElse { BlockchainNetworkPresets.defaults().activeProfile }
        notarisationFileUri = requireArguments().getString(ARG_NOTARISATION_FILE_URI)?.let(Uri::parse)
        notarisationFileDisplayName = requireArguments()
            .getString(ARG_NOTARISATION_FILE_NAME)
            ?.takeIf(String::isNotBlank)
            ?: notarisationFileUri?.let(::displayName)
            ?: NO_FILE_SELECTED
        selectedVerificationFileName = savedInstanceState?.getString(STATE_FILE_NAME)
            ?: requireArguments().getString(ARG_VERIFICATION_FILE_NAME)
            ?: NO_FILE_SELECTED
        selectedVerificationFileUri = (
            savedInstanceState?.getString(STATE_FILE_URI)
                ?: requireArguments().getString(ARG_VERIFICATION_FILE_URI)
            )?.let(Uri::parse)
        calculatedVerificationHash = savedInstanceState?.getString(STATE_FILE_HASH)
            ?: requireArguments().getString(ARG_VERIFICATION_FILE_HASH)
        if (selectedVerificationFileUri == null) calculatedVerificationHash = null
        transactionReferenceValue = savedInstanceState?.getString(STATE_TRANSACTION_REFERENCE)
            ?: requireArguments().getString(ARG_VERIFICATION_TRANSACTION_REFERENCE).orEmpty()
        validatedTransactionHash = savedInstanceState?.getString(STATE_TRANSACTION_HASH)
        validatedTransactionProfileId = savedInstanceState?.getString(STATE_TRANSACTION_PROFILE_ID)
        if (validatedTransactionProfileId != activeNetworkProfile.id) {
            validatedTransactionHash = null
            validatedTransactionProfileId = null
        }
        parentFragmentManager.setFragmentResultListener(
            BlockchainNetworkSettingsDialog.REQUEST_NETWORK_PROFILE_CHANGED,
            this
        ) { _, _ -> reloadActiveNetworkProfile() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILE_NAME, selectedVerificationFileName)
        selectedVerificationFileUri?.let { outState.putString(STATE_FILE_URI, it.toString()) }
        calculatedVerificationHash?.let { outState.putString(STATE_FILE_HASH, it) }
        outState.putString(STATE_TRANSACTION_REFERENCE, transactionReferenceValue)
        validatedTransactionHash?.let { outState.putString(STATE_TRANSACTION_HASH, it) }
        validatedTransactionProfileId?.let {
            outState.putString(STATE_TRANSACTION_PROFILE_ID, it)
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        showParent()
    }

    override fun onDestroy() {
        verificationHashJob?.cancel()
        notarisationHashJob?.cancel()
        transactionVerificationJob?.cancel()
        comparisonRequestId++
        super.onDestroy()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return if (requireArguments().getString(ARG_PARENT) == PARENT_ACCESS) {
            buildVerificationScreen()
        } else {
            buildNotarisationScreen()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(MapSafeUi.PAGE))
            setDimAmount(0f)
        }
    }

    private fun buildVerificationScreen(): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        verificationFileName = MapSafeUi.text(
            context,
            selectedVerificationFileName,
            15f,
            MapSafeUi.TEXT,
            bold = true
        )
        selectVerificationFileButton = MapSafeUi.outlineButton(
            context,
            "Select encrypted file",
            ::beginVerificationFileSelection
        )
        verificationHash = MapSafeUi.text(
            context,
            calculatedVerificationHash ?: HASH_NOT_GENERATED,
            13f,
            if (calculatedVerificationHash == null) MapSafeUi.MUTED else MapSafeUi.TEXT,
            bold = calculatedVerificationHash != null
        ).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            calculatedVerificationHash?.let {
                contentDescription = "Calculated SHA-256: $it"
            }
        }
        verificationStatus = MapSafeUi.text(
            context,
            "",
            13f,
            MapSafeUi.MUTED
        ).apply { visibility = View.GONE }
        verificationProgress = ProgressBar(context).apply {
            visibility = View.GONE
            contentDescription = "Calculating SHA-256"
        }
        transactionReferenceInput = EditText(context).apply {
            hint = transactionInputHint()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = false
            maxLines = 3
            textSize = 14f
            setTextColor(MapSafeUi.TEXT)
            setText(transactionReferenceValue)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(text: Editable?) {
                    val changedValue = text?.toString().orEmpty()
                    if (changedValue == transactionReferenceValue) return
                    transactionReferenceValue = changedValue
                    validatedTransactionHash = null
                    validatedTransactionProfileId = null
                    transactionReferenceInput.error = null
                    showTransactionValidationPrompt()
                    resetBlockchainComparison()
                }
            })
        }
        transactionReferenceStatus = MapSafeUi.text(
            context,
            transactionReferenceStatusText(),
            13f,
            if (validatedTransactionHash == null) MapSafeUi.MUTED else MapSafeUi.GREEN_TEXT
        )
        comparisonProgress = ProgressBar(context).apply {
            visibility = View.GONE
            contentDescription = "Retrieving blockchain transaction"
        }
        comparisonStatus = MapSafeUi.text(
            context,
            comparisonPrompt(),
            13f,
            MapSafeUi.MUTED
        ).apply { setTextIsSelectable(true) }
        retrieveAndCompareButton = MapSafeUi.primaryButton(
            context,
            "Retrieve and compare record",
            ::retrieveAndCompareRecord
        )
        val verificationActions = MapSafeUi.nextStopActions(
            context,
            nextLabel = "Next: Decrypt",
            onNext = ::continueToVerifiedDecryption,
            onStop = ::dismiss
        )
        continueToDecryptButton = verificationActions.getChildAt(1) as Button
        updateComparisonReadiness()
        updateContinueReadiness()

        val page = MapSafeUi.page(context).apply {
            setPadding(dp(16), dp(10), dp(16), dp(24))
            addView(MapSafeUi.accessStepStrip(context, MapSafeUi.AccessStep.VERIFY))
            addView(MapSafeUi.screenHeading(
                context,
                "Verification",
                "Calculate the encrypted file's SHA-256 hash before comparing it with its blockchain record."
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Encrypted File"),
                verificationFileName,
                MapSafeUi.divider(context),
                MapSafeUi.text(context, "Local SHA-256", 13f, MapSafeUi.MUTED, bold = true),
                verificationHash,
                verificationProgress,
                verificationStatus,
                selectVerificationFileButton
            ))
            addView(MapSafeUi.card(
                context,
                MapSafeUi.sectionTitle(context, "Blockchain Verification"),
                MapSafeUi.text(
                    context,
                    "Enter a transaction URL or 0x hash to retrieve its record and compare it with the local SHA-256.",
                    13f,
                    MapSafeUi.MUTED
                ),
                transactionReferenceInput,
                MapSafeUi.outlineButton(
                    context,
                    "Validate transaction reference",
                    ::validateTransactionReference
                ),
                MapSafeUi.divider(context),
                transactionReferenceStatus,
                retrieveAndCompareButton,
                comparisonProgress,
                MapSafeUi.divider(context),
                comparisonStatus
            ))
            verificationActions.setPadding(0, dp(4), 0, dp(12))
            addView(verificationActions)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapSafeUi.PAGE)
            addView(toolbar())
            addView(ScrollView(context).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.setContentView(root)
        return dialog
    }

    private fun beginVerificationFileSelection() {
        selectVerificationFile.launch(
            arrayOf("application/pgp-encrypted", "application/octet-stream", "*/*")
        )
    }

    private fun hashVerificationFile(uri: Uri) {
        verificationHashJob?.cancel()
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        selectedVerificationFileName = displayName(uri)
        selectedVerificationFileUri = uri
        calculatedVerificationHash = null
        resetBlockchainComparison()
        verificationFileName.text = selectedVerificationFileName
        verificationHash.apply {
            text = HASH_NOT_GENERATED
            setTextColor(MapSafeUi.MUTED)
            contentDescription = null
        }
        verificationStatus.apply {
            text = "Calculating the local SHA-256 hash..."
            visibility = View.VISIBLE
        }
        verificationProgress.visibility = View.VISIBLE
        selectVerificationFileButton.isEnabled = false
        val resolver = requireContext().contentResolver

        verificationHashJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use(HashUtils::sha256)
                        ?: error("The selected document could not be opened.")
                }
            }
            if (!isAdded || !::verificationHash.isInitialized) return@launch
            verificationProgress.visibility = View.GONE
            selectVerificationFileButton.isEnabled = true
            result.onSuccess { hash ->
                calculatedVerificationHash = hash
                verificationHash.apply {
                    text = hash
                    setTextColor(MapSafeUi.TEXT)
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    contentDescription = "Calculated SHA-256: $hash"
                }
                verificationStatus.visibility = View.GONE
                updateComparisonReadiness()
                updateContinueReadiness()
            }.onFailure { error ->
                verificationHash.text = HASH_NOT_GENERATED
                verificationStatus.apply {
                    text = "Hash calculation failed: ${error.message ?: error.javaClass.simpleName}"
                    visibility = View.VISIBLE
                }
                updateComparisonReadiness()
                updateContinueReadiness()
            }
        }
    }

    private fun validateTransactionReference() {
        resetBlockchainComparison()
        transactionReferenceValue = transactionReferenceInput.text?.toString().orEmpty()
        when (
            val validation = EvmTransactionReferenceParser.validate(
                transactionReferenceValue,
                activeNetworkProfile
            )
        ) {
            is EvmReferenceValidation.Valid -> {
                validatedTransactionHash = validation.reference.transactionHash
                validatedTransactionProfileId = validation.reference.networkProfileId
                transactionReferenceInput.error = null
                transactionReferenceStatus.apply {
                    text = validTransactionStatusText(
                        validation.reference.transactionHash,
                        validation.reference.networkName,
                        validation.reference.chainId
                    )
                    setTextColor(MapSafeUi.GREEN_TEXT)
                    contentDescription =
                        "Valid ${validation.reference.networkName} transaction ${validation.reference.transactionHash}"
                }
                updateComparisonReadiness()
            }

            is EvmReferenceValidation.Invalid -> {
                validatedTransactionHash = null
                validatedTransactionProfileId = null
                transactionReferenceInput.error = validation.message
                transactionReferenceStatus.apply {
                    text = validation.message
                    setTextColor(TRANSACTION_ERROR_TEXT)
                    contentDescription = "Invalid blockchain transaction reference"
                }
                updateComparisonReadiness()
            }
        }
    }

    private fun retrieveAndCompareRecord() {
        val localHash = calculatedVerificationHash
        val transactionHash = validatedTransactionHash
        if (localHash == null ||
            transactionHash == null ||
            validatedTransactionProfileId != activeNetworkProfile.id
        ) {
            resetBlockchainComparison()
            return
        }
        if (BlockchainNetworkProfileValidator.validate(activeNetworkProfile) is BlockchainProfileValidation.Invalid) {
            comparisonStatus.apply {
                text = "The active blockchain profile is incomplete. Open network settings before retrieving the transaction."
                setTextColor(TRANSACTION_ERROR_TEXT)
                contentDescription = "Blockchain comparison unavailable"
            }
            return
        }

        transactionVerificationJob?.cancel()
        val requestId = ++comparisonRequestId
        val profile = activeNetworkProfile
        comparisonRunning = true
        comparisonProgress.visibility = View.VISIBLE
        comparisonStatus.apply {
            text = "Retrieving the transaction and receipt from ${profile.displayName}..."
            setTextColor(MapSafeUi.MUTED)
            contentDescription = "Blockchain comparison in progress"
        }
        updateComparisonReadiness()

        transactionVerificationJob = lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                EthereumTransactionVerifier().verify(profile, transactionHash, localHash)
            }
            if (requestId != comparisonRequestId ||
                !isAdded ||
                !::comparisonStatus.isInitialized
            ) {
                return@launch
            }
            comparisonRunning = false
            comparisonProgress.visibility = View.GONE
            renderComparisonReport(report)
            updateComparisonReadiness()
        }
    }

    private fun renderComparisonReport(report: EthereumTransactionVerificationReport) {
        comparisonState = report.state
        val details = buildString {
            append(report.message)
            report.onChainHash?.let { append("\nOn-chain SHA-256: $it") }
            report.recordFormat?.let { append("\nRecord format: ${recordFormatLabel(it)}") }
            report.blockNumber?.let { append("\nBlock: $it") }
            report.sender?.let { append("\nSender: $it") }
            if (report.state == EthereumTransactionVerificationState.MATCH) {
                append("\nThe receipt confirms successful mining; confirmation depth is not assessed.")
            }
        }
        comparisonStatus.apply {
            text = details
            setTextColor(
                when (report.state) {
                    EthereumTransactionVerificationState.MATCH -> MapSafeUi.GREEN_TEXT
                    EthereumTransactionVerificationState.PENDING -> MapSafeUi.MUTED
                    EthereumTransactionVerificationState.HASH_MISMATCH,
                    EthereumTransactionVerificationState.INVALID -> TRANSACTION_ERROR_TEXT
                }
            )
            contentDescription = when (report.state) {
                EthereumTransactionVerificationState.MATCH -> "Blockchain hash matches the selected file"
                EthereumTransactionVerificationState.HASH_MISMATCH ->
                    "Blockchain hash does not match the selected file"
                EthereumTransactionVerificationState.PENDING -> "Blockchain transaction pending"
                EthereumTransactionVerificationState.INVALID -> "Blockchain transaction verification failed"
            }
        }
        updateContinueReadiness()
    }

    private fun recordFormatLabel(format: MapSafeIntegrityRecordFormat): String = when (format) {
        MapSafeIntegrityRecordFormat.MAPSAFE_V1 -> "MapSafe v1"
        MapSafeIntegrityRecordFormat.LEGACY_QGIS_FILENAME_HASH -> "Legacy QGIS"
    }

    private fun resetBlockchainComparison() {
        transactionVerificationJob?.cancel()
        transactionVerificationJob = null
        comparisonRequestId++
        comparisonRunning = false
        comparisonState = null
        if (::comparisonProgress.isInitialized) comparisonProgress.visibility = View.GONE
        if (::comparisonStatus.isInitialized) {
            comparisonStatus.apply {
                text = comparisonPrompt()
                setTextColor(MapSafeUi.MUTED)
                contentDescription = null
            }
        }
        updateComparisonReadiness()
        updateContinueReadiness()
    }

    private fun updateComparisonReadiness() {
        if (!::retrieveAndCompareButton.isInitialized) return
        val ready = !comparisonRunning &&
            calculatedVerificationHash != null &&
            validatedTransactionHash != null &&
            validatedTransactionProfileId == activeNetworkProfile.id
        retrieveAndCompareButton.isEnabled = ready
        retrieveAndCompareButton.alpha = if (ready) 1f else 0.55f
    }

    private fun updateContinueReadiness() {
        if (!::continueToDecryptButton.isInitialized) return
        val ready = VerifiedDecryptHandoffGate.isReady(
            selectedVerificationFileUri?.toString(),
            calculatedVerificationHash
        )
        continueToDecryptButton.isEnabled = ready
        continueToDecryptButton.alpha = if (ready) 1f else 0.55f
    }

    private fun continueToVerifiedDecryption() {
        val sourceUri = selectedVerificationFileUri
        val localHash = calculatedVerificationHash
        if (!VerifiedDecryptHandoffGate.isReady(
                sourceUri?.toString(),
                localHash
            ) || sourceUri == null || localHash == null
        ) {
            updateContinueReadiness()
            return
        }

        startActivity(
            MapSafeOpenPgpActivity.intent(
                requireContext(),
                decrypt = true,
                verifiedSourceUri = sourceUri,
                verifiedSourceDisplayName = selectedVerificationFileName,
                verifiedSourceSha256 = localHash,
                verificationNetworkName = activeNetworkProfile.displayName.takeIf {
                    comparisonState == EthereumTransactionVerificationState.MATCH
                },
                verificationTransactionHash = validatedTransactionHash.takeIf {
                    comparisonState == EthereumTransactionVerificationState.MATCH
                }
            )
        )
        dismiss()
    }

    private fun comparisonPrompt(): String = when {
        calculatedVerificationHash == null && validatedTransactionHash == null ->
            "Select an encrypted file, calculate its hash, and validate a transaction reference first."
        calculatedVerificationHash == null ->
            "Select an encrypted file and calculate its local SHA-256 first."
        validatedTransactionHash == null || validatedTransactionProfileId != activeNetworkProfile.id ->
            "Validate a transaction reference for ${activeNetworkProfile.displayName} first."
        else -> "Ready to retrieve and compare the blockchain record."
    }

    private fun showTransactionValidationPrompt() {
        if (!::transactionReferenceStatus.isInitialized) return
        transactionReferenceStatus.apply {
            text = transactionReferencePrompt()
            setTextColor(MapSafeUi.MUTED)
            contentDescription = null
        }
    }

    private fun transactionReferenceStatusText(): String =
        validatedTransactionHash
            ?.takeIf { validatedTransactionProfileId == activeNetworkProfile.id }
            ?.let {
                validTransactionStatusText(
                    it,
                    activeNetworkProfile.displayName,
                    activeNetworkProfile.chainId
                )
            }
            ?: transactionReferencePrompt()

    private fun validTransactionStatusText(hash: String, networkName: String, chainId: Long): String =
        "Valid $networkName reference (chain ID $chainId).\nTransaction: $hash\nNo blockchain request has been made."

    private fun transactionReferencePrompt(): String =
        "Enter a reference for ${activeNetworkProfile.displayName} and validate it. No blockchain request will be made."

    private fun transactionInputHint(): String =
        "${activeNetworkProfile.displayName} explorer URL or 0x transaction hash"

    private fun blockchainNetworkCard(): LinearLayout {
        val context = requireContext()
        networkSummary = MapSafeUi.text(
            context,
            networkDisplayName(activeNetworkProfile),
            15f,
            MapSafeUi.TEXT,
            bold = true
        )
        return MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Blockchain Network"),
            networkSummary!!
        )
    }

    private fun reloadActiveNetworkProfile() {
        val loaded = runCatching { networkRepository.load().activeProfile }
        loaded.onSuccess { profile ->
            val profileChanged = profile != activeNetworkProfile
            activeNetworkProfile = profile
            if (profileChanged || validatedTransactionProfileId != profile.id) {
                validatedTransactionHash = null
                validatedTransactionProfileId = null
            }
            networkSummary?.text = networkDisplayName(profile)
            if (::transactionReferenceInput.isInitialized) {
                transactionReferenceInput.hint = transactionInputHint()
                transactionReferenceInput.error = null
                showTransactionValidationPrompt()
            }
            resetBlockchainComparison()
        }
    }

    private fun networkDisplayName(profile: BlockchainNetworkProfile): String =
        "${profile.displayName} - ${profile.environment.displayName}"

    private fun displayName(uri: Uri): String {
        val resolver = requireContext().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "Selected encrypted package"
    }

    private fun buildNotarisationScreen(): Dialog {
        val context = requireContext()
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val fileName = MapSafeUi.text(
            context,
            notarisationFileDisplayName,
            15f,
            MapSafeUi.TEXT,
            bold = true
        )
        val fileHash = MapSafeUi.text(
            context,
            if (notarisationFileUri == null) HASH_NOT_GENERATED else HASH_CALCULATING,
            13f,
            MapSafeUi.MUTED,
            bold = false
        ).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val hashProgress = ProgressBar(context).apply {
            visibility = if (notarisationFileUri == null) View.GONE else View.VISIBLE
            contentDescription = "Calculating notarisation SHA-256"
        }
        val page = MapSafeUi.page(context)
        page.setPadding(dp(16), dp(10), dp(16), dp(24))
        page.addView(MapSafeUi.safeguardStepStrip(context, MapSafeUi.SafeguardStep.NOTARISE))
        page.addView(MapSafeUi.screenHeading(
            context,
            "Notarise on Blockchain",
            "Record the SHA-256 hash of an encrypted file without exposing the protected dataset."
        ))
        page.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Encrypted File"),
            fileName,
            MapSafeUi.divider(context),
            MapSafeUi.text(context, "File Hash (SHA-256)", 13f, MapSafeUi.MUTED, bold = true),
            fileHash,
            hashProgress
        ))
        val saveFolder = MapSafeSaveFolderRepository.read(context)
        page.addView(MapSafeUi.card(
            context,
            MapSafeUi.sectionTitle(context, "Local protected files"),
            MapSafeUi.savedLocationRow(
                context,
                MapSafeUi.text(
                    context,
                    "Encrypted packages remain in\n${saveFolder.displayLocation}",
                    13f,
                    MapSafeUi.GREEN_TEXT,
                    bold = true
                )
            ) {
                if (!MapSafeSaveFolderRepository.openFolder(context)) {
                    Toast.makeText(context, "Downloads/MapSafe could not be opened.", Toast.LENGTH_LONG).show()
                }
            }
        ))
        page.addView(blockchainNetworkCard())
        val unavailable = MapSafeUi.primaryButton(context, "🛡  Notarisation unavailable") {}.apply {
            isEnabled = false
            alpha = 0.55f
        }
        page.addView(unavailable)
        page.addView(MapSafeUi.outlineButton(context, "Back to Safeguard Features") {
            dismiss()
            showParent()
        })

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapSafeUi.PAGE)
            addView(toolbar())
            addView(ScrollView(context).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.setContentView(root)
        notarisationFileUri?.let { uri ->
            notarisationHashJob = lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(HashUtils::sha256)
                            ?: error("The encrypted file could not be opened.")
                    }
                }
                hashProgress.visibility = View.GONE
                result.onSuccess { hash ->
                    calculatedNotarisationHash = hash
                    fileHash.apply {
                        text = hash
                        setTextColor(MapSafeUi.TEXT)
                        typeface = Typeface.MONOSPACE
                        contentDescription = "Calculated notarisation SHA-256: $hash"
                    }
                }.onFailure {
                    fileHash.apply {
                        text = "Hash calculation failed"
                        setTextColor(TRANSACTION_ERROR_TEXT)
                        contentDescription = "Notarisation SHA-256 calculation failed"
                    }
                }
            }
        }
        return dialog
    }

    private fun toolbar(): LinearLayout = MapSafeUi.appBar(requireContext()) {
        dismiss()
        showParent()
    }

    private fun showParent() {
        when (requireArguments().getString(ARG_PARENT)) {
            PARENT_ACCESS -> AccessFeaturesDialog().show(parentFragmentManager, "AccessFeaturesDialog")
            else -> SafeguardFeaturesDialog().show(parentFragmentManager, "SafeguardFeaturesDialog")
        }
    }

    private fun dp(value: Int): Int = MapSafeUi.dp(requireContext(), value)

    companion object {
        private const val ARG_PARENT = "mapsafe_integrity_parent"
        private const val ARG_NOTARISATION_FILE_URI = "mapsafe_notarisation_file_uri"
        private const val ARG_NOTARISATION_FILE_NAME = "mapsafe_notarisation_file_name"
        private const val ARG_VERIFICATION_FILE_URI = "mapsafe_verification_file_uri"
        private const val ARG_VERIFICATION_FILE_NAME = "mapsafe_verification_file_name"
        private const val ARG_VERIFICATION_FILE_HASH = "mapsafe_verification_file_hash"
        private const val ARG_VERIFICATION_TRANSACTION_REFERENCE =
            "mapsafe_verification_transaction_reference"
        private const val PARENT_SAFEGUARD = "safeguard"
        private const val PARENT_ACCESS = "access"
        private const val STATE_FILE_NAME = "verification_file_name"
        private const val STATE_FILE_URI = "verification_file_uri"
        private const val STATE_FILE_HASH = "verification_file_hash"
        private const val STATE_TRANSACTION_REFERENCE = "verification_transaction_reference"
        private const val STATE_TRANSACTION_HASH = "verification_transaction_hash"
        private const val STATE_TRANSACTION_PROFILE_ID = "verification_transaction_profile_id"
        private const val NO_FILE_SELECTED = "No encrypted package selected"
        private const val HASH_NOT_GENERATED = "Not generated"
        private const val HASH_CALCULATING = "Calculating…"
        private const val TRANSACTION_ERROR_TEXT = 0xffb3261e.toInt()

        fun forSafeguardFeatures(
            encryptedFileUri: Uri? = null,
            encryptedFileName: String? = null
        ) = IntegrityRecordDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_PARENT, PARENT_SAFEGUARD)
                encryptedFileUri?.let { putString(ARG_NOTARISATION_FILE_URI, it.toString()) }
                encryptedFileName?.let { putString(ARG_NOTARISATION_FILE_NAME, it) }
            }
        }

        fun forAccessFeatures() = IntegrityRecordDialog().apply {
            arguments = Bundle().apply { putString(ARG_PARENT, PARENT_ACCESS) }
        }

        fun forCommunityPackage(
            fileUri: Uri,
            fileName: String,
            calculatedSha256: String,
            transactionReference: String? = null
        ) = IntegrityRecordDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_PARENT, PARENT_ACCESS)
                putString(ARG_VERIFICATION_FILE_URI, fileUri.toString())
                putString(ARG_VERIFICATION_FILE_NAME, fileName)
                putString(ARG_VERIFICATION_FILE_HASH, calculatedSha256)
                transactionReference?.takeIf(String::isNotBlank)?.let {
                    putString(ARG_VERIFICATION_TRANSACTION_REFERENCE, it)
                }
            }
        }
    }
}
