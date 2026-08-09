package com.nextgis.mobile.mapsafe

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.nextgis.mobile.mapsafe.ui.MapSafeIdentityActivity
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

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
}
