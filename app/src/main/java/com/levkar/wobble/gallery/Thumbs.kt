package com.levkar.wobble.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** The gallery's on-disk thumbnails (cache/thumbs/<sha1(uri)>_<modified>.jpg), reused as instant placeholders. */
object Thumbs {
    fun key(uri: Uri) = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    /** The newest cached thumbnail for [uri], or null. Any thread. */
    fun find(c: Context, uri: Uri): Bitmap? {
        val prefix = key(uri) + "_"
        val f = File(c.cacheDir, "thumbs").listFiles()?.filter { it.name.startsWith(prefix) }?.maxByOrNull { it.lastModified() } ?: return null
        return BitmapFactory.decodeFile(f.path)
    }
}
