package com.nextgis.mobile.mapsafe

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.nextgis.mobile.mapsafe.test.MapSafeTestDashboardActivity
import org.junit.Assert.assertTrue
import java.io.File

object MapSafeDeviceTestSupport {
    private const val TAG = "MapSafeTier1"

    fun production(stage: String, detail: String) {
        emit("PASS", stage, detail)
    }

    fun simulated(stage: String, detail: String) {
        emit("SIMULATED", stage, detail)
    }

    fun dashboard(activity: MapSafeTestDashboardActivity, status: String, stage: String, detail: String) {
        activity.appendStatus("[$status][$stage] $detail")
    }

    fun screenshot(context: Context, name: String): File {
        val directory = File("/sdcard/Download/MapSafe-Tier1")
        val file = File(directory, "${sanitize(name)}.png")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        device.executeShellCommand("mkdir -p ${directory.path}")
        device.executeShellCommand("screencap -p ${file.path}")
        val details = device.executeShellCommand("ls -l ${file.path}")
        assertTrue("Could not capture $name", details.contains(file.name))
        production("SCREENSHOT", file.name)
        return file
    }

    fun waitUntil(description: String, timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(100L)
        }
        assertTrue("Timed out waiting for $description", condition())
    }

    fun prepareMainActivity(context: Context) {
        android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean("app_intro", true)
            .putBoolean("show_geo_dialog", false)
            .commit()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val permissions = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.forEach { permission ->
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
    }

    private fun emit(status: String, stage: String, detail: String) {
        val message = "[$status][$stage] $detail"
        Log.i(TAG, message)
        println("[MAPSAFE][DEVICE]$message")
    }

    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
}
