package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
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
import com.nextgis.mobile.mapsafe.ui.MapSafeIdentityActivity
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures the primary production MapSafe screens used as figures in the paper. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafePaperScreensDeviceTest {

    @Test
    fun captureWorkflowChooserScreens() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
                var ready = false
                scenario.onActivity { activity -> ready = activity.mapFragment != null }
                ready
            }

            openMapSafe(context)
            MapSafeDeviceTestSupport.screenshot(context, "paper-01-mapsafe-workflow-chooser")

            onView(withText("Protect & Share (guided workflow)")).perform(click())
            onView(withText("Protect & Share")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-02-guided-protect-share")
            onView(withText("Back")).perform(click())

            onView(withText("Safeguard Features")).perform(click())
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-03-safeguard-features")

            onView(withText("Anonymise")).perform(click())
            MapSafeDeviceTestSupport.screenshot(context, "paper-04-anonymise-options")
            onView(withText("Back")).perform(click())

            onView(withText("Encrypt")).perform(click())
            onView(withText("Encrypt & Protect")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-05-encrypt-source-chooser")
            onView(withText("Back")).perform(click())

            onView(withText("Blockchain Notarisation")).perform(click())
            MapSafeDeviceTestSupport.screenshot(context, "paper-06-notarisation-placeholder")
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Back")).perform(click())

            onView(withText("Access Features")).perform(click())
            onView(withText("Access Features")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-07-access-features")

            onView(withText("Decrypt")).perform(click())
            onView(withText("OpenPGP Decryption")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-08-decrypt-package-chooser")
            onView(withText("Back")).perform(click())

            onView(withText("Check Records")).perform(click())
            onView(withText("Verification")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-09-verification-placeholder")
        }
    }

    @Test
    fun captureIdentityCreationAndSuccessScreens() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val passphrase = "MapSafe-Paper-2026!"
        ActivityScenario.launch(MapSafeIdentityActivity::class.java).use {
            onView(withText("Create Encryption Identity")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "paper-10-identity-creation")

            onView(withHint("Name")).perform(replaceText("Research Participant"))
            onView(withHint("Organisation / community")).perform(replaceText("MapSafe Study"))
            onView(withHint("Email (optional)")).perform(replaceText("participant@example.invalid"))
            onView(withHint("Passphrase")).perform(scrollTo(), replaceText(passphrase))
            onView(withHint("Confirm passphrase")).perform(scrollTo(), replaceText(passphrase), closeSoftKeyboard())
            onView(withText(containsString("Generate Key Pair"))).perform(scrollTo(), click())

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(
                "Identity success screen did not appear.",
                device.wait(Until.hasObject(By.text("Key Pair Created Successfully")), 90_000L)
            )
            MapSafeDeviceTestSupport.screenshot(context, "paper-11-identity-success")
        }
    }

    private fun openMapSafe(context: MainApplication) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(context.getString(R.string.mapsafe_menu_title))).perform(click())
        onView(withText("Choose a workflow")).check(matches(isDisplayed()))
    }
}
