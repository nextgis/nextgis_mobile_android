package com.nextgis.mobile.mapsafe

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that production saves reach the fixed shared folder visible in Android Files. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class MapSafeSaveFolderDeviceTest {

    @Test
    fun outputIsSavedInDownloadsMapSafeWithoutAPicker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MapSafeSaveFolderRepository.clear(context)
        val bytes = "MapSafe fixed shared folder test".toByteArray()
        val requestedName = "mapsafe-folder-test-${System.nanoTime()}.txt"

        val saved = MapSafeSaveFolderRepository.save(
            context,
            "text/plain",
            requestedName
        ) { uri ->
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("The shared MapSafe output could not be opened.")
        }

        try {
            assertEquals("Downloads/MapSafe", saved.folderLocation)
            assertTrue(saved.fileName.startsWith("mapsafe-folder-test-"))
            val relativePath = context.contentResolver.query(
                saved.uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("Download/MapSafe/", relativePath)
            val recovered = context.contentResolver.openInputStream(saved.uri)?.use { it.readBytes() }
                ?: error("The saved test output could not be read.")
            assertArrayEquals(bytes, recovered)
        } finally {
            context.contentResolver.delete(saved.uri, null, null)
            MapSafeSaveFolderRepository.clear(context)
        }
    }
}
