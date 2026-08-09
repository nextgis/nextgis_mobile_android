package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.R
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.mapsafe.ui.DonutMaskingResultDialog
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises every top-level MapSafe Back route, including the Android system Back action. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeNavigationUiDeviceTest {

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
            onView(withContentDescription("Back")).check(matches(isDisplayed())).perform(click())
            assertMainChooser()

            onView(withText("Safeguard Features")).perform(click())
            onView(withText("Anonymise")).perform(click())
            pressBack()
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            assertMainChooser()

            onView(withText("Protect & Share (guided workflow)")).perform(click())
            onView(withText("Configure Halo Masking")).perform(click())
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Protect & Share")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            assertMainChooser()

            onView(withText("Protect & Share (guided workflow)")).perform(click())
            onView(withText("Configure Hexagonal Binning")).perform(click())
            pressBack()
            onView(withText("Protect & Share")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            assertMainChooser()

            onView(withText("Safeguard Features")).perform(click())
            onView(withText("Encrypt")).perform(click())
            onView(withText("Back")).perform(click())
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            onView(withText("Encrypt")).perform(click())
            onView(withText("Encrypt another file")).perform(click())
            onView(withText("Encrypt & Protect")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            onView(withText("Blockchain Notarisation")).perform(click())
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
            assertMainChooser()

            onView(withText("Access Features")).perform(click())
            onView(withText("Decrypt")).perform(click())
            onView(withText("Back")).perform(click())
            onView(withText("Access Features")).check(matches(isDisplayed()))
            onView(withText("Decrypt")).perform(click())
            onView(withText("Open")).perform(click())
            onView(withText("Decrypt & Access")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Access Features")).check(matches(isDisplayed()))
            onView(withText("Check Records")).perform(click())
            pressBack()
            onView(withText("Access Features")).check(matches(isDisplayed()))
            onView(withText("Back")).perform(click())
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
                    evaluatedPoints = 30,
                    continueToEncryption = false
                ).show(activity.supportFragmentManager, DonutMaskingResultDialog.TAG)
            }
            onView(withText("Halo Masking Applied")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Halo Masking")).check(matches(isDisplayed()))
            onView(withText(containsString("Remasking from the precise source")))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Anonymise")).check(matches(isDisplayed()))
        }
    }

    private fun openMapSafe(context: MainApplication) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(context.getString(R.string.mapsafe_menu_title))).perform(click())
        assertMainChooser()
    }

    private fun assertMainChooser() {
        onView(withText("Choose a workflow")).check(matches(isDisplayed()))
        onView(withContentDescription("MapSafe full logo")).check(matches(isDisplayed()))
    }
}
