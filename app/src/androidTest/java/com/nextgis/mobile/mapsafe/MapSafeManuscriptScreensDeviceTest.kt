package com.nextgis.mobile.mapsafe

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.fragment.app.DialogFragment
import com.nextgis.maplib.map.Layer
import com.nextgis.maplib.map.TrackLayer
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.NextGisGroupSummary
import com.nextgis.mobile.mapsafe.keys.NextGisPublicKeyDirectoryClient
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import com.nextgis.mobile.mapsafe.ui.DonutMaskingDialog
import com.nextgis.mobile.mapsafe.ui.DonutMaskingResultDialog
import com.nextgis.mobile.mapsafe.ui.AccessFeaturesDialog
import com.nextgis.mobile.mapsafe.ui.HexabinningDialog
import com.nextgis.mobile.mapsafe.ui.HexabinningResultDialog
import com.nextgis.mobile.mapsafe.ui.IntegrityRecordDialog
import com.nextgis.mobile.mapsafe.ui.MapSafeIdentityActivity
import com.nextgis.mobile.mapsafe.ui.MapSafeMainDialog
import com.nextgis.mobile.mapsafe.ui.MapSafeOpenPgpActivity
import com.nextgis.mobile.mapsafe.ui.MapSafeSecurityActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Captures the current, manuscript-relevant MapSafe screens with new filenames.
 *
 * UI Automator is used deliberately because the map remains visible behind several
 * full-window DialogFragments and Espresso may select the unfocused map root on
 * recent Android emulator images.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeManuscriptScreensDeviceTest {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun captureOriginalAndHaloMaskedDatasetsUnobstructed() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            allowScreenshots(scenario)
            waitForMap(scenario)
            val sourceLayerName = loadNorthWhangareiSample(scenario, context)
            scenario.onActivity { activity -> showOnlyDataLayer(activity, context, sourceLayerName) }
            SystemClock.sleep(4_000L)
            capture(context, "ms2026-28-original-sample-dataset-unobstructed")

            val layersBeforeMasking = layerNames(context)
            scenario.onActivity { activity ->
                activity.mapFragment?.runMapSafeDonutMasking(
                    minDistanceMetres = 100.0,
                    maxDistanceMetres = 2_000.0,
                    sourceLayerName = sourceLayerName
                )
            }
            waitText("Halo Masking Applied", 60_000L)

            var maskedLayerName: String? = null
            MapSafeDeviceTestSupport.waitUntil("Halo-masked layer", 30_000L) {
                maskedLayerName = (layerNames(context) - layersBeforeMasking)
                    .firstOrNull { it.startsWith("${sourceLayerName}_masked") }
                maskedLayerName != null
            }
            scenario.onActivity { activity ->
                showDataLayers(
                    activity,
                    context,
                    setOf(sourceLayerName, requireNotNull(maskedLayerName))
                )
            }
            tapDescription("Collapse Halo Masking Applied results")
            SystemClock.sleep(1_200L)
            scenario.onActivity { activity ->
                showDataLayers(
                    activity,
                    context,
                    setOf(sourceLayerName, requireNotNull(maskedLayerName))
                )
                refreshResultOverlay(activity)
            }
            SystemClock.sleep(4_000L)
            capture(context, "ms2026-29-original-and-halo-masked-unobstructed")
        } finally {
            runCatching { scenario.close() }
        }
    }

    /**
     * Captures the paper's hexagonal-binning figure from a clean map state.
     *
     * The broader safeguard capture deliberately demonstrates both anonymisation
     * tools in one run. That leaves its halo-masked point layer on the map and can
     * make the later hexagons look like another point representation. This focused
     * scenario keeps only the precise source before binning, then explicitly shows
     * the source-plus-cells comparison and the final cells on their own.
     */
    @Test
    fun captureCleanHexagonalBinningFigure() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            allowScreenshots(scenario)
            waitForMap(scenario)
            val sourceLayerName = loadNorthWhangareiSample(scenario, context)
            scenario.onActivity { activity ->
                showOnlyDataLayer(activity, context, sourceLayerName)
            }

            openMapSafe(scenario)
            waitText("Safeguard")
            tapDescription("Open Hexagonal Binning")
            waitText("Hexagonal Binning")
            scenario.onActivity { activity ->
                showOnlyDataLayer(activity, context, sourceLayerName)
                (activity.supportFragmentManager.findFragmentByTag(HexabinningDialog.TAG) as? DialogFragment)
                    ?.dismissAllowingStateLoss()
                activity.supportFragmentManager.executePendingTransactions()
                HexabinningDialog.forRebin(sourceLayerName, 6)
                    .show(activity.supportFragmentManager, HexabinningDialog.TAG)
                activity.supportFragmentManager.executePendingTransactions()
            }
            waitTextContains("Resolution 6")
            SystemClock.sleep(2_000L)
            captureHexbin(context, "01-configuration-source-only")

            val layersBeforeBinning = layerNames(context)
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentByTag(HexabinningDialog.TAG) as? DialogFragment)
                    ?.dismissAllowingStateLoss()
                activity.supportFragmentManager.executePendingTransactions()
                activity.mapFragment?.runMapSafeHexabinning(
                    resolution = 6,
                    sourceLayerName = sourceLayerName
                )
            }
            waitText("Hexagonal Binning Applied", 60_000L)

            var hexbinLayerName: String? = null
            MapSafeDeviceTestSupport.waitUntil("clean North Whangārei hexagonal layer", 30_000L) {
                hexbinLayerName = (layerNames(context) - layersBeforeBinning)
                    .firstOrNull { it.contains("hexbin", ignoreCase = true) }
                val layer = hexbinLayerName?.let { context.map.getLayerByName(it) as? VectorLayer }
                layer != null && layer.query(null).isNotEmpty()
            }

            scenario.onActivity { activity ->
                showDataLayers(
                    activity,
                    context,
                    setOf(sourceLayerName, requireNotNull(hexbinLayerName))
                )
                refreshResultOverlay(activity)
            }
            SystemClock.sleep(3_000L)
            captureHexbin(context, "02-applied-source-and-cells")

            tapDescription("Collapse Hexagonal Binning Applied results")
            SystemClock.sleep(1_200L)
            scenario.onActivity { activity ->
                showOnlyDataLayer(activity, context, requireNotNull(hexbinLayerName))
                refreshResultOverlay(activity)
            }
            SystemClock.sleep(3_000L)
            captureHexbin(context, "03-collapsed-cells-only")
        } finally {
            runCatching { scenario.close() }
        }
    }

    @Test
    fun captureNavigationSettingsAndAccess() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            allowScreenshots(scenario)
            waitForMap(scenario)
            loadNorthWhangareiSample(scenario, context)
            openMapSafe(scenario)
            capture(context, "ms2026-01-workflow-chooser")

            scrollDownUntil("Security & Sharing")
            tapDescription("Open Security & Sharing")
            waitText("Security & Sharing")
            capture(context, "ms2026-02-security-account-community")
            scrollDownUntil("Configure blockchain network")
            capture(context, "ms2026-03-security-identity-folder-network")

            tap("Configure blockchain network")
            waitText("Blockchain Network Settings")
            capture(context, "ms2026-04-blockchain-network-profile")
            scrollDownUntil("Save and use this network")
            capture(context, "ms2026-05-blockchain-contract-validation")
        }
    }

    @Test
    fun captureAccessAndVerification() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            allowScreenshots(scenario)
            waitForMap(scenario)
            loadNorthWhangareiSample(scenario, context)
            openMapSafe(scenario)
            tap("Access")
            waitText("Verify Encrypted File")
            capture(context, "ms2026-06-access-community-local")
            tapDescription("Open Verify Encrypted File")
            waitText("Verification")
            capture(context, "ms2026-07-verification-file-and-blockchain")
        }
    }

    @Test
    fun captureCommunityUploadAndPackages() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val premium = premiumAccount(ARG_GUARDIAN_ACCOUNT)?.let { accountName ->
            selectPremiumCommunity(context, accountName)
            ensurePremiumScreenshotIdentity(context)
            true
        } ?: false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            allowScreenshots(scenario)
            waitForMap(scenario)
            val sourceLayerName = loadNorthWhangareiSample(scenario, context)

            scenario.onActivity { activity ->
                activity.mapFragment?.runMapSafeDonutMasking(
                    minDistanceMetres = 100.0,
                    maxDistanceMetres = 2_000.0,
                    sourceLayerName = sourceLayerName
                )
            }
            waitText("Halo Masking Applied", 60_000L)
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments
                    .filterIsInstance<DonutMaskingResultDialog>()
                    .lastOrNull()
                    ?.dismiss()
            }
            SystemClock.sleep(500L)

            if (premium) {
                scenario.onActivity { activity ->
                    activity.mapFragment?.runMapSafeHexabinning(
                        resolution = 8,
                        sourceLayerName = sourceLayerName
                    )
                }
                waitText("Hexagonal Binning Applied", 60_000L)
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<HexabinningResultDialog>()
                        .lastOrNull()
                        ?.dismiss()
                }
                SystemClock.sleep(500L)
            }
            openMapSafe(scenario)
            waitText("Safeguard")

            scrollDownUntil("Upload to Community")
            tapDescription("Open Upload to Community")
            waitText("Upload to Community")
            if (premium) {
                tapContains("My public key")
                tapContains("Halo masked")
                tapContains("Hexagonal bin")
                capturePremium(context, "01-steven-upload-community")
            } else {
                capture(context, "ms2026-30-community-upload-selection")
            }

            device.pressBack()
            waitText("Safeguard")
            tap("Access")
            waitText("Community Packages")
            tapDescription("Open Community Packages")
            waitText("Community Packages")
            if (premium) {
                waitTextContains("community item", 60_000L)
                capturePremium(context, "02-steven-community-public-keys")
                scrollDownUntilTextContains("halo masked")
                capturePremium(context, "03-steven-community-datasets")
            } else {
                SystemClock.sleep(2_000L)
                capture(context, "ms2026-31-community-packages")
            }
        }
    }

    @Test
    fun capturePremiumSecurityAndKeys() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val accountName = requirePremiumAccount(ARG_GUARDIAN_ACCOUNT)
        val selection = selectPremiumCommunity(context, accountName)
        ensurePremiumScreenshotIdentity(context)
        val directory = NextGisPublicKeyDirectoryClient(
            context,
            OpenPgpKeyRepository(context),
            PublicKeyExchangeRepository(context)
        )
        val report = directory.sync(accountName, requireNotNull(selection.groupId))
        assertTrue("Expected three current community public keys.", report.records.size >= 3)

        ActivityScenario.launch(MapSafeSecurityActivity::class.java).use {
            waitText("Security & Sharing", 30_000L)
            scrollDownUntil("4. Group public keys")
            capturePremium(context, "00-steven-security-community-keys")
        }
    }

    @Test
    fun capturePremiumBmaCommunityPackages() {
        capturePremiumCommunityRole(
            argumentName = ARG_PRECISE_ACCOUNT,
            screenshotName = "04-bma-authorised-community-packages",
            targetText = "protected original"
        )
    }

    @Test
    fun capturePremiumAmberCommunityDatasets() {
        capturePremiumCommunityRole(
            argumentName = ARG_ANONYMISED_ACCOUNT,
            screenshotName = "05-amber-anonymised-community-access",
            targetText = "No encrypted packages published"
        )
    }

    @Test
    fun capturePremiumBmaVerificationDecryptionAndMap() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        selectPremiumCommunity(context, requirePremiumAccount(ARG_PRECISE_ACCOUNT))

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            allowScreenshots(scenario)
            waitForMap(scenario)
            scenario.onActivity { activity ->
                AccessFeaturesDialog().show(activity.supportFragmentManager, "PremiumBmaAccess")
            }
            waitText("Community Packages")
            waitTextContains("community item", 60_000L)
            scrollDownUntilTextContains("protected original")
            // The package title can become visible while its action remains below
            // the fold on tall community lists, so scroll to the action itself.
            scrollDownUntil("Download & verify")
            capturePremium(context, "07-bma-community-package-selection")

            tap("Download & verify", 60_000L)
            waitText("Verification", 60_000L)
            waitText("Local SHA-256", 60_000L)
            SystemClock.sleep(2_000L)
            capturePremium(context, "08-bma-package-verification")

            tap("Next: Decrypt", 60_000L)
            waitText("Decrypt & Access", 60_000L)
            waitTextContains("BMA representative", 60_000L)
            capturePremium(context, "09-bma-private-key-decryption")

            scrollDownUntilTextContains("Decrypt Verified File")
            tap("Decrypt Verified File", 60_000L)
            waitText("Unlock private key", 60_000L)
            enterOnlyTextField(PASSPHRASE)
            tapIgnoreCase("Continue")
            waitText("Import decrypted layer?", 90_000L)
            tapIgnoreCase("Import and continue")
            waitTextContains("Dataset ready to access", 60_000L)
            capturePremium(context, "10-bma-decryption-success")

            scrollDownUntilTextContains("Next: Access")
            tap("Next: Access")
            SystemClock.sleep(8_000L)
            capturePremium(context, "11-bma-decrypted-original-map")
        } finally {
            // The access workflow intentionally finishes MainActivity when it opens
            // the recovered layer. ActivityScenario can therefore have no terminal
            // state to close; that expected transition must not fail the capture run.
            runCatching { scenario.close() }
        }
    }

    @Test
    fun capturePremiumOutsiderDenied() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val accountName = requirePremiumAccount(ARG_OUTSIDER_ACCOUNT)
        val groupId = InstrumentationRegistry.getArguments()
            .getString(ARG_COMMUNITY_GROUP_ID)
            ?.toLongOrNull()
            ?: error("$ARG_COMMUNITY_GROUP_ID was not supplied.")
        val communityName = requirePremiumAccount(ARG_COMMUNITY_NAME)
        val directory = NextGisPublicKeyDirectoryClient(
            context,
            OpenPgpKeyRepository(context),
            PublicKeyExchangeRepository(context)
        )
        val account = directory.accountSummaries().single { it.accountName == accountName }
        MapSafeSecurityPreferences.selectGroup(
            context,
            account,
            NextGisGroupSummary(
                id = groupId,
                displayName = communityName,
                keyname = "",
                memberIds = emptySet(),
                memberNames = emptyMap(),
                currentUserId = -1L
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            allowScreenshots(scenario)
            waitForMap(scenario)
            scenario.onActivity { activity ->
                AccessFeaturesDialog().show(activity.supportFragmentManager, "PremiumOutsiderAccess")
            }
            waitText("Community refresh failed", 60_000L)
            capturePremium(context, "06-external-user-community-denied")
        }
    }

    @Test
    fun captureSafeguardAndAnonymisation() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        MapSafeSaveFolderRepository.clear(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            allowScreenshots(scenario)
            waitForMap(scenario)
            val sourceLayerName = loadNorthWhangareiSample(scenario, context)
            openMapSafe(scenario)
            waitText("Safeguard")
            capture(context, "ms2026-08-safeguard-features")

            waitText("Halo Masking")
            capture(context, "ms2026-09-anonymise-options")

            val layersBeforeMasking = layerNames(context)
            tapDescription("Open Halo Masking")
            waitText("Halo Masking")
            capture(context, "ms2026-10-halo-masking-configuration")
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentByTag(DonutMaskingDialog.TAG) as? DialogFragment)
                    ?.dismissAllowingStateLoss()
                activity.supportFragmentManager.executePendingTransactions()
                activity.mapFragment?.runMapSafeDonutMasking(
                    minDistanceMetres = 100.0,
                    maxDistanceMetres = 2_000.0,
                    sourceLayerName = sourceLayerName
                )
            }
            waitText("Halo Masking Applied", 60_000L)
            var maskedLayerName: String? = null
            MapSafeDeviceTestSupport.waitUntil("North Whangārei halo-masked layer", 30_000L) {
                maskedLayerName = (layerNames(context) - layersBeforeMasking)
                    .firstOrNull { it.startsWith("${sourceLayerName}_masked") }
                maskedLayerName != null
            }
            scenario.onActivity { activity ->
                showDataLayers(
                    activity,
                    context,
                    setOf(sourceLayerName, requireNotNull(maskedLayerName))
                )
                refreshResultOverlay(activity)
            }
            capture(context, "ms2026-11-halo-masking-applied-expanded")
            tapDescription("Collapse Halo Masking Applied results")
            SystemClock.sleep(1_200L)
            scenario.onActivity(::refreshResultOverlay)
            capture(context, "ms2026-12-halo-masking-applied-collapsed")
            tapDescription("Expand Halo Masking Applied results")
            SystemClock.sleep(1_000L)
            val savedHalo = saveLayerDirectly(context, requireNotNull(maskedLayerName))
            scenario.onActivity { activity -> showSavedState(activity, savedHalo.fileName) }
            waitTextContains("Saved:", 60_000L)
            scenario.onActivity(::refreshResultOverlay)
            capture(context, "ms2026-13-halo-masking-saved")

            tapDescription("Back")
            waitText("Halo Masking")
            tapDescription("Back")
            waitText("Safeguard")

            tapDescription("Open Hexagonal Binning")
            waitText("Hexagonal Binning")
            capture(context, "ms2026-14-hexagonal-binning-configuration")
            val layersBeforeBinning = layerNames(context)
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentByTag(HexabinningDialog.TAG) as? DialogFragment)
                    ?.dismissAllowingStateLoss()
                activity.supportFragmentManager.executePendingTransactions()
                activity.mapFragment?.runMapSafeHexabinning(
                    resolution = 8,
                    sourceLayerName = sourceLayerName
                )
            }
            waitText("Hexagonal Binning Applied", 60_000L)
            var hexbinLayerName: String? = null
            MapSafeDeviceTestSupport.waitUntil("North Whangārei hexagonal-binned layer", 30_000L) {
                hexbinLayerName = (layerNames(context) - layersBeforeBinning)
                    .firstOrNull { it.contains("hexbin", ignoreCase = true) }
                hexbinLayerName != null
            }
            scenario.onActivity(::refreshResultOverlay)
            capture(context, "ms2026-15-hexagonal-binning-applied-expanded")
            tapDescription("Collapse Hexagonal Binning Applied results")
            SystemClock.sleep(1_200L)
            scenario.onActivity(::refreshResultOverlay)
            capture(context, "ms2026-16-hexagonal-binning-applied-collapsed")
            tapDescription("Expand Hexagonal Binning Applied results")
            SystemClock.sleep(1_000L)
            val savedHexbin = saveLayerDirectly(context, requireNotNull(hexbinLayerName))
            scenario.onActivity { activity -> showSavedState(activity, savedHexbin.fileName) }
            waitTextContains("Saved:", 60_000L)
            scenario.onActivity(::refreshResultOverlay)
            capture(context, "ms2026-17-hexagonal-binning-saved")
        } finally {
            runCatching { scenario.close() }
        }
    }

    @Test
    fun captureIdentityCreationAndSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenario = ActivityScenario.launch(MapSafeIdentityActivity::class.java)
        try {
            allowScreenshots(scenario)
            waitText("Create Encryption Identity")
            capture(context, "ms2026-18-identity-creation")
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
                .sortedBy { it.visibleBounds.top }
            require(fields.size >= 5) { "Expected five identity fields, found ${fields.size}." }
            fields[0].text = "Steven"
            fields[1].text = "North Whangārei biodiversity community"
            fields[2].text = "steven@example.invalid"
            fields[3].text = PASSPHRASE
            fields[4].text = PASSPHRASE
            dismissKeyboardIfVisible()
            scrollDownUntilTextContains("Generate Key Pair")
            tapContains("Generate Key Pair")
            waitText("Key Pair Created Successfully", 300_000L)
            capture(context, "ms2026-19-identity-created")
        } finally {
            runCatching { scenario.close() }
        }
    }

    @Test
    fun captureEncryptionNotarisationAndDecryption() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val repository = OpenPgpKeyRepository(context)
        File(context.noBackupFilesDir, "mapsafe/openpgp/recipients")
            .listFiles()
            .orEmpty()
            .forEach(File::delete)
        val sender = OpenPgpKeyGenerator.generate(
            "Steven (field data custodian) <steven@example.invalid>",
            PASSPHRASE.toCharArray(),
            rsaBits = 2048
        )
        repository.saveLocalIdentity(sender)
        val recipient = OpenPgpKeyGenerator.generate(
            "Amber (authorised researcher) <amber@example.invalid>",
            "recipient test passphrase".toCharArray(),
            rsaBits = 2048
        )
        repository.importPublicKeys(
            ByteArrayInputStream(OpenPgpKeyCodec.encodePublicKeyRing(recipient.publicKeyRing))
        )

        val sourceDirectory = File(context.cacheDir, "mapsafe/manuscript").apply { mkdirs() }
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "manuscript-save-folder"),
            "MapSafe Manuscript Save Folder"
        )
        val mapScenario = ActivityScenario.launch(MainActivity::class.java)
        allowScreenshots(mapScenario)
        waitForMap(mapScenario)
        val sourceLayerName = loadNorthWhangareiSample(mapScenario, context)
        SystemClock.sleep(1_000L)
        var exportedSource: MapSafeGeoJsonWorkflow.ExportResult? = null
        mapScenario.onActivity { activity ->
            val sourceLayer = context.map.getLayerByName(sourceLayerName) as? VectorLayer
                ?: error("The North Whangārei sample layer was not selected.")
            exportedSource = MapSafeGeoJsonWorkflow.exportLayer(sourceLayer, sourceDirectory)
        }
        val sourceExport = requireNotNull(exportedSource)
        require(sourceExport.featureCount == 23) {
            "Expected the complete 23-point North Whangārei sample, exported ${sourceExport.featureCount}."
        }
        // Keep only one ActivityScenario active while capturing the full-screen
        // OpenPGP flow. Leaving MainActivity resumed underneath a second scenario
        // can make the emulator expose the underlying map to UI Automator and
        // produce black/transition screenshots for the activity under test.
        mapScenario.close()
        val source = sourceExport.file
        val sourceDisplayName = sourceExport.fileName
        val encryptedFile = MapSafeTestDocumentProvider.file(context, "$sourceDisplayName.pgp")
        val decryptedFile = MapSafeTestDocumentProvider.file(context, sourceDisplayName)
        encryptedFile.delete()
        decryptedFile.delete()
        SystemClock.sleep(4_000L)

        try {
            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    sourceFile = source,
                    sourceDisplayName = sourceDisplayName
                )
            ).use { encryptScenario ->
                allowScreenshots(encryptScenario)
                waitText("Encrypt & Protect")
                capture(context, "ms2026-20-encrypt-protect")
                scrollDownUntilTextContains("Select Recipients & Encrypt")
                performActivityButtonClick(encryptScenario, "Select Recipients & Encrypt")
                waitText("Select recipients")
                device.findObject(By.textContains("Individual recipient"))?.click()
                capture(context, "ms2026-21-recipient-selection")
                tapIgnoreCase("Continue")
                waitText("Unlock signing key")
                enterOnlyTextField(PASSPHRASE)
                tapIgnoreCase("Continue")
                waitTextContains("Dataset protected", 90_000L)
                capture(context, "ms2026-22-encryption-success")
                scrollDownUntilTextContains("Next: Notarise")
                tap("Next: Notarise")
                waitText("Notarise on Blockchain")
                capture(context, "ms2026-23-notarisation-hash-network")
            }

            waitForFile(encryptedFile)
            val encryptedUri = MapSafeTestDocumentProvider.uri(context, encryptedFile.name)
            val encryptedHash = HashUtils.sha256(encryptedFile)
            val decryptScenario = ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    decrypt = true,
                    verifiedSourceUri = encryptedUri,
                    verifiedSourceDisplayName = encryptedFile.name,
                    verifiedSourceSha256 = encryptedHash,
                    verificationNetworkName = "Ethereum Sepolia",
                    verificationTransactionHash = "0x" + "12".repeat(32)
                )
            )
            try {
                allowScreenshots(decryptScenario)
                waitText("Decrypt & Access")
                capture(context, "ms2026-24-decrypt-and-access")
                scrollDownUntilTextContains("Decrypt Verified File")
                performActivityButtonClick(decryptScenario, "Decrypt Verified File")
                waitText("Unlock private key")
                enterOnlyTextField(PASSPHRASE)
                tapIgnoreCase("Continue")
                waitText("Import decrypted layer?", 90_000L)
                capture(context, "ms2026-25-import-decrypted-layer")
                tapIgnoreCase("Import and continue")
                waitTextContains("Dataset ready to access", 60_000L)
                capture(context, "ms2026-26-decryption-success")
                scrollDownUntilTextContains("Next: Access")
                tap("Next: Access")
                SystemClock.sleep(8_000L)
                capture(context, "ms2026-27-decrypted-dataset-map")
            } finally {
                runCatching { decryptScenario.close() }
            }
        } finally {
            runCatching { mapScenario.close() }
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    @Test
    fun captureFilenameBoundNotarisationScreen() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val encryptedName = "North Whangārei infected trees.geojson.pgp"
        val encryptedFile = MapSafeTestDocumentProvider.file(context, encryptedName).apply {
            parentFile?.mkdirs()
            writeBytes("MapSafe manuscript notarisation sample".toByteArray())
        }
        val encryptedUri = MapSafeTestDocumentProvider.uri(context, encryptedName)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            allowScreenshots(scenario)
            waitForMap(scenario)
            scenario.onActivity { activity ->
                IntegrityRecordDialog.forSafeguardFeatures(encryptedUri, encryptedFile.name)
                    .show(activity.supportFragmentManager, "IntegrityRecordDialog")
            }
            waitText("Notarise on Blockchain")
            waitTextContains("Bind the encrypted filename")
            SystemClock.sleep(2_000L)
            capture(context, "ms2026-23-notarisation-hash-network")
        }
    }

    @Test
    fun captureEncryptProtectScreen() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val repository = OpenPgpKeyRepository(context)
        val sender = OpenPgpKeyGenerator.generate(
            "Steven (field data custodian) <steven@example.invalid>",
            PASSPHRASE.toCharArray(),
            rsaBits = 2048
        )
        repository.saveLocalIdentity(sender)
        val sourceName = "North Whangārei infected trees.geojson"
        val source = File(context.cacheDir, "mapsafe/manuscript/$sourceName").apply {
            parentFile?.mkdirs()
            context.assets.open("mapsafe/north_whangarei_infected_trees.geojson").use { input ->
                outputStream().use(input::copyTo)
            }
        }

        ActivityScenario.launch<MapSafeOpenPgpActivity>(
            MapSafeOpenPgpActivity.intent(
                context,
                sourceFile = source,
                sourceDisplayName = sourceName
            )
        ).use { scenario ->
            allowScreenshots(scenario)
            waitText("Encrypt & Protect")
            SystemClock.sleep(2_000L)
            capture(context, "ms2026-20-encrypt-protect")
        }
    }

    private fun waitForMap(scenario: ActivityScenario<MainActivity>) {
        MapSafeDeviceTestSupport.waitUntil("MainActivity MapLibre style", 90_000L) {
            var ready = false
            scenario.onActivity { activity ->
                ready = runCatching {
                    activity.mapFragment?.mMapRef?.get()?.map?.maplibreMap?.get()?.style != null
                }.getOrDefault(false)
            }
            ready
        }
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        check(
            device.wait(Until.hasObject(By.pkg(packageName).desc("More options")), 90_000L)
        ) { "MainActivity remained behind its Android launch screen." }
        device.waitForIdle()
        SystemClock.sleep(1_000L)
    }

    private fun <A : Activity> allowScreenshots(scenario: ActivityScenario<A>) {
        scenario.onActivity { activity ->
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun layerNames(context: MainApplication): Set<String> =
        (0 until context.map.layerCount).map { context.map.getLayer(it).name }.toSet()

    private fun loadNorthWhangareiSample(
        scenario: ActivityScenario<MainActivity>,
        context: MainApplication
    ): String {
        var loaded = false
        var layerName: String? = null
        scenario.onActivity { activity ->
            loaded = activity.mapFragment?.loadMapSafeSamplePoints() == true
            layerName = activity.mapFragment?.selectedLayer?.name
        }
        check(loaded) { "The bundled North Whangārei sample dataset could not be loaded." }
        val name = requireNotNull(layerName)
        MapSafeDeviceTestSupport.waitUntil("complete North Whangārei sample layer", 30_000L) {
            val layer = context.map.getLayerByName(name) as? VectorLayer
            layer?.query(null)?.size == 23
        }
        return name
    }

    private fun showOnlyDataLayer(
        activity: MainActivity,
        context: MainApplication,
        layerName: String
    ) = showDataLayers(activity, context, setOf(layerName))

    private fun showDataLayers(
        activity: MainActivity,
        context: MainApplication,
        layerNames: Set<String>
    ) {
        val mapView = activity.mapFragment?.mMapRef?.get()
        for (index in 0 until context.map.layerCount) {
            val layer = context.map.getLayer(index)
            if (layer is VectorLayer || layer is TrackLayer) {
                (layer as Layer).isVisible = layer.name in layerNames
                mapView?.map?.checkLayerVisibility(layer.id)
            }
        }
        context.map.save()
        mapView?.drawMapDrawable()
        mapView?.postInvalidate()
    }

    private fun refreshResultOverlay(activity: MainActivity) {
        currentResultDialog(activity)?.dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.requestLayout()
            decorView.invalidate()
        }
    }

    private fun performResultButtonClick(
        scenario: ActivityScenario<MainActivity>,
        label: String
    ) {
        var clicked = false
        scenario.onActivity { activity ->
            val root = currentResultDialog(activity)?.dialog?.window?.decorView
            val button = root?.let { findButton(it, label) }
            clicked = button?.performClick() == true
        }
        check(clicked) { "The result button '$label' was not available." }
    }

    private fun <A : Activity> performActivityButtonClick(
        scenario: ActivityScenario<A>,
        label: String
    ) {
        var clicked = false
        scenario.onActivity { activity ->
            clicked = findButton(activity.window.decorView, label)?.performClick() == true
        }
        check(clicked) { "The activity button '$label' was not available." }
        device.waitForIdle()
    }

    private fun saveLayerDirectly(
        context: MainApplication,
        layerName: String
    ): MapSafeSaveFolderRepository.SavedFile<*> {
        val layer = context.map.getLayerByName(layerName) as? VectorLayer
            ?: error("The derived layer '$layerName' is no longer available.")
        val fileName = layerName.replace(Regex("[^A-Za-z0-9._ -]+"), "_")
            .trim('.', ' ')
            .ifBlank { "mapsafe-derived-layer" } + ".geojson"
        return MapSafeSaveFolderRepository.save(
            context,
            "application/geo+json",
            fileName
        ) { uri ->
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                MapSafeGeoJsonWorkflow.exportLayer(layer, output)
            } ?: error("The MapSafe save folder did not open the new file.")
        }
    }

    private fun showSavedState(activity: MainActivity, fileName: String) {
        val result = currentResultDialog(activity) ?: error("No result screen is open.")
        val textField = result.javaClass.getDeclaredField("saveLocationText").apply {
            isAccessible = true
        }
        val rowField = result.javaClass.getDeclaredField("saveLocationRow").apply {
            isAccessible = true
        }
        (textField.get(result) as TextView).text = "Saved: $fileName"
        (rowField.get(result) as View).visibility = View.VISIBLE
        refreshResultOverlay(activity)
    }

    private fun currentResultDialog(activity: MainActivity): DialogFragment? =
        activity.supportFragmentManager.fragments
            .mapNotNull { fragment ->
                when (fragment) {
                    is DonutMaskingResultDialog -> fragment
                    is HexabinningResultDialog -> fragment
                    else -> null
                }
            }
            .lastOrNull()

    private fun findButton(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index), label)?.let { return it }
            }
        }
        return null
    }

    private fun openMapSafe(scenario: ActivityScenario<MainActivity>) {
        if (device.hasObject(By.desc("MapSafe full logo")) && device.hasObject(By.text("Safeguard"))) {
            return
        }
        scenario.onActivity { activity ->
            MapSafeMainDialog.forTab(MapSafeMainDialog.DESTINATION_SAFEGUARD)
                .show(activity.supportFragmentManager, MapSafeMainDialog.TAG)
        }
        waitText("Safeguard")
    }

    private fun capturePremiumCommunityRole(
        argumentName: String,
        screenshotName: String,
        targetText: String
    ) {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        selectPremiumCommunity(context, requirePremiumAccount(argumentName))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            allowScreenshots(scenario)
            waitForMap(scenario)
            scenario.onActivity { activity ->
                AccessFeaturesDialog().show(activity.supportFragmentManager, "PremiumCommunityAccess")
            }
            waitText("Community Packages")
            waitTextContains("community item", 60_000L)
            scrollDownUntilTextContains(targetText)
            capturePremium(context, screenshotName)
        }
    }

    private fun selectPremiumCommunity(
        context: MainApplication,
        accountName: String
    ): MapSafeSecurityPreferences.Selection {
        val communityName = requirePremiumAccount(ARG_COMMUNITY_NAME)
        val directory = NextGisPublicKeyDirectoryClient(
            context,
            OpenPgpKeyRepository(context),
            PublicKeyExchangeRepository(context)
        )
        val account = directory.accountSummaries().single { it.accountName == accountName }
        val group = directory.membershipGroups(accountName)
            .single { it.displayName == communityName }
        MapSafeSecurityPreferences.selectGroup(context, account, group)
        return MapSafeSecurityPreferences.read(context)
    }

    private fun ensurePremiumScreenshotIdentity(context: MainApplication) {
        val repository = OpenPgpKeyRepository(context)
        if (repository.hasLocalIdentity()) return
        repository.saveLocalIdentity(
            OpenPgpKeyGenerator.generate(
                "Steven (field data custodian) <steven@mapsafe.example.invalid>",
                PASSPHRASE.toCharArray(),
                rsaBits = 2048
            )
        )
    }

    private fun premiumAccount(argumentName: String): String? =
        InstrumentationRegistry.getArguments().getString(argumentName)?.trim()?.takeIf { it.isNotEmpty() }

    private fun requirePremiumAccount(argumentName: String): String =
        premiumAccount(argumentName) ?: error("$argumentName was not supplied.")

    private fun capture(context: Context, name: String) {
        device.findObject(By.text("Wait"))?.let { waitButton ->
            if (device.hasObject(By.textContains("isn't responding"))) {
                waitButton.click()
                device.waitForIdle()
                SystemClock.sleep(1_000L)
            }
        }
        device.waitForIdle()
        val refreshedName = name.replaceFirst("ms2026-", SCREENSHOT_PREFIX)
        MapSafeDeviceTestSupport.screenshot(context, refreshedName)
    }

    private fun capturePremium(context: Context, suffix: String) {
        device.waitForIdle()
        MapSafeDeviceTestSupport.screenshot(context, "$PREMIUM_SCREENSHOT_PREFIX$suffix")
    }

    private fun captureHexbin(context: Context, suffix: String) {
        device.waitForIdle()
        MapSafeDeviceTestSupport.screenshot(context, "$HEXBIN_SCREENSHOT_PREFIX$suffix")
    }

    private fun tap(text: String, timeout: Long = 30_000L) {
        requireObject(By.text(text), timeout).click()
        device.waitForIdle()
    }

    private fun tapContains(text: String, timeout: Long = 30_000L) {
        requireObject(By.textContains(text), timeout).click()
        device.waitForIdle()
    }

    private fun tapIgnoreCase(text: String, timeout: Long = 30_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            device.findObjects(By.clazz("android.widget.Button"))
                .firstOrNull { it.text?.equals(text, ignoreCase = true) == true }
                ?.let { button ->
                    button.click()
                    device.waitForIdle()
                    return
                }
            SystemClock.sleep(200L)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Button not found (case-insensitive): $text")
    }

    private fun tapDescription(description: String, timeout: Long = 30_000L) {
        requireObject(By.desc(description), timeout).click()
        device.waitForIdle()
    }

    private fun waitText(text: String, timeout: Long = 30_000L): UiObject2 =
        requireObject(By.text(text), timeout)

    private fun waitTextContains(text: String, timeout: Long = 30_000L): UiObject2 =
        requireObject(By.textContains(text), timeout)

    private fun waitTextStartsWith(text: String, timeout: Long = 30_000L): UiObject2 =
        requireObject(By.textStartsWith(text), timeout)

    private fun requireObject(selector: androidx.test.uiautomator.BySelector, timeout: Long): UiObject2 {
        val value = device.wait(Until.findObject(selector), timeout)
        assertNotNull("UI object not found for $selector", value)
        return requireNotNull(value)
    }

    private fun scrollDownUntil(text: String) {
        repeat(6) {
            if (device.hasObject(By.text(text))) return
            swipeDownPage()
        }
        waitText(text)
    }

    private fun scrollDownUntilTextContains(text: String) {
        repeat(14) {
            if (device.hasObject(By.textContains(text))) return
            swipeDownPage()
        }
        waitTextContains(text)
    }

    private fun swipeDownPage() {
        device.swipe(
            device.displayWidth / 2,
            (device.displayHeight * 0.82).toInt(),
            device.displayWidth / 2,
            (device.displayHeight * 0.28).toInt(),
            24
        )
        SystemClock.sleep(350L)
    }

    private fun enterOnlyTextField(value: String) {
        val field = requireObject(By.clazz("android.widget.EditText"), 20_000L)
        field.text = value
        dismissKeyboardIfVisible()
        device.waitForIdle()
    }

    private fun dismissKeyboardIfVisible() {
        val inputMethodVisible = device.hasObject(By.pkg("com.google.android.inputmethod.latin")) ||
            device.hasObject(By.pkg("com.android.inputmethod.latin"))
        if (inputMethodVisible) device.pressBack()
    }

    private fun waitForFile(file: File) {
        MapSafeDeviceTestSupport.waitUntil("${file.name} output", 60_000L) {
            file.isFile && file.length() > 0L
        }
    }

    companion object {
        private const val SCREENSHOT_PREFIX = "ms2026-north-whangarei-20260909-"
        private const val PREMIUM_SCREENSHOT_PREFIX = "ms2026-premium-community-20261006-"
        private const val HEXBIN_SCREENSHOT_PREFIX = "ms2026-north-whangarei-hexbin-20261006-"
        private const val ARG_GUARDIAN_ACCOUNT = "mapsafe.premium.guardian_account"
        private const val ARG_PRECISE_ACCOUNT = "mapsafe.premium.precise_account"
        private const val ARG_ANONYMISED_ACCOUNT = "mapsafe.premium.anonymised_account"
        private const val ARG_OUTSIDER_ACCOUNT = "mapsafe.premium.outsider_account"
        private const val ARG_COMMUNITY_NAME = "mapsafe.premium.community_name"
        private const val ARG_COMMUNITY_GROUP_ID = "mapsafe.premium.community_group_id"
        private const val PASSPHRASE = "MapSafe manuscript recovery 2026!"
    }
}
