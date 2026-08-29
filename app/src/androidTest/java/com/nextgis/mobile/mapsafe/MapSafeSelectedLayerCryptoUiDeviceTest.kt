package com.nextgis.mobile.mapsafe

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
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
import com.nextgis.mobile.R
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.maplib.map.Layer
import com.nextgis.maplib.map.TrackLayer
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual selected-map-layer bridge into and back out of OpenPGP. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeSelectedLayerCryptoUiDeviceTest {

    @Test
    fun selectedMapLayerEncryptsDecryptsImportsAndReturnsToMap() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val passphrase = "selected layer recovery passphrase"
        val identity = OpenPgpKeyGenerator.generate(
            "Selected Layer UI <selected-layer@example.test>",
            passphrase.toCharArray(),
            rsaBits = 2048
        )
        OpenPgpKeyRepository(context).saveLocalIdentity(identity)
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "mapsafe-save-folder"),
            "MapSafe Test Save Folder"
        )

        Intents.init()
        try {
            ActivityScenario.launch(MainActivity::class.java).also { scenario ->
                waitForMapFragment(scenario)
                val layersBeforeSample = layerNames(context)

                openMapSafe(context)
                onView(withText("Use sample dataset")).perform(click())
                val sourceLayerName = waitForNewLayer(
                    context,
                    layersBeforeSample,
                    "MapSafe sample points - Suva"
                )
                val encryptedUri = MapSafeTestDocumentProvider.uri(context, "mapsafe-save-folder")
                    .buildUpon()
                    .appendPath("$sourceLayerName.geojson.pgp")
                    .build()
                val encryptedFile = MapSafeTestDocumentProvider.file(
                    context,
                    "$sourceLayerName.geojson.pgp"
                )
                val decryptedFile = MapSafeTestDocumentProvider.file(
                    context,
                    "$sourceLayerName.geojson"
                )
                encryptedFile.delete()
                decryptedFile.delete()
                assertEquals(sourceLayerName, selectedLayerName(scenario))
                onView(withText("Back")).perform(click())
                onView(withText("Encrypt")).perform(click())
                onView(withText("Encrypt selected map layer")).perform(click())

                waitForText("Encrypt & Protect")
                onView(withText("Original Dataset")).check(matches(isDisplayed()))
                onView(withText("$sourceLayerName.geojson")).check(matches(isDisplayed()))
                onView(withText("Choose")).check(matches(isDisplayed()))
                onView(withText("Choose another file")).check(doesNotExist())
                onView(withText("Identity")).check(matches(isDisplayed()))
                onView(withText("Recipients")).check(matches(isDisplayed()))
                onView(withText("Access Protection")).check(doesNotExist())
                onView(withText("Key Backup & Recovery")).check(doesNotExist())
                onView(withText(containsString("Select Recipients & Encrypt")))
                    .check(matches(isDisplayed()))
                    .perform(click())
                onView(withText("Select recipients")).check(matches(isDisplayed()))
                onView(withText(containsString("Add recipient public key"))).check(matches(isDisplayed()))
                onView(withText("Continue")).perform(click())
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())

                waitForText("Dataset protected")
                onView(withText("Reselect Recipients & Encrypt")).check(matches(isDisplayed()))
                onView(withText("Create encryption identity")).check(matches(not(isDisplayed())))
                onView(withText("Reselect Recipients & Encrypt")).perform(click())
                onView(withText("Select recipients")).check(matches(isDisplayed()))
                onView(withText("Continue")).check(matches(isDisplayed()))
                onView(withText(android.R.string.cancel)).perform(click())
                onView(withText("Next: Notarise")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("Stop")).check(matches(isDisplayed()))
                onView(withText("Saved: $sourceLayerName.geojson.pgp")).check(matches(isDisplayed()))
                onView(withText(containsString("MapSafe Test Save Folder/"))).check(doesNotExist())
                MapSafeDeviceTestSupport.waitUntil("selected-layer encrypted package", 60_000L) {
                    encryptedFile.isFile && encryptedFile.length() > 0L
                }
                MapSafeDeviceTestSupport.production(
                    "SELECTED LAYER -> ENCRYPT",
                    "$sourceLayerName was exported by the live map and encrypted through the user interface"
                )

                onView(withContentDescription("Back")).perform(click())
                onView(withText("Safeguard Features")).check(matches(isDisplayed()))
                onView(withText("Back")).perform(click())
                onView(withText("Access Features")).perform(scrollTo(), click())
                onView(withText("Decrypt")).perform(click())
                onView(withText("Open")).perform(click())
                waitForText("Decrypt & Access")
                intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(resultFor(encryptedUri))
                onView(withText("Choose Package & Decrypt")).perform(scrollTo(), click())
                onView(withHint("Recovery passphrase")).perform(replaceText(passphrase))
                closeSoftKeyboard()
                onView(withText("Continue")).perform(click())

                MapSafeDeviceTestSupport.waitUntil("selected-layer decrypted GeoJSON", 60_000L) {
                    decryptedFile.isFile && decryptedFile.length() > 0L
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(
                    "The selected-layer import prompt did not appear.",
                    device.wait(Until.hasObject(By.text("Import decrypted layer?")), 20_000L)
                )
                val layersBeforeImport = layerNames(context)
                onView(withText("Import and continue")).perform(click())
                assertTrue(
                    "The selected-layer import did not complete.",
                    device.wait(Until.hasObject(By.textContains("Dataset ready to access")), 30_000L)
                )
                onView(withText("Decrypt Verified File")).check(doesNotExist())
                onView(withText("Choose Package & Decrypt")).check(doesNotExist())
                onView(withText("Encryption")).check(doesNotExist())
                onView(withText("Protected by")).check(doesNotExist())
                onView(withText("Integrity")).check(doesNotExist())
                onView(withText("Saved: $sourceLayerName.geojson")).check(matches(isDisplayed()))
                onView(withText(containsString("MapSafe Test Save Folder/"))).check(doesNotExist())
                val importedLayerName = waitForNewLayer(
                    context,
                    layersBeforeImport,
                    "$sourceLayerName (decrypted)"
                )
                val recovered = JSONObject(decryptedFile.readText())
                assertEquals(sourceLayerName, recovered.getString("name"))
                assertEquals(30, recovered.getJSONArray("features").length())
                assertNotNull(context.map.getLayerByName(importedLayerName))

                onView(withText("Next: Access")).perform(scrollTo(), click())
                MapSafeDeviceTestSupport.waitUntil("imported selected layer to return to the map") {
                    selectedLayerName(scenario) == importedLayerName
                }
                assertEquals(setOf(importedLayerName), visibleDataLayerNames(context))
                onView(withText("Access Datasets")).check(doesNotExist())
                MapSafeDeviceTestSupport.production(
                    "SELECTED LAYER ROUND TRIP",
                    "30 exported features were encrypted, decrypted, imported, selected, and returned to the live map"
                )
                MapSafeDeviceTestSupport.screenshot(context, "selected-layer-encrypt-decrypt-import")
            }
        } finally {
            Intents.release()
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    private fun openMapSafe(context: MainApplication) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(context.getString(R.string.mapsafe_menu_title))).perform(click())
        onView(withText("MapSafe")).check(matches(isDisplayed()))
    }

    private fun waitForMapFragment(scenario: ActivityScenario<MainActivity>) {
        MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
            var ready = false
            scenario.onActivity { activity -> ready = activity.mapFragment != null }
            ready
        }
    }

    private fun waitForText(text: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Timed out waiting for '$text'",
            device.wait(Until.hasObject(By.textContains(text)), 60_000L)
        )
        onView(withText(containsString(text))).check(matches(isDisplayed()))
    }

    private fun waitForNewLayer(
        context: MainApplication,
        before: Set<String>,
        namePrefix: String
    ): String {
        var result: String? = null
        MapSafeDeviceTestSupport.waitUntil("new $namePrefix layer", 60_000L) {
            result = (layerNames(context) - before).firstOrNull { it.startsWith(namePrefix) }
            result != null
        }
        return requireNotNull(result)
    }

    private fun selectedLayerName(scenario: ActivityScenario<MainActivity>): String? {
        var selected: String? = null
        scenario.onActivity { activity -> selected = activity.mapFragment?.selectedLayer?.name }
        return selected
    }

    private fun layerNames(context: MainApplication): Set<String> =
        (0 until context.map.layerCount).map { context.map.getLayer(it).name }.toSet()

    private fun visibleDataLayerNames(context: MainApplication): Set<String> =
        (0 until context.map.layerCount)
            .map(context.map::getLayer)
            .filter { it is VectorLayer || it is TrackLayer }
            .map { it as Layer }
            .filter { it.isVisible }
            .mapTo(mutableSetOf()) { it.name }

    private fun resultFor(uri: android.net.Uri) =
        ActivityResult(
            Activity.RESULT_OK,
            Intent().setData(uri).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        )
}
