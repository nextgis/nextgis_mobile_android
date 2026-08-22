package com.nextgis.mobile.mapsafe.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.nextgis.mobile.BuildConfig
import java.io.File

/** The fixed MapSafe output folder in shared device storage. */
object MapSafeSaveFolderRepository {

    data class Folder(
        val uri: Uri,
        val displayLocation: String,
        internal val mode: String
    )

    data class SavedFile<T>(
        val uri: Uri,
        val fileName: String,
        val folderLocation: String,
        val value: T
    ) {
        val displayLocation: String
            get() = "$folderLocation/$fileName"
    }

    /** Returns the one fixed folder. Debug tests may temporarily supply a content-provider folder. */
    fun read(context: Context): Folder {
        debugFolder(context)?.let { return it }
        return Folder(fixedFolderUri(), DISPLAY_LOCATION, MODE_SHARED_DOWNLOADS)
    }

    /** Creates the shared Downloads/MapSafe folder if it does not already exist. */
    fun ensureFolder(context: Context): Folder {
        val folder = read(context)
        if (folder.mode == MODE_DEBUG_DIRECT) return folder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ensureMediaStoreMarker(context)
        } else {
            requireLegacyWritePermission(context)
            check(legacyDirectory().isDirectory || legacyDirectory().mkdirs()) {
                "Android could not create $DISPLAY_LOCATION."
            }
        }
        return folder
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    fun <T> save(
        context: Context,
        mimeType: String,
        requestedFileName: String,
        writer: (Uri) -> T
    ): SavedFile<T> {
        val folder = ensureFolder(context)
        val safeName = safeFileName(requestedFileName)
        val uri = createDocument(context, folder, mimeType, safeName)
        return try {
            val value = writer(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && folder.mode == MODE_SHARED_DOWNLOADS) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            SavedFile(
                uri = uri,
                fileName = displayName(context, uri) ?: safeName,
                folderLocation = folder.displayLocation,
                value = value
            )
        } catch (error: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
                .recoverCatching { uri.path?.let(::File)?.delete() }
            throw error
        }
    }

    fun openFolder(context: Context): Boolean {
        val folder = runCatching { ensureFolder(context) }.getOrNull() ?: return false
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folder.uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(viewIntent)
            true
        }.getOrElse {
            val browseIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder.uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(browseIntent)
                true
            }.getOrDefault(false)
        }
    }

    /** Debug-only direct content folder used by deterministic emulator tests. */
    fun configureDebugFolder(context: Context, baseUri: Uri, displayLocation: String) {
        check(BuildConfig.DEBUG) { "Debug save folders are unavailable in release builds." }
        preferences(context).edit()
            .putString(KEY_DEBUG_URI, baseUri.toString())
            .putString(KEY_DEBUG_LOCATION, displayLocation)
            .apply()
    }

    private fun createDocument(
        context: Context,
        folder: Folder,
        mimeType: String,
        fileName: String
    ): Uri {
        if (folder.mode == MODE_DEBUG_DIRECT) {
            return folder.uri.buildUpon().appendPath(fileName).build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create $DISPLAY_LOCATION/$fileName.")
        }
        requireLegacyWritePermission(context)
        val directory = legacyDirectory()
        check(directory.isDirectory || directory.mkdirs()) {
            "Android could not create $DISPLAY_LOCATION."
        }
        return Uri.fromFile(uniqueFile(directory, fileName))
    }

    private fun ensureMediaStoreMarker(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val arguments = arrayOf(RELATIVE_PATH, MARKER_FILE)
        val exists = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arguments,
            null
        )?.use { it.moveToFirst() } == true
        if (exists) return
        val marker = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MARKER_FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        ) ?: error("Android could not create $DISPLAY_LOCATION.")
        resolver.openOutputStream(marker, "w")?.use { output ->
            output.write("MapSafe shared output folder\n".toByteArray())
        }
    }

    private fun debugFolder(context: Context): Folder? {
        if (!BuildConfig.DEBUG) return null
        val preferences = preferences(context)
        val uri = preferences.getString(KEY_DEBUG_URI, null)?.let(Uri::parse) ?: return null
        return Folder(
            uri,
            preferences.getString(KEY_DEBUG_LOCATION, null)?.takeIf(String::isNotBlank)
                ?: "MapSafe Test Save Folder",
            MODE_DEBUG_DIRECT
        )
    }

    private fun fixedFolderUri(): Uri = DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "primary:$RELATIVE_PATH_WITHOUT_TRAILING_SLASH"
    )

    @Suppress("DEPRECATION")
    private fun legacyDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        FOLDER_NAME
    )

    private fun requireLegacyWritePermission(context: Context) {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Allow storage access so MapSafe can create $DISPLAY_LOCATION." }
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct
        val dot = requestedName.lastIndexOf('.').takeIf { it > 0 } ?: requestedName.length
        val stem = requestedName.substring(0, dot)
        val extension = requestedName.substring(dot)
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 }
                ?.let(cursor::getString)
        }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: uri.path?.let(::File)?.name

    private fun safeFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
        .trim('.', ' ')
        .ifBlank { "mapsafe-output" }
        .take(120)

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    private const val FOLDER_NAME = "MapSafe"
    private const val DISPLAY_LOCATION = "Downloads/MapSafe"
    private const val RELATIVE_PATH_WITHOUT_TRAILING_SLASH = "Download/MapSafe"
    private const val RELATIVE_PATH = "$RELATIVE_PATH_WITHOUT_TRAILING_SLASH/"
    private const val MARKER_FILE = ".mapsafe-folder"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PREFERENCES = "mapsafe-save-folder"
    private const val KEY_DEBUG_URI = "debug-uri"
    private const val KEY_DEBUG_LOCATION = "debug-location"
    private const val MODE_SHARED_DOWNLOADS = "shared-downloads"
    private const val MODE_DEBUG_DIRECT = "debug-direct"
}
