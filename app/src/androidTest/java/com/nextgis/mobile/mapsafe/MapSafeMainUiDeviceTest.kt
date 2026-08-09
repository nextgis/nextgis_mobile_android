package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.R
import com.nextgis.mobile.activity.MainActivity
import org.hamcrest.Matchers.startsWith
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real MapSafe menu/dialog path instead of calling workflows directly. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeMainUiDeviceTest {

    @Test
    fun mapSafeMenusLoadMaskAndHexbinTheSelectedLayer() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        MapSafeDeviceTestSupport.prepareMainActivity(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
                var ready = false
                scenario.onActivity { activity -> ready = activity.mapFragment != null }
                ready
            }

            val beforeSample = layerNames(context)
            openMapSafe(context)
            onView(withText("Use sample dataset")).perform(click())
            val sampleName = waitForNewLayer(context, beforeSample, "MapSafe sample points - Suva")
            assertEquals(sampleName, selectedLayerName(scenario))
            onView(withText("Anonymise")).check(matches(isDisplayed()))
            onView(withText("Use sample dataset")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "anonymise-sample-dataset-option")
            val sampleLayer = context.map.getLayerByName(sampleName) as VectorLayer
            assertEquals(GeoConstants.GTPoint, sampleLayer.geometryType)
            assertEquals(30, sampleLayer.query(null).size)
            MapSafeDeviceTestSupport.production(
                "UI SAMPLE DATASET",
                "main-menu action created and selected compatible 30-point vector layer $sampleName, then opened Anonymise"
            )

            val beforeDonut = layerNames(context)
            onView(withText("Configure Halo Masking")).perform(click())
            onView(withText("Halo Masking")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withText(startsWith("Minimum distance:"))).check(matches(isDisplayed()))
            onView(withText(startsWith("Maximum distance:"))).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-donut-dialog")
            onView(withText("Apply Halo Masking")).perform(click())
            val firstDonutName = waitForNewLayer(context, beforeDonut, sampleName + "_masked")
            assertNotNull(context.map.getLayerByName(firstDonutName))
            assertEquals(
                "The protected output, not the precise source, must become the active layer",
                firstDonutName,
                selectedLayerName(scenario)
            )
            onView(withText("Halo Masking Applied")).check(matches(isDisplayed()))
            onView(withText("Inverted Spruill Privacy Score")).check(matches(isDisplayed()))
            onView(withText("Remask")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-donut-spruill-result")
            MapSafeDeviceTestSupport.production(
                "UI DONUT + SPRUILL",
                "dialog applied defaults; $firstDonutName was created and shows the inverted Spruill score"
            )

            val beforeRemask = layerNames(context)
            onView(withText("Remask")).perform(click())
            onView(withText(containsString("Remasking from the precise source")))
                .check(matches(isDisplayed()))
            onView(withText(startsWith("Minimum distance: 100 m"))).check(matches(isDisplayed()))
            onView(withText(startsWith("Maximum distance: 2,000 m"))).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-donut-remask-dialog")
            onView(withText("Apply Halo Masking")).perform(click())
            val donutName = waitForNewLayer(context, beforeRemask, sampleName + "_masked")
            assertTrue("Remasking must create a new candidate", donutName != firstDonutName)
            assertEquals(
                "A retry must be named from the precise source, proving it was not chained from the first mask",
                sampleName + "_masked_2",
                donutName
            )
            assertEquals(
                "Remasking must select the new candidate generated from the precise source",
                donutName,
                selectedLayerName(scenario)
            )
            onView(withText("Inverted Spruill Privacy Score")).check(matches(isDisplayed()))
            onView(withText("Use This Result")).perform(click())
            MapSafeDeviceTestSupport.production(
                "UI REMASK",
                "$donutName was regenerated from $sampleName, not from $firstDonutName"
            )

            val beforeHexbin = layerNames(context)
            openAnonymise(context)
            onView(withText("Configure Hexagonal Binning")).perform(click())
            onView(withText("Hexagonal Binning")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withText(startsWith("Resolution 8"))).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-hexbin-dialog")
            onView(withText("Apply Hexagonal Binning")).perform(click())
            val hexbinName = waitForNewLayer(context, beforeHexbin, donutName + "_hexbin")
            assertNotNull(context.map.getLayerByName(hexbinName))
            assertEquals(
                "The aggregated output must become the active layer",
                hexbinName,
                selectedLayerName(scenario)
            )
            MapSafeDeviceTestSupport.production("UI HEXBIN", "dialog created $hexbinName")

            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-mapsafe-map")

            openMapSafe(context)
            onView(withText("Safeguard Features")).perform(click())
            onView(withText("Blockchain Notarisation")).perform(click())
            onView(withText("Notarise on Blockchain")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "mapsafe-notarisation-unavailable")
        }
    }

    private fun openMapSafe(context: MainApplication) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(context.getString(R.string.mapsafe_menu_title))).perform(click())
        onView(withText("MapSafe")).check(matches(isDisplayed()))
        onView(withContentDescription("MapSafe full logo")).check(matches(isDisplayed()))
        MapSafeDeviceTestSupport.screenshot(context, "mapsafe-main-logo-workflows")
    }

    private fun openAnonymise(context: MainApplication) {
        openMapSafe(context)
        onView(withText("Safeguard Features")).perform(scrollTo(), click())
        onView(withText("Anonymise")).perform(click())
        onView(withText("Anonymise")).check(matches(isDisplayed()))
    }

    private fun waitForNewLayer(
        context: MainApplication,
        before: Set<String>,
        namePrefix: String
    ): String {
        var result: String? = null
        MapSafeDeviceTestSupport.waitUntil("new $namePrefix layer") {
            result = (layerNames(context) - before).firstOrNull { it.startsWith(namePrefix) }
            result != null
        }
        return requireNotNull(result)
    }

    private fun layerNames(context: MainApplication): Set<String> =
        (0 until context.map.layerCount).map { context.map.getLayer(it).name }.toSet()

    private fun selectedLayerName(scenario: ActivityScenario<MainActivity>): String? {
        var name: String? = null
        scenario.onActivity { activity -> name = activity.mapFragment?.selectedLayer?.name }
        assertTrue("A MapSafe sample layer should be selected", name != null)
        return name
    }
}
