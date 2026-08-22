package com.nextgis.mobile.mapsafe

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import com.nextgis.mobile.mapsafe.ui.MapSafeOpenPgpActivity
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the compact dataset-selector header and its original-to-override state change. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeDatasetChoiceUiDeviceTest {

    @Test
    fun choosingAnOverrideChangesOriginalDatasetHeadingToDataset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "mapsafe/dataset-choice/original.geojson").apply {
            parentFile?.mkdirs()
            writeText("""{"type":"FeatureCollection","features":[]}""")
        }
        val chosenFile = MapSafeTestDocumentProvider.file(context, "chosen-dataset.geojson").apply {
            parentFile?.mkdirs()
            writeText("""{"type":"FeatureCollection","features":[]}""")
        }
        val chosenUri = MapSafeTestDocumentProvider.uri(context, chosenFile.name)

        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(chosenUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            )
            ActivityScenario.launch<MapSafeOpenPgpActivity>(
                MapSafeOpenPgpActivity.intent(
                    context,
                    sourceFile = source,
                    sourceDisplayName = source.name,
                    sourceRepresentation = "Original dataset"
                )
            ).use {
                onView(withText("Original Dataset")).check(matches(isDisplayed()))
                onView(withText(source.name)).check(matches(isDisplayed()))
                onView(withText("Choose")).check(matches(isDisplayed())).perform(click())

                onView(withText("Dataset")).check(matches(isDisplayed()))
                onView(withText(chosenFile.name)).check(matches(isDisplayed()))
                onView(withText("Original Dataset")).check(doesNotExist())
                onView(withText("Choose another file")).check(doesNotExist())
            }
        } finally {
            Intents.release()
            chosenFile.delete()
        }
    }
}
