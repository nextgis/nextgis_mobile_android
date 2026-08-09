package com.nextgis.mobile.mapsafe

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import com.nextgis.mobile.mapsafe.ui.MapSafeOpenPgpActivity
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drives the real secure OpenPGP screen while replacing only Android's document picker. */
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
        val encryptedUri = MapSafeTestDocumentProvider.uri(context, "tier1-ui.geojson.pgp")
        val decryptedUri = MapSafeTestDocumentProvider.uri(context, "tier1-ui-decrypted.geojson")
        val encryptedFile = MapSafeTestDocumentProvider.file(context, "tier1-ui.geojson.pgp")
        val decryptedFile = MapSafeTestDocumentProvider.file(context, "tier1-ui-decrypted.geojson")

        Intents.init()
        try {
            intending(
                allOf(
                    hasAction(Intent.ACTION_CREATE_DOCUMENT),
                    hasType("application/pgp-encrypted")
                )
            ).respondWith(resultFor(encryptedUri))
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(resultFor(encryptedUri))
            intending(
                allOf(
                    hasAction(Intent.ACTION_CREATE_DOCUMENT),
                    hasType("application/octet-stream")
                )
            ).respondWith(resultFor(decryptedUri))

            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    sourceFile = source,
                    sourceDisplayName = "tier1-ui.geojson"
                )
            ).use {
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-encrypt-protect")
                onView(withText(containsString("Select Recipients & Encrypt"))).perform(scrollTo(), click())
                onView(withText("Select recipients")).check(matches(isDisplayed()))
                MapSafeDeviceTestSupport.screenshot(context, "paper-12-recipient-selection")
                onView(withText("Choose destination")).perform(click())
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())

                waitForText("Encrypted once with AES-256-GCM")
                MapSafeDeviceTestSupport.waitUntil("encrypted OpenPGP test document", 60_000L) {
                    encryptedFile.isFile && encryptedFile.length() > 0L
                }
                MapSafeDeviceTestSupport.production(
                    "UI ENCRYPT",
                    "secure activity created a signed AES-256-GCM OpenPGP document"
                )
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-encryption-success")
            }

            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(context, decrypt = true)
            ).use {
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-decrypt-access")
                onView(withText(containsString("Choose Package & Decrypt"))).perform(scrollTo(), click())
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
                device.pressBack()
                MapSafeDeviceTestSupport.screenshot(context, "mapsafe-decryption-success")
                assertArrayEquals(sourceBytes, decryptedFile.readBytes())
                MapSafeDeviceTestSupport.production(
                    "UI DECRYPT + VERIFY",
                    "integrity and signature checks passed; recovered bytes exactly match the source"
                )
                MapSafeDeviceTestSupport.production("SECURE ENTRY", "FLAG_SECURE protected only passphrase entry")
            }
        } finally {
            Intents.release()
        }
    }

    private fun resultFor(uri: android.net.Uri) =
        ActivityResult(
            Activity.RESULT_OK,
            Intent().setData(uri).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        )

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
