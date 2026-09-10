package com.nextgis.mobile.mapsafe.service

import android.Manifest
import android.content.ContentUris
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
import java.io.FileOutputStream

/** The fixed MapSafe output folder in shared device storage. */
object MapSafeSaveFolderRepository {

    private data class AppendTarget(
        val uri: Uri,
        val newlyCreated: Boolean
    )

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

    /** A readable output already present in the shared MapSafe folder. */
    data class StoredFile(
        val uri: Uri,
        val fileName: String,
        val mimeType: String?,
        val sizeBytes: Long
    )

    /** Returns the one fixed folder. Debug tests may temporarily supply a content-provider folder. */
    fun read(context: Context): Folder {
        debugFolder(context)?.let { return it }
        return Folder(fixedFolderUri(), DISPLAY_LOCATION, MODE_SHARED_DOWNLOADS)
    }

    /**
     * Lists existing MapSafe outputs without opening Android's document picker.
     *
     * This is intentionally scoped to Downloads/MapSafe. It is used by the
     * combined workflow screen when the user chooses encrypted packages for a
     * community upload; it never scans unrelated device storage.
     */
    fun listStoredFiles(
        context: Context,
        extensions: Set<String> = emptySet()
    ): List<StoredFile> {
        val wanted = extensions.mapTo(mutableSetOf()) {
            it.trim().removePrefix(".").lowercase()
        }
        fun accepts(name: String): Boolean = wanted.isEmpty() ||
            name.substringAfterLast('.', "").lowercase() in wanted

        val folder = read(context)
        if (folder.mode == MODE_DEBUG_DIRECT || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = when {
                folder.uri.scheme == "file" -> folder.uri.path?.let(::File)
                folder.mode == MODE_SHARED_DOWNLOADS -> legacyDirectory()
                else -> null
            } ?: return emptyList()
            return directory.listFiles().orEmpty()
                .asSequence()
                .filter(File::isFile)
                .filter { accepts(it.name) }
                .map { StoredFile(Uri.fromFile(it), it.name, null, it.length()) }
                .sortedByDescending { it.fileName.lowercase() }
                .toList()
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val results = mutableListOf<Pair<Long, StoredFile>>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH_WITHOUT_TRAILING_SLASH%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(pathColumn) ?: continue
                if (!sameRelativePath(relativePath, RELATIVE_PATH)) continue
                val name = cursor.getString(nameColumn) ?: continue
                if (!accepts(name) || name == FOLDER_INFO_FILE) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn)
                )
                results += cursor.getLong(modifiedColumn) to StoredFile(
                    uri = uri,
                    fileName = name,
                    mimeType = cursor.getString(mimeColumn),
                    sizeBytes = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                )
            }
        }
        return results.sortedByDescending(Pair<Long, StoredFile>::first).map(Pair<Long, StoredFile>::second)
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
        // A MediaStore insert with RELATIVE_PATH creates the directory as part of
        // creating the real output. Do not make saving depend on the optional
        // folder marker: some OEM MediaProviders reject hidden marker names.
        val folder = prepareFolderForOutput(context)
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

    /** Appends UTF-8 rows to one stable text file, writing the header only when it is empty. */
    @Synchronized
    fun appendText(
        context: Context,
        mimeType: String,
        requestedFileName: String,
        header: String,
        rows: Collection<String>,
        migrateExistingText: ((String) -> String)? = null
    ): SavedFile<Unit> {
        require(rows.isNotEmpty()) { "At least one row is required." }
        val folder = prepareFolderForOutput(context)
        val safeName = safeFileName(requestedFileName)
        val target = findOrCreateAppendTarget(context, folder, mimeType, safeName)
        migrateExistingText?.let { migration ->
            migrateAppendTargetIfNeeded(context, folder, target.uri, migration)
        }
        val needsHeader = documentSize(context, target.uri) <= 0L
        return try {
            val output = if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                folder.mode == MODE_SHARED_DOWNLOADS
            ) {
                FileOutputStream(requireNotNull(target.uri.path), true)
            } else {
                context.contentResolver.openOutputStream(target.uri, "wa")
                    ?: error("The Save Folder did not open $safeName for appending.")
            }
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (needsHeader) {
                    writer.append(header.trimEnd('\r', '\n')).append('\n')
                }
                rows.forEach { row ->
                    writer.append(row.trimEnd('\r', '\n')).append('\n')
                }
            }
            if (
                target.newlyCreated &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                folder.mode == MODE_SHARED_DOWNLOADS
            ) {
                context.contentResolver.update(
                    target.uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && folder.mode == MODE_SHARED_DOWNLOADS) {
                rememberAppendTarget(context, safeName, target.uri)
            }
            SavedFile(
                uri = target.uri,
                fileName = displayName(context, target.uri) ?: safeName,
                folderLocation = folder.displayLocation,
                value = Unit
            )
        } catch (error: Throwable) {
            if (target.newlyCreated) {
                runCatching { context.contentResolver.delete(target.uri, null, null) }
                    .recoverCatching { target.uri.path?.let(::File)?.delete() }
            }
            forgetAppendTarget(context, safeName)
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

    /** Prepares only what an output write requires; MediaStore creates its relative directory. */
    private fun prepareFolderForOutput(context: Context): Folder {
        val folder = read(context)
        if (folder.mode == MODE_DEBUG_DIRECT || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return folder
        }
        requireLegacyWritePermission(context)
        check(legacyDirectory().isDirectory || legacyDirectory().mkdirs()) {
            "Android could not create $DISPLAY_LOCATION."
        }
        return folder
    }

    private fun findOrCreateAppendTarget(
        context: Context,
        folder: Folder,
        mimeType: String,
        fileName: String
    ): AppendTarget {
        if (folder.mode == MODE_DEBUG_DIRECT) {
            return AppendTarget(folder.uri.buildUpon().appendPath(fileName).build(), false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val remembered = rememberedAppendTarget(context, fileName)
                ?.takeIf { documentExists(context, it) }
            if (remembered != null) return AppendTarget(remembered, false)
            forgetAppendTarget(context, fileName)

            val existing = findExistingDownload(context, fileName)
            if (existing != null) return AppendTarget(existing, false)
            val created = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: error("Android could not create $DISPLAY_LOCATION/$fileName.")
            return AppendTarget(created, true)
        }
        requireLegacyWritePermission(context)
        val directory = legacyDirectory()
        check(directory.isDirectory || directory.mkdirs()) {
            "Android could not create $DISPLAY_LOCATION."
        }
        val file = File(directory, fileName)
        return AppendTarget(Uri.fromFile(file), !file.exists())
    }

    /**
     * MediaStore can fail to rediscover a file when a provider normalises its relative path.
     * Remembering the concrete content URI prevents a fresh insert (and Android's " (1)" suffix)
     * for every later append. The name-based lookup remains as a recovery path after upgrades.
     */
    private fun rememberedAppendTarget(context: Context, fileName: String): Uri? =
        preferences(context).getString(KEY_APPEND_URI_PREFIX + fileName, null)?.let(Uri::parse)

    private fun rememberAppendTarget(context: Context, fileName: String, uri: Uri) {
        preferences(context).edit().putString(KEY_APPEND_URI_PREFIX + fileName, uri.toString()).apply()
    }

    private fun forgetAppendTarget(context: Context, fileName: String) {
        preferences(context).edit().remove(KEY_APPEND_URI_PREFIX + fileName).apply()
    }

    private fun documentExists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun findExistingDownload(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        val numberedName = Regex("^${Regex.escape(stem)} \\(\\d+\\)${Regex.escape(extension)}$")
        val candidates = mutableListOf<ExistingDownload>()

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(fileName, "$stem%$extension"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val candidateName = cursor.getString(nameColumn) ?: continue
                val candidatePath = cursor.getString(pathColumn) ?: continue
                if (!sameRelativePath(candidatePath, RELATIVE_PATH)) continue
                if (candidateName != fileName && !numberedName.matches(candidateName)) continue
                candidates += ExistingDownload(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    ),
                    name = candidateName,
                    dateAdded = cursor.getLong(dateColumn)
                )
            }
        }

        return candidates
            .sortedWith(compareByDescending<ExistingDownload> { it.name == fileName }
                .thenByDescending { it.dateAdded })
            .firstOrNull()
            ?.uri
    }

    private fun sameRelativePath(first: String, second: String): Boolean =
        first.replace('\\', '/').trim('/').equals(
            second.replace('\\', '/').trim('/'),
            ignoreCase = true
        )

    private fun migrateAppendTargetIfNeeded(
        context: Context,
        folder: Folder,
        uri: Uri,
        migration: (String) -> String
    ) {
        if (documentSize(context, uri) <= 0L) return
        val existing = if (uri.scheme == "file") {
            uri.path?.let(::File)?.readText(Charsets.UTF_8) ?: return
        } else {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: return
        }
        val migrated = migration(existing)
        if (migrated == existing) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && folder.mode == MODE_SHARED_DOWNLOADS) {
            File(requireNotNull(uri.path)).writeText(migrated, Charsets.UTF_8)
        } else {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(migrated)
            } ?: error("The Save Folder did not open ${displayName(context, uri)} for migration.")
        }
    }

    private fun documentSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf(File::exists)?.length() ?: 0L
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) 0L else cursor.getLong(0)
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun ensureMediaStoreMarker(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val folderAlreadyExists = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH_WITHOUT_TRAILING_SLASH%"),
            null
        )?.use { cursor ->
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            var found = false
            while (!found && cursor.moveToNext()) {
                val path = cursor.getString(pathColumn)
                found = path != null && sameRelativePath(path, RELATIVE_PATH)
            }
            found
        } == true
        // Any existing output or legacy marker already proves that the directory exists.
        if (folderAlreadyExists) return
        val marker = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FOLDER_INFO_FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        ) ?: error("Android could not create $DISPLAY_LOCATION.")
        resolver.openOutputStream(marker, "w")?.use { output ->
            output.write(
                "MapSafe stores anonymised, encrypted, decrypted, and performance files here.\n"
                    .toByteArray()
            )
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
    // Avoid a dot-prefixed name. Some OEM MediaProviders cannot build a unique
    // MediaStore file for hidden names and throw before the actual output is saved.
    private const val FOLDER_INFO_FILE = "MapSafe folder information.txt"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PREFERENCES = "mapsafe-save-folder"
    private const val KEY_DEBUG_URI = "debug-uri"
    private const val KEY_DEBUG_LOCATION = "debug-location"
    private const val KEY_APPEND_URI_PREFIX = "append-uri:"
    private const val MODE_SHARED_DOWNLOADS = "shared-downloads"
    private const val MODE_DEBUG_DIRECT = "debug-direct"

    private data class ExistingDownload(
        val uri: Uri,
        val name: String,
        val dateAdded: Long
    )
}
