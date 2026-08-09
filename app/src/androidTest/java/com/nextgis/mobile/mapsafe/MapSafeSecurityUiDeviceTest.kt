package com.nextgis.mobile.mapsafe

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.nextgis.mobile.mapsafe.ui.MapSafeSecurityActivity
import org.junit.Test
import org.junit.runner.RunWith

/** Guards against hiding public-key exchange inside the old cryptographic utility screen. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeSecurityUiDeviceTest {
    @Test
    fun publicKeyExchangeJourneyIsVisibleInTheProductionInterface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MapSafeSecurityActivity::class.java).use {
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withContentDescription("MapSafe logo")).check(matches(isDisplayed()))
            onView(withText("1. NextGIS account")).check(matches(isDisplayed()))
            onView(withText("Choose connected account")).check(matches(isDisplayed()))
            onView(withText("2. Trusted group")).check(matches(isDisplayed()))
            onView(withText("Choose one of my groups")).check(matches(isDisplayed()))
            onView(withText("Create MapSafe group")).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "security-sharing-account-group")
            onView(withText("3. Encryption identity")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Create / replace identity")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("4. Group public keys")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Publish my public key")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Download group member keys")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Review changed / new fingerprints")).perform(scrollTo()).check(matches(isDisplayed()))
            MapSafeDeviceTestSupport.screenshot(context, "security-sharing-identity-keys")
            onView(withText("Advanced key backup / import")).perform(scrollTo(), click())
            onView(withText("Encrypt & Protect")).check(matches(isDisplayed()))
            onView(withContentDescription("Back")).perform(click())
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withText("Security & Sharing")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
}
