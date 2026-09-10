package com.nextgis.mobile.mapsafe

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
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
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.ui.DonutMaskingResultDialog
import com.nextgis.mobile.mapsafe.ui.IntegrityRecordDialog
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises every top-level MapSafe Back route, including the Android system Back action. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeNavigationUiDeviceTest {

    @Test
    fun localHashEnablesDecryptionWithoutRequiringBlockchainComparison() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val documentName = "verification-ready-${System.nanoTime()}.pgp"
        val documentBytes = "MapSafe local hash readiness".toByteArray()
        val saved = MapSafeSaveFolderRepository.save(
            context,
            "application/pgp-encrypted",
            documentName
        ) { uri ->
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(documentBytes) }
                ?: error("The encrypted test package could not be opened.")
        }
        val expectedHash = HashUtils.sha256(documentBytes)
        MapSafeDeviceTestSupport.prepareMainActivity(context)

        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(saved.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            )
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
                    var ready = false
                    scenario.onActivity { activity -> ready = activity.mapFragment != null }
                    ready
                }
                scenario.onActivity { activity ->
                    IntegrityRecordDialog.forAccessFeatures().show(
                        activity.supportFragmentManager,
                        "VerificationReadinessTest"
                    )
                }

                onView(withText("Verification")).check(matches(isDisplayed()))
                onView(withText("Select encrypted file")).perform(click())
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(
                    "The local SHA-256 did not appear after selecting the encrypted file.",
                    device.wait(Until.hasObject(By.text(expectedHash)), 20_000L)
                )
                onView(withText(expectedHash)).check(matches(isDisplayed()))
                onView(withText("Next: Decrypt"))
                    .perform(scrollTo()).check(matches(allOf(isDisplayed(), isEnabled())))
                onView(withText("Retrieve and compare record"))
                    .perform(scrollTo()).check(matches(not(isEnabled())))
                onView(withText("Next: Decrypt")).perform(scrollTo(), click())
                onView(withText("Hash-Checked Encrypted File")).check(matches(isDisplayed()))
            }
        } finally {
            Intents.release()
            context.contentResolver.delete(saved.uri, null, null)
        }
    }

    @Test
    fun everyMapSafeScreenReturnsToItsActualParent() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)

        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
                var ready = false
                scenario.onActivity { activity -> ready = activity.mapFragment != null }
                ready
            }

            openMapSafe(context)
            onView(withText("Security & Sharing")).perform(click())
            onView(withText("Security & Sharing")).check(matches(isDisplayed()))
            onView(withText("Configure blockchain network")).perform(scrollTo(), click())
            onView(withText("Blockchain Network Settings")).check(matches(isDisplayed()))
            onView(withText("<encrypted-package filename>_<64 lowercase hex>"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Read-only Connection Check"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Test RPC and contract"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("does not save settings, connect a wallet, or submit a transaction")))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText(containsString("does not ask for or store a wallet recovery phrase")))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Save and use this network"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Security & Sharing")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).check(matches(isDisplayed())).perform(click())
            assertMainChooser()

            onView(withContentDescription("Open Halo Masking")).perform(click())
            onView(withText("Select a dataset first")).check(matches(isDisplayed()))
            onView(withText("Load sample dataset")).perform(click())
            onView(withContentDescription("MapSafe full logo")).check(matches(isDisplayed()))
            onView(withContentDescription("Open Halo Masking")).perform(click())
            pressBack()
            assertMainChooser()

            onView(withContentDescription("Open Encrypt")).perform(scrollTo(), click())
            onView(withText("Choose Datasets to Encrypt")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            assertMainChooser()
            onView(withContentDescription("Open Encrypt")).perform(scrollTo(), click())
            onView(withText("Choose Another File")).perform(scrollTo(), click())
            onView(withText("Encrypt & Protect")).check(matches(isDisplayed()))
            onView(withContentDescription("Safeguard progress: 2 of 3, Encrypt"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            assertMainChooser()
            onView(withContentDescription("Open Notarise Package")).perform(scrollTo(), click())
            onView(withContentDescription("Back")).perform(click())
            assertMainChooser()

            onView(withText("Access")).perform(click())
            onView(withText("Community Packages")).check(matches(isDisplayed()))
            onView(withText("Verify Encrypted File")).check(matches(isDisplayed()))
            onView(withContentDescription("Open Verify Encrypted File")).perform(click())
            onView(withText("Verification")).check(matches(isDisplayed()))
            onView(withContentDescription("Access progress: 1 of 3, Verify"))
                .check(matches(isDisplayed()))
            onView(withText("Select encrypted file")).check(matches(isDisplayed()))
            onView(withText("Local SHA-256")).check(matches(isDisplayed()))
            onView(withText("No encrypted package selected")).check(matches(isDisplayed()))
            onView(withText("Blockchain Network")).check(doesNotExist())
            onView(withText("Blockchain Transaction")).check(doesNotExist())
            onView(withText("Blockchain Comparison")).check(doesNotExist())
            onView(withText("Blockchain Verification")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withHint(containsString("explorer URL or 0x transaction hash")))
                .check(matches(isDisplayed()))
            onView(withText("Validate transaction reference"))
                .perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Retrieve and compare record"))
                .perform(scrollTo()).check(matches(allOf(isDisplayed(), not(isEnabled()))))
            onView(withText("Next: Decrypt"))
                .perform(scrollTo()).check(matches(allOf(isDisplayed(), not(isEnabled()))))
            onView(withText("Stop")).check(matches(isDisplayed()))
            onView(withText("Continue Securely")).check(doesNotExist())
            onView(withText("Configure blockchain network")).check(doesNotExist())
            pressBack()
            onView(withContentDescription("Access tab, selected")).check(matches(isDisplayed()))
            assertMainChooser()

            MapSafeDeviceTestSupport.production(
                "UI BACK AUDIT",
                "all MapSafe dialog/activity Back controls and system Back routes returned to their expected parent"
            )
        }
    }

    @Test
    fun maskingResultBackReturnsToItsMaskingSettings() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
                var ready = false
                scenario.onActivity { activity -> ready = activity.mapFragment != null }
                ready
            }
            scenario.onActivity { activity ->
                DonutMaskingResultDialog.newInstance(
                    sourceLayerName = "precise-source",
                    outputLayerName = "masked-result",
                    minDistanceMetres = 100.0,
                    maxDistanceMetres = 2_000.0,
                    totalPoints = 30,
                    maskedPoints = 30,
                    averageDistanceMetres = 800.0,
                    disclosureRiskPercent = 20.0,
                    privacyRatingPercent = 80.0,
                    parentNearestCount = 6,
                    evaluatedPoints = 30
                ).show(activity.supportFragmentManager, DonutMaskingResultDialog.TAG)
            }
            onView(withText("Halo Masking Applied")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Halo Masking")).check(matches(isDisplayed()))
            onView(withText(containsString("Min:"))).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withContentDescription("MapSafe full logo")).check(matches(isDisplayed()))
        }
    }

    private fun openMapSafe(context: MainApplication) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(context.getString(R.string.mapsafe_menu_title))).perform(click())
        assertMainChooser()
    }

    private fun assertMainChooser() {
        onView(withContentDescription("MapSafe full logo")).check(matches(isDisplayed()))
        onView(withContentDescription("MapSafe full logo")).check(matches(isDisplayed()))
        onView(withText("Protect & Share (guided workflow)")).check(doesNotExist())
    }

}
