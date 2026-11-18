package com.levkar.wobble.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.levkar.wobble.core.WblDocument
import com.levkar.wobble.core.WblFile

/** Decodes a .wbl (or a plain PNG/JPEG) into a bitmap plus its bubble data. */
object WblLoader {
    class Loaded(val bitmap: Bitmap, val doc: WblDocument, val hadWobbleData: Boolean)

    /** Returns null if the image can't be decoded. */
    fun load(bytes: ByteArray): Loaded? {
        val parsed = WblFile.parse(bytes)
        val bmp = BitmapFactory.decodeByteArray(parsed.imageBytes, 0, parsed.imageBytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        return Loaded(bmp, parsed.document ?: WblDocument(null, emptyList()), parsed.document != null)
    }
}
