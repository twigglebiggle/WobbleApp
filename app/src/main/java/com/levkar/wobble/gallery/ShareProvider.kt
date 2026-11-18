package com.levkar.wobble.gallery

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Tiny read-only provider for "Share wobble": serves files from cache/share/ to the app the user picks
 * (granted per share via FLAG_GRANT_READ_URI_PERMISSION). No androidx needed.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val f = File(dir(context!!), name)
        return if (f.parentFile == dir(context!!) && f.exists()) f else null      // no path tricks
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "wbl" -> "application/octet-stream"; else -> null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        fun dir(c: Context) = File(c.cacheDir, "share").apply { mkdirs() }
        fun uri(c: Context, f: File): Uri = Uri.parse("content://${c.packageName}.share/${Uri.encode(f.name)}")
    }
}
