package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfileRepository
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import com.nextgis.mobile.mapsafe.ui.MapSafeOpenPgpActivity
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drives the real secure OpenPGP screen with automatic output to MapSafe's save folder. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeOpenPgpUiDeviceTest {

    @Test
    fun secureScreenEncryptsThenDecryptsThroughRealUi() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val passphrase = "tier one visible recovery passphrase"
        val material = OpenPgpKeyGenerator.generate(
            "Tier 1 UI <ui@example.test>",
            passphrase.toCharArray(),
            rsaBits = 2048
        )
        OpenPgpKeyRepository(context).saveLocalIdentity(material)

        val sourceBytes = SAMPLE_GEOJSON.toByteArray(Charsets.UTF_8)
        val sourceDirectory = File(context.cacheDir, "mapsafe/device-ui").apply {
            require(exists() || mkdirs()) { "Could not create the Tier 1 source directory." }
        }
        val source = File(sourceDirectory, "tier1-ui.geojson").apply { writeBytes(sourceBytes) }
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "mapsafe-save-folder"),
            "MapSafe Test Save Folder"
        )
        val encryptedUri = MapSafeTestDocumentProvider.uri(context, "tier1-ui.geojson.pgp")
        val encryptedFile = MapSafeTestDocumentProvider.file(context, "tier1-ui.geojson.pgp")
        val decryptedFile = MapSafeTestDocumentProvider.file(context, "tier1-ui.geojson")
        val activeNetwork = BlockchainNetworkProfileRepository(context).load().activeProfile
        val activeNetworkLabel = "${activeNetwork.displayName} - ${activeNetwork.environment.displayName}"
        encryptedFile.delete()
        decryptedFile.delete()

        try {
            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    sourceFile = source,
                    sourceDisplayName = "tier1-ui.geojson"
                )
            ).use {
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-encrypt-protect")
                onView(withText("Original Dataset")).check(matches(isDisplayed()))
                onView(withText("Choose")).check(matches(isDisplayed()))
                onView(withText("Choose another file")).check(doesNotExist())
                onView(withText(containsString("Select Recipients & Encrypt"))).perform(scrollTo(), click())
                onView(withText("Select recipients")).check(matches(isDisplayed()))
                onView(withText(containsString("Add recipient public key"))).check(matches(isDisplayed()))
                onView(withText(containsString("only your identity, only other recipients, or both")))
                    .check(matches(isDisplayed()))
                MapSafeDeviceTestSupport.screenshot(context, "paper-12-recipient-selection")
                onView(withText("Continue")).perform(click())
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())

                waitForText("Dataset protected")
                onView(withText("Reselect Recipients & Encrypt")).check(matches(isDisplayed()))
                onView(withText("Create encryption identity")).check(matches(not(isDisplayed())))
                onView(withText("Include me")).check(matches(isChecked()))
                onView(withText("Next: Notarise")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("Stop")).check(matches(isDisplayed()))
                onView(withText("Saved: tier1-ui.geojson.pgp")).check(matches(isDisplayed()))
                onView(withText(containsString("MapSafe Test Save Folder/"))).check(doesNotExist())
                MapSafeDeviceTestSupport.waitUntil("encrypted OpenPGP test document", 60_000L) {
                    encryptedFile.isFile && encryptedFile.length() > 0L
                }
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-encryption-success")
                val notarisationHash = HashUtils.sha256(encryptedFile)
                onView(withText("Next: Notarise")).perform(scrollTo(), click())
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(
                    "The encrypted package did not reach the Notarise screen.",
                    device.wait(Until.hasObject(By.text("Notarise on Blockchain")), 20_000L)
                )
                onView(withText("tier1-ui.geojson.pgp")).check(matches(isDisplayed()))
                assertTrue(
                    "The Notarise screen did not calculate the encrypted package hash.",
                    device.wait(Until.hasObject(By.text(notarisationHash)), 20_000L)
                )
                onView(withText(activeNetworkLabel)).check(matches(isDisplayed()))
                onView(withText(containsString("encrypted file picker"))).check(doesNotExist())
                onView(withText("Configure blockchain network")).check(doesNotExist())
                onView(withText("Privacy boundary")).check(doesNotExist())
                MapSafeDeviceTestSupport.production(
                    "UI ENCRYPT",
                    "secure activity created a signed AES-256-GCM OpenPGP document and handed it to notarisation"
                )
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-notarisation-handoff")
            }

            val encryptedSha256 = HashUtils.sha256(encryptedFile)
            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    decrypt = true,
                    verifiedSourceUri = encryptedUri,
                    verifiedSourceDisplayName = "tier1-ui.geojson.pgp",
                    verifiedSourceSha256 = encryptedSha256,
                    verificationNetworkName = "Test verification network",
                    verificationTransactionHash = "0x" + "12".repeat(32)
                )
            ).use {
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-decrypt-access")
                onView(withContentDescription("Access progress: 2 of 3, Decrypt"))
                    .check(matches(isDisplayed()))
                onView(withText("Blockchain-Verified File"))
                    .perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText(encryptedSha256)).check(doesNotExist())
                onView(withText("Choose a different file")).check(doesNotExist())
                onView(withText("Decrypt Verified File")).perform(scrollTo(), click())
                onView(withText("Unlock private key")).check(matches(isDisplayed()))
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())

                MapSafeDeviceTestSupport.waitUntil("decrypted GeoJSON test document", 60_000L) {
                    decryptedFile.isFile && decryptedFile.length() > 0L
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(
                    "The verified-GeoJSON import prompt did not appear.",
                    device.wait(Until.hasObject(By.text("Import decrypted layer?")), 20_000L)
                )
                assertTrue(device.hasObject(By.textContains("passed the OpenPGP integrity check")))
                MapSafeDeviceTestSupport.screenshot(context, "paper-13-decryption-integrity-prompt")
                onView(withText("Import and continue")).perform(click())
                assertTrue(
                    "The access continuation was not offered after the verified import.",
                    device.wait(Until.hasObject(By.textContains("Dataset ready to access")), 30_000L)
                )
                onView(withText("Decrypt Verified File")).check(doesNotExist())
                onView(withText("Encryption")).check(doesNotExist())
                onView(withText("Protected by")).check(doesNotExist())
                onView(withText("Integrity")).check(doesNotExist())
                onView(withText("Saved: tier1-ui.geojson")).check(matches(isDisplayed()))
                onView(withText(containsString("MapSafe Test Save Folder/"))).check(doesNotExist())
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-decryption-success")
                assertArrayEquals(sourceBytes, decryptedFile.readBytes())
                MapSafeDeviceTestSupport.prepareMainActivity(context)
                onView(withText("Next: Access")).perform(scrollTo(), click())
                onView(withText("Access Datasets")).check(doesNotExist())
                MapSafeDeviceTestSupport.production(
                    "UI DECRYPT + VERIFY",
                    "integrity and signature checks passed; recovered bytes match and the imported layer returned directly to the map"
                )
                MapSafeDeviceTestSupport.production("SECURE ENTRY", "FLAG_SECURE protected only passphrase entry")
            }

            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    decrypt = true,
                    verifiedSourceUri = encryptedUri,
                    verifiedSourceDisplayName = "tier1-ui.geojson.pgp",
                    verifiedSourceSha256 = "00".repeat(32),
                    verificationNetworkName = "Test verification network",
                    verificationTransactionHash = "0x" + "34".repeat(32)
                )
            ).use {
                onView(withText("Decrypt Verified File")).perform(scrollTo(), click())
                onView(withText("Verified file changed")).check(matches(isDisplayed()))
                onView(withHint("Recovery passphrase")).check(doesNotExist())
                MapSafeDeviceTestSupport.production(
                    "VERIFY -> DECRYPT REJECTION",
                    "a hash-changed encrypted document was blocked before private-key unlock"
                )
                onView(withText("Return to Verify")).perform(click())
            }
        } finally {
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    private fun waitForText(text: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("Timed out waiting for '$text'", device.wait(Until.hasObject(By.textContains(text)), 60_000L))
        onView(withText(containsString(text))).check(matches(isDisplayed()))
    }

    companion object {
        private const val SAMPLE_GEOJSON =
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"id":1},"geometry":{"type":"Point","coordinates":[178.4419,-18.1416]}}]}"""
    }
}
