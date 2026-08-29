package com.nextgis.mobile.mapsafe

import android.content.Context
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
import com.nextgis.mobile.mapsafe.ui.MapSafeIdentityActivity
import com.nextgis.mobile.mapsafe.ui.MapSafeOpenPgpActivity
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Visual guard for the dedicated MapSafe identity-creation screen. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeIdentityUiDeviceTest {
    @Test
    fun identityJourneyUsesTheMapSafeSafeguardDesign() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MapSafeIdentityActivity::class.java).use {
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withText("Create Encryption Identity")).check(matches(isDisplayed()))
            onView(withHint("Name")).check(matches(isDisplayed()))
            onView(withHint("Organisation / community")).check(matches(isDisplayed()))
            onView(withText(containsString("Your keys stay on this device"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("🔑  Generate Key Pair")).perform(scrollTo()).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "mapsafe-create-encryption-identity")
        }
    }

    @Test
    fun newIdentityReturnsToRecipientSelectionAndIsReusedOnNextEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyDirectory = File(context.noBackupFilesDir, "mapsafe/openpgp")
        File(keyDirectory, "local-public.asc").delete()
        File(keyDirectory, "local-secret.pgp.ks").delete()
        val sourceDirectory = File(context.cacheDir, "mapsafe/identity-return").apply { mkdirs() }
        val source = File(sourceDirectory, "identity-return.geojson").apply {
            writeText("""{"type":"FeatureCollection","features":[]}""")
        }
        val passphrase = "identity return test passphrase"

        ActivityScenario.launch<MapSafeOpenPgpActivity>(
            MapSafeOpenPgpActivity.intent(
                context,
                sourceFile = source,
                sourceDisplayName = source.name
            )
        ).use {
            onView(withText("Create encryption identity")).perform(click())
            onView(withHint("Name")).perform(replaceText("Identity Return Test"))
            onView(withHint("Organisation / community")).perform(replaceText("MapSafe Tests"))
            onView(withHint("Passphrase")).perform(replaceText(passphrase))
            onView(withHint("Confirm passphrase")).perform(replaceText(passphrase))
            closeSoftKeyboard()
            onView(withText("🔑  Generate Key Pair")).perform(scrollTo(), click())

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(
                "The generated identity did not reach its success screen.",
                device.wait(Until.hasObject(By.text("Key Pair Created Successfully")), 90_000L)
            )
            onView(withText("✓  Done")).perform(scrollTo(), click())

            onView(withText("Select recipients")).check(doesNotExist())
            onView(withText("Select Recipients & Encrypt")).check(matches(isDisplayed())).perform(click())
            onView(withText("Select recipients")).check(matches(isDisplayed()))
            onView(withText(containsString("only your identity, only other recipients, or both")))
                .check(matches(isDisplayed()))
            onView(withText(containsString("Add recipient public key"))).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).perform(click())
            onView(withText("Create encryption identity")).check(matches(not(isDisplayed())))
            onView(withText("Include me")).check(matches(isChecked()))
        }

        val secondSource = File(sourceDirectory.apply { mkdirs() }, "identity-reuse.geojson").apply {
            writeText("""{"type":"FeatureCollection","features":[]}""")
        }
        ActivityScenario.launch<MapSafeOpenPgpActivity>(
            MapSafeOpenPgpActivity.intent(
                context,
                sourceFile = secondSource,
                sourceDisplayName = secondSource.name
            )
        ).use {
            onView(withText("Identity Return Test / MapSafe Tests")).check(matches(isDisplayed()))
            onView(withText("Create encryption identity")).check(matches(not(isDisplayed())))
            onView(withText("Include me")).check(matches(isChecked()))
            onView(withText("Select Recipients & Encrypt")).check(matches(isDisplayed()))
        }
    }
}
