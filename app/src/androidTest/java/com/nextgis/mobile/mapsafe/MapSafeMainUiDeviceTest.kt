package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.R
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
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
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "mapsafe-save-folder"),
            "MapSafe Test Save Folder"
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            MapSafeDeviceTestSupport.waitUntil("MainActivity map fragment") {
                var ready = false
                scenario.onActivity { activity -> ready = activity.mapFragment != null }
                ready
            }

            val beforeSample = layerNames(context)
            openMapSafe(context)
            onView(withText("Access Features")).perform(click())
            onView(withText("Access Features")).check(matches(isDisplayed()))
            onView(withText("Select a dataset first")).check(doesNotExist())
            onView(withText("Back")).perform(click())

            onView(withText("Safeguard Features")).perform(click())
            onView(withText("Select a dataset first")).check(matches(isDisplayed()))
            onView(withText(containsString("Safeguard Features needs an active map dataset")))
                .check(matches(isDisplayed()))
            onView(withText("Load sample dataset")).perform(click())
            val sampleName = waitForNewLayer(context, beforeSample, "MapSafe sample points - Suva")
            assertEquals(sampleName, selectedLayerName(scenario))
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            onView(withText("Anonymise")).perform(click())
            onView(withContentDescription("Safeguard progress: 1 of 3, Anonymise"))
                .check(matches(isDisplayed()))
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
            onView(withContentDescription("Safeguard progress: 1 of 3, Anonymise"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withText(startsWith("Min:"))).check(matches(isDisplayed()))
            onView(withText(startsWith("Max:"))).check(matches(isDisplayed()))
            onView(withContentDescription(containsString("Masking distance range")))
                .check(matches(isDisplayed()))
            onView(withText(containsString("Privacy Rating"))).check(doesNotExist())
            onView(withContentDescription("Halo masking enabled")).check(doesNotExist())
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
            onView(withContentDescription("Safeguard progress: 1 of 3, Anonymise"))
                .check(matches(isDisplayed()))
            onView(withText("Inverted Spruill Privacy Score")).check(matches(isDisplayed()))
            onView(withText("Remask")).check(matches(isDisplayed()))
            onView(withText("Save Layer")).check(matches(isDisplayed()))
            val savedDonutFile = MapSafeTestDocumentProvider.file(context, "$firstDonutName.geojson")
            savedDonutFile.delete()
            onView(withText("Save Layer")).perform(click())
            MapSafeDeviceTestSupport.waitUntil("saved masked layer", 60_000L) {
                savedDonutFile.isFile && savedDonutFile.length() > 0L
            }
            onView(withText("Saved: $firstDonutName.geojson")).check(matches(isDisplayed()))
            onView(withText(containsString("MapSafe Test Save Folder/"))).check(doesNotExist())
            onView(withText("Next: Encrypt")).check(matches(isDisplayed()))
            onView(withText("Stop")).check(matches(isDisplayed()))
            onView(withText("Collapse results  ▲")).perform(click())
            onView(withText("Expand results  ▼")).check(matches(isDisplayed()))
            onView(withText("Expand results  ▼")).perform(click())
            onView(withText("Inverted Spruill Privacy Score")).check(matches(isDisplayed()))
            onView(withText("How this score was calculated")).check(doesNotExist())
            onView(withText("Masking result")).check(doesNotExist())
            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-donut-spruill-result")
            MapSafeDeviceTestSupport.production(
                "UI DONUT + SPRUILL",
                "dialog applied defaults; $firstDonutName was created and shows the inverted Spruill score"
            )

            val beforeRemask = layerNames(context)
            onView(withText("Remask")).perform(click())
            onView(withText(startsWith("Min: 100 m"))).check(matches(isDisplayed()))
            onView(withText(startsWith("Max: 2,000 m"))).check(matches(isDisplayed()))
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
            onView(withText("Next: Encrypt")).perform(click())
            assertTrue(
                "The masked result did not reach encryption.",
                device.wait(Until.hasObject(By.text("Encrypt & Protect")), 30_000L)
            )
            onView(withText("$sampleName.geojson")).check(matches(isDisplayed()))
            onView(withText("Original Dataset")).check(matches(isDisplayed()))
            onView(withText("Choose")).check(matches(isDisplayed()))
            onView(withText("Choose another file")).check(doesNotExist())
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.production(
                "UI REMASK",
                "$donutName was regenerated from $sampleName while the precise source was preloaded for encryption"
            )

            val beforeHexbin = layerNames(context)
            onView(withText("Anonymise")).perform(click())
            onView(withText("Configure Halo Masking")).check(matches(isDisplayed()))
            onView(withText("Configure Hexagonal Binning")).perform(click())
            onView(withText("Hexagonal Binning")).check(matches(isDisplayed()))
            onView(withContentDescription("Safeguard progress: 1 of 3, Anonymise"))
                .check(matches(isDisplayed()))
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withText(startsWith("Resolution 8"))).check(matches(isDisplayed()))
            onView(withText(containsString("Privacy Rating"))).check(doesNotExist())
            onView(withText("Spatial aggregation")).check(doesNotExist())
            onView(withContentDescription("Hexagonal binning enabled")).check(doesNotExist())
            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-hexbin-dialog")
            onView(withText("Apply Hexagonal Binning")).perform(click())
            val hexbinName = waitForNewLayer(context, beforeHexbin, donutName + "_hexbin")
            assertNotNull(context.map.getLayerByName(hexbinName))
            assertEquals(
                "The aggregated output must become the active layer",
                hexbinName,
                selectedLayerName(scenario)
            )
            onView(withText("Hexagonal Binning Applied")).check(matches(isDisplayed()))
            onView(withText("Bin Again")).check(matches(isDisplayed()))
            onView(withText("Save Layer")).check(matches(isDisplayed()))
            val savedHexbinFile = MapSafeTestDocumentProvider.file(context, "$hexbinName.geojson")
            savedHexbinFile.delete()
            onView(withText("Save Layer")).perform(click())
            MapSafeDeviceTestSupport.waitUntil("saved hexagonal layer", 60_000L) {
                savedHexbinFile.isFile && savedHexbinFile.length() > 0L
            }
            onView(withText("Saved: $hexbinName.geojson")).check(matches(isDisplayed()))
            onView(withText(containsString("MapSafe Test Save Folder/"))).check(doesNotExist())
            onView(withText("Next: Encrypt")).check(matches(isDisplayed()))
            onView(withText("Stop")).check(matches(isDisplayed()))
            onView(withText("Collapse results  ▲")).perform(click())
            onView(withText("Expand results  ▼")).check(matches(isDisplayed()))
            onView(withText("Expand results  ▼")).perform(click())
            onView(withText("Aggregated result")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.production("UI HEXBIN", "dialog created $hexbinName")

            MapSafeDeviceTestSupport.screenshot(context, "tier1-real-mapsafe-map")
            onView(withText("Next: Encrypt")).perform(click())
            assertTrue(
                "The hexbin result did not reach encryption.",
                device.wait(Until.hasObject(By.text("Encrypt & Protect")), 30_000L)
            )
            onView(withText("$sampleName.geojson")).check(matches(isDisplayed()))
            onView(withText("Original Dataset")).check(matches(isDisplayed()))
            onView(withText("Choose")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withText("Safeguard Features")).check(matches(isDisplayed()))
            onView(withText("Blockchain Notarisation")).perform(click())
            onView(withText("Notarise on Blockchain")).check(matches(isDisplayed()))
            onView(withContentDescription("Safeguard progress: 3 of 3, Notarise"))
                .check(matches(isDisplayed()))
            onView(withText("No encrypted package selected")).check(matches(isDisplayed()))
            onView(withText(containsString("encrypted file picker"))).check(doesNotExist())
            onView(withText("Configure blockchain network")).check(doesNotExist())
            onView(withText("Privacy boundary")).check(doesNotExist())
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "mapsafe-notarisation-unavailable")
        } finally {
            // MapSafe's encryption hand-off can replace the original MainActivity.
            // ActivityScenario 1.7 may then throw while closing its stale reference,
            // even though the exercised workflow and all assertions completed.
            runCatching { scenario.close() }
            MapSafeSaveFolderRepository.clear(context)
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
        onView(withText("Configure Halo Masking")).check(matches(isDisplayed()))
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
