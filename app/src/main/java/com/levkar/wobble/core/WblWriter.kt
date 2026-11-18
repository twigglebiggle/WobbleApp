package com.levkar.wobble.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Writes .wbl files the original AndWobble can still open:
 * PNG → `tEXt` "VERSION"="2" and `wbBl` chunks right after IHDR (this time with correct CRCs);
 * JPEG → an APP7 segment right after SOI. The image data itself is copied untouched.
 * Payload in both: 4-byte big-endian length + Wbl protobuf.
 */
object WblWriter {

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** [image] is any PNG or JPEG, with or without existing wobble data (which is replaced). */
    fun write(doc: WblDocument, image: ByteArray): ByteArray {
        val proto = encode(doc)
        return when {
            image.size >= 8 && image.copyOfRange(0, 8).contentEquals(PNG_SIG) -> writePng(proto, image)
            image.size >= 2 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() -> writeJpeg(proto, image)
            else -> throw IllegalArgumentException("Not a PNG or JPEG")
        }
    }

    private fun writePng(proto: ByteArray, img: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(img)
        val out = ByteArrayOutputStream(img.size + proto.size + 2048)
        out.write(img, 0, 8)
        var i = 8
        var wrote = false
        val crc = CRC32()
        while (i + 12 <= img.size) {
            val len = bb.getInt(i)
            if (len < 0 || i + 12 + len > img.size) break
            val type = String(img, i + 4, 4, Charsets.ISO_8859_1)
            crc.reset(); crc.update(img, i + 4, 4 + len)
            val crcOk = crc.value.toInt() == bb.getInt(i + 8 + len)
            val isOldWobble = type == "wbBl" || (type == "tEXt" && String(img, i + 8, minOf(len, 7), Charsets.ISO_8859_1) == "VERSION")
            if (crcOk && !isOldWobble) out.write(img, i, 12 + len)
            if (type == "IHDR" && !wrote) {
                chunk(out, "tEXt", "VERSION".toByteArray() + byteArrayOf(0) + "2".toByteArray())
                // Original layout: length prefix + proto, zero-padded to a multiple of 1024.
                val body = ByteArray(((proto.size + 4 + 1023) / 1024) * 1024)
                ByteBuffer.wrap(body).putInt(proto.size).put(proto)
                chunk(out, "wbBl", body)
                wrote = true
            }
            i += 12 + len
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private fun writeJpeg(proto: ByteArray, img: ByteArray): ByteArray {
        val seg = 2 + 4 + proto.size
        require(seg <= 0xFFFF) { "Too many bubbles for a JPEG wobble" }
        val out = ByteArrayOutputStream(img.size + seg + 2)
        out.write(img, 0, 2)                                        // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE7.toByte(), (seg shr 8).toByte(), seg.toByte()))
        out.write(ByteBuffer.allocate(4).putInt(proto.size).array()); out.write(proto)
        // Copy the rest, dropping any old APP7 wobble segment.
        var i = 2
        while (i + 4 <= img.size && img[i] == 0xFF.toByte()) {
            val marker = img[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break                // image data starts: copy the rest verbatim
            val len = ((img[i + 2].toInt() and 0xFF) shl 8) or (img[i + 3].toInt() and 0xFF)
            if (marker != 0xE7) out.write(img, i, 2 + len)
            i += 2 + len
        }
        out.write(img, i, img.size - i)
        return out.toByteArray()
    }

    // ---- protobuf encoding (field numbers from the original Wbl.proto) ----

    private class Enc {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun key(field: Int, wt: Int) = varint(((field shl 3) or wt).toLong())
        fun float(field: Int, v: Float) { key(field, 5); val b = java.lang.Float.floatToIntBits(v); for (s in 0..3) out.write((b ushr (8 * s)) and 0xFF) }
        fun bool(field: Int, v: Boolean) { key(field, 0); varint(if (v) 1 else 0) }
        fun bytes(field: Int, b: ByteArray) { key(field, 2); varint(b.size.toLong()); out.write(b) }
    }

    private fun vec(x: Float, y: Float) = Enc().apply { float(1, x); float(2, y) }.out.toByteArray()

    fun encode(doc: WblDocument): ByteArray {
        val e = Enc()
        for (b in doc.bubbles) {
            val w = Enc()
            w.bytes(1, vec(b.x, b.y))
            for (p in b.points) w.bytes(2, vec(p[0], p[1]))
            for (c in b.curvature) w.float(3, c)                     // unpacked, as the original wrote it
            w.float(4, b.wobbliness)
            w.float(5, if (b.freeze) 0f else b.height)                // a Freeze zone moves nothing in the original app
            val curveOn = b.useCurve && b.curve != null
            w.float(6, if (curveOn) Falloff.fitExtent(b.curve!!, Falloff.cap(b.ceiling, b.height)) else b.extent)   // original app: closest Extent
            w.float(7, 1f)                                           // mass: unused, written for old readers
            b.name?.let { w.bytes(13, it.toByteArray(Charsets.UTF_8)) }
            w.bool(14, b.locked)
            w.bool(15, b.visible)
            if (b.area) w.bool(WblFile.FIELD_AREA, true)            // only written when set: Point files stay original-identical
            b.curve?.let { c ->                                      // kept even while Falloff = Extent (Lev: never lose a curve)
                val p = Enc(); for (v in c) { val bits = java.lang.Float.floatToIntBits(v); for (s in 0..3) p.out.write((bits ushr (8 * s)) and 0xFF) }
                w.bytes(WblFile.FIELD_CURVE, p.out.toByteArray())
            }
            if (curveOn) { w.bool(WblFile.FIELD_USE_CURVE, true); w.float(WblFile.FIELD_OWN_EXTENT, b.extent) }
            if (b.ceiling < Falloff.CEIL_OFF) w.float(WblFile.FIELD_CEILING, b.ceiling)
            if (b.freeze) { w.bool(WblFile.FIELD_FREEZE, true); w.float(WblFile.FIELD_HOLD, b.hold) }
            e.bytes(1, w.out.toByteArray())
        }
        doc.name?.let { e.bytes(11, it.toByteArray(Charsets.UTF_8)) }
        doc.author?.takeIf { it.isNotBlank() && !doc.authorAi }?.let { e.bytes(WblFile.FIELD_AUTHOR, it.toByteArray(Charsets.UTF_8)) }
        if (doc.authorAi) e.bool(WblFile.FIELD_AUTHOR_AI, true)
        doc.source?.takeIf { it.isNotBlank() }?.let { e.bytes(WblFile.FIELD_SOURCE, it.toByteArray(Charsets.UTF_8)) }
        doc.created?.let { e.key(WblFile.FIELD_CREATED, 0); e.varint(it) }
        return e.out.toByteArray()
    }
}
