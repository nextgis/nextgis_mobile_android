package com.nextgis.mobile.mapsafe.test

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Debug-only file-backed provider used to replace the system picker in device tests. */
class MapSafeTestDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(requireNotNull(context), uri)
        if (mode != "r") file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val file = fileFor(requireNotNull(context), uri)
        return MatrixCursor(requested).apply {
            addRow(requested.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.takeIf(File::exists)?.length() ?: 0L
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "pgp", "gpg" -> "application/pgp-encrypted"
        "json", "geojson" -> "application/geo+json"
        else -> "application/octet-stream"
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return if (fileFor(requireNotNull(context), uri).delete()) 1 else 0
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        fun uri(context: Context, fileName: String): Uri {
            val safeName = File(fileName).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.mapsafe.test.documents")
                .appendPath(safeName)
                .build()
        }

        fun file(context: Context, fileName: String): File =
            File(context.cacheDir, "mapsafe-test-documents/${File(fileName).name}")

        private fun fileFor(context: Context, uri: Uri): File {
            val fileName = File(requireNotNull(uri.lastPathSegment) { "A test-document name is required." }).name
            return file(context, fileName)
        }
    }
}
