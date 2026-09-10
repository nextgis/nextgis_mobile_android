package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
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
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafePerformanceLogRepository
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import com.nextgis.mobile.mapsafe.ui.MapSafeOpenPgpActivity
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolates production OpenPGP timing capture from later Notarise/Access navigation. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeProductionCryptoTimingDeviceTest {

    @Test
    fun successfulEncryptionAndDecryptionAppendTheirProductionTimings() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val passphrase = "MapSafe production timing test passphrase"
        val material = OpenPgpKeyGenerator.generate(
            "Performance UI <performance@example.test>",
            passphrase.toCharArray(),
            rsaBits = 2048
        )
        OpenPgpKeyRepository(context).saveLocalIdentity(material)

        val sourceBytes = SAMPLE_GEOJSON.toByteArray(Charsets.UTF_8)
        val sourceDirectory = File(context.cacheDir, "mapsafe/performance-ui").apply {
            require(exists() || mkdirs())
        }
        val source = File(sourceDirectory, "performance-ui.geojson").apply { writeBytes(sourceBytes) }
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "performance-ui-save-folder"),
            "MapSafe Test Save Folder"
        )
        val encryptedUri = MapSafeTestDocumentProvider.uri(context, "performance-ui.geojson.pgp")
        val encryptedFile = MapSafeTestDocumentProvider.file(context, "performance-ui.geojson.pgp")
        val decryptedFile = MapSafeTestDocumentProvider.file(context, "performance-ui.geojson")
        val performanceFile = MapSafeTestDocumentProvider.file(
            context,
            MapSafePerformanceLogRepository.FILE_NAME
        )
        encryptedFile.delete()
        decryptedFile.delete()
        performanceFile.delete()

        try {
            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    sourceFile = source,
                    sourceDisplayName = source.name,
                    sourcePointCount = 1
                )
            ).use {
                onView(withText(containsString("Select Recipients & Encrypt"))).perform(scrollTo(), click())
                onView(withText("Continue")).perform(click())
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())
                waitForText("Dataset protected")
                waitForLog(performanceFile, "openpgp_encrypt_signed")
            }

            val encryptedHash = HashUtils.sha256(encryptedFile)
            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    decrypt = true,
                    verifiedSourceUri = encryptedUri,
                    verifiedSourceDisplayName = encryptedFile.name,
                    verifiedSourceSha256 = encryptedHash
                )
            ).use {
                onView(withText("Decrypt Verified File")).perform(scrollTo(), click())
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())
                MapSafeDeviceTestSupport.waitUntil("decrypted timing test document", 60_000L) {
                    decryptedFile.isFile && decryptedFile.length() > 0L
                }
                waitForLog(performanceFile, "openpgp_decrypt_verify")
            }

            assertArrayEquals(sourceBytes, decryptedFile.readBytes())
            val csv = performanceFile.readText()
            assertTrue(csv.contains("openpgp_encrypt_signed,performance-ui.geojson,1,"))
            assertTrue(csv.contains("openpgp_decrypt_verify,performance-ui.geojson,"))
            assertTrue(csv.lineSequence().count { it.startsWith("record_id,timestamp_utc") } == 1)
        } finally {
            encryptedFile.delete()
            decryptedFile.delete()
            performanceFile.delete()
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    private fun waitForText(text: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("Timed out waiting for '$text'", device.wait(Until.hasObject(By.textContains(text)), 60_000L))
    }

    private fun waitForLog(file: File, operation: String) {
        MapSafeDeviceTestSupport.waitUntil("$operation timing row", 20_000L) {
            file.takeIf(File::isFile)?.readText()?.contains(operation) == true
        }
    }

    companion object {
        private const val SAMPLE_GEOJSON =
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"id":1,"notes":"timing fixture"},"geometry":{"type":"Point","coordinates":[178.4419,-18.1416]}}]}"""
    }
}
