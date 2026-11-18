package com.levkar.wobble.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * One bubble as stored in a .wbl file.
 * Coordinates are normalized to the image (0..1) with Y pointing UP (y = 0 is the bottom edge).
 * [points] are the corners relative to [x],[y]; [x],[y] is the user's center = the motion peak.
 */
class BubbleData(
    val x: Float,
    val y: Float,
    val points: List<FloatArray>,
    val curvature: List<Float>,
    val wobbliness: Float,
    val height: Float,
    val extent: Float,
    val name: String?,
    val locked: Boolean,
    val visible: Boolean,
    /** Profile mode: false = Point (the original's), true = Area. Stored as new field 20; old readers ignore it. */
    val area: Boolean = false,
    /** Falloff curve points (x0,y0,x1,y1,…; see [Falloff]); kept even while unused. Field 21, packed floats. */
    val curve: FloatArray? = null,
    /** Falloff = Curve instead of Extent. Field 22. */
    val useCurve: Boolean = false,
    /** Curve ceiling: absolute cap on motion (after the height/Scale), 0..1.5; [Falloff.CEIL_OFF] = off. Field 24. */
    val ceiling: Float = Falloff.CEIL_OFF,
    /** Freeze zone (Lev): no motion of its own; calms what moves under it by [hold] × its falloff. Field 25. */
    val freeze: Boolean = false,
    /** How much a Freeze zone holds still, 0..1 (1 = frozen). Field 26. */
    val hold: Float = 1f,
)

fun BubbleData.withHeight(h: Float) = BubbleData(x, y, points, curvature, wobbliness, h, extent, name, locked, visible, area, curve, useCurve, ceiling, freeze, hold)

/**
 * [author], [authorAi], [source]: credits (Lev, b52). New fields 30–32 at the top level; the original app ignores
 * them, and files without them simply have none.
 */
class WblDocument(val name: String?, val bubbles: List<BubbleData>,
                  val author: String? = null, val authorAi: Boolean = false, val source: String? = null,
                  /** When the piece was first saved by this app (epoch ms, field 33); null for older files. */
                  val created: Long? = null) {
    fun withCredits(author: String?, ai: Boolean, source: String?) = WblDocument(name, bubbles, author, ai, source, created)
    fun withCreated(t: Long) = WblDocument(name, bubbles, author, authorAi, source, t)
}

/**
 * Reader for the original AndWobble .wbl container (a PNG or JPEG with the bubble data embedded).
 *
 * PNG: chunk `wbBl` = 4-byte big-endian length + Wbl protobuf, zero padded.
 * JPEG: APP7 segment, same payload layout.
 * The original writes wrong CRCs on its `tEXt` and `wbBl` chunks, so [imageBytes] is a cleaned
 * copy with every chunk whose CRC fails removed, safe for strict image decoders.
 */
object WblFile {

    class Parsed(val document: WblDocument?, val imageBytes: ByteArray)

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** First field number of our own additions (the original's proto stops at 15). */
    /** Credits (document level): author text, "made with AI" flag, source link. */
    const val FIELD_AUTHOR = 30
    const val FIELD_AUTHOR_AI = 31
    const val FIELD_SOURCE = 32
    const val FIELD_CREATED = 33
    const val FIELD_AREA = 20
    const val FIELD_CURVE = 21
    const val FIELD_USE_CURVE = 22
    /** While a curve is in use, field 6 holds the closest Extent (for the original app); the user's own Extent is kept here. */
    const val FIELD_OWN_EXTENT = 23
    /** Freeze zone flag (bool) and its hold (float 0..1). */
    const val FIELD_FREEZE = 25
    const val FIELD_HOLD = 26
    /** Curve ceiling (float, absolute motion cap); written only when set. */
    const val FIELD_CEILING = 24

    /**
     * The picture as it went in, without the wobble data: same format, no re-encoding.
     * PNG: the `wbBl` chunk (and any chunk with a broken CRC) removed. JPEG: our APP7 segments removed.
     * Returns the bytes and "png" / "jpg" (null for anything else).
     */
    fun sourceImage(bytes: ByteArray): Pair<ByteArray, String>? {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIG)) return parsePng(bytes).imageBytes to "png"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return stripJpeg(bytes) to "jpg"
        return null
    }

    private fun stripJpeg(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var i = 2
        while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break                    // image data from here on: copied as is
            val segLen = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (i + 2 + segLen > b.size) break
            if (marker != 0xE7) out.write(b, i, 2 + segLen)
            i += 2 + segLen
        }
        out.write(b, i, b.size - i)
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIG)) return parsePng(bytes)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return parseJpeg(bytes)
        return Parsed(null, bytes)
    }

    private fun parsePng(b: ByteArray): Parsed {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val clean = java.io.ByteArrayOutputStream(b.size)
        clean.write(b, 0, 8)
        var doc: WblDocument? = null
        var i = 8
        val crc = CRC32()
        while (i + 12 <= b.size) {
            val len = bb.getInt(i)
            if (len < 0 || i + 12 + len > b.size) break
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (type == "wbBl" && len >= 4) {
                val plen = bb.getInt(i + 8)
                if (plen in 0..(len - 4)) doc = decodeWbl(b, i + 12, plen)
            }
            crc.reset(); crc.update(b, i + 4, 4 + len)
            val crcOk = crc.value.toInt() == bb.getInt(i + 8 + len)
            if (crcOk && type != "wbBl") clean.write(b, i, 12 + len)
            i += 12 + len
            if (type == "IEND") break
        }
        return Parsed(doc, clean.toByteArray())
    }

    private fun parseJpeg(b: ByteArray): Parsed {
        var i = 2
        var doc: WblDocument? = null
        while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break
            val segLen = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE7 && segLen >= 6) {
                val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
                val plen = bb.getInt(i + 4)
                if (plen in 0..(segLen - 6)) doc = decodeWbl(b, i + 8, plen)
            }
            i += 2 + segLen
        }
        return Parsed(doc, b)
    }

    // ---- minimal protobuf decoding (proto2, no dependencies) ----

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var s = 0
            while (true) {
                val c = b[pos++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl s)
                if (c < 0x80) return r
                s += 7
            }
        }
        fun fixed32(): Int {
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4; return v
        }
        fun float() = java.lang.Float.intBitsToFloat(fixed32())
        fun sub(): Reader { val n = varint().toInt(); val r = Reader(b, pos, pos + n); pos += n; return r }
        fun skip(wt: Int) {
            when (wt) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = varint().toInt(); pos += n }
                5 -> pos += 4
                else -> throw IllegalStateException("bad wire type $wt")
            }
        }
    }

    private fun decodeWbl(b: ByteArray, off: Int, len: Int): WblDocument? = try {
        val r = Reader(b, off, off + len)
        var name: String? = null
        var author: String? = null; var authorAi = false; var source: String? = null; var created: Long? = null
        val bubbles = ArrayList<BubbleData>()
        while (r.more()) {
            val key = r.varint().toInt(); val f = key ushr 3; val wt = key and 7
            when {
                f == 1 && wt == 2 -> bubbles += decodeBubble(r.sub())
                f == 11 && wt == 2 -> { val s = r.sub(); name = String(b, s.pos, s.end - s.pos, Charsets.UTF_8) }
                f == FIELD_AUTHOR && wt == 2 -> { val s = r.sub(); author = String(b, s.pos, s.end - s.pos, Charsets.UTF_8) }
                f == FIELD_AUTHOR_AI && wt == 0 -> authorAi = r.varint() != 0L
                f == FIELD_SOURCE && wt == 2 -> { val s = r.sub(); source = String(b, s.pos, s.end - s.pos, Charsets.UTF_8) }
                f == FIELD_CREATED && wt == 0 -> created = r.varint()
                else -> r.skip(wt)
            }
        }
        WblDocument(name, bubbles, author?.ifBlank { null }, authorAi, source?.ifBlank { null }, created?.takeIf { it > 0 })
    } catch (e: Exception) { null }

    private fun decodeVec(r: Reader): FloatArray {
        val v = FloatArray(2)
        while (r.more()) {
            val key = r.varint().toInt(); val f = key ushr 3; val wt = key and 7
            if ((f == 1 || f == 2) && wt == 5) v[f - 1] = r.float() else r.skip(wt)
        }
        return v
    }

    private fun decodeBubble(r: Reader): BubbleData {
        var pos = floatArrayOf(0f, 0f)
        val pts = ArrayList<FloatArray>()
        val curv = ArrayList<Float>()
        var wobbliness = 0.5f; var height = 1f; var extent = 0.5f
        var name: String? = null; var locked = false; var visible = true; var area = false
        var curve: FloatArray? = null; var useCurve = false; var ownExtent: Float? = null; var ceiling = Falloff.CEIL_OFF; var freeze = false; var hold = 1f
        while (r.more()) {
            val key = r.varint().toInt(); val f = key ushr 3; val wt = key and 7
            when {
                f == 1 && wt == 2 -> pos = decodeVec(r.sub())
                f == 2 && wt == 2 -> pts += decodeVec(r.sub())
                f == 3 && wt == 5 -> curv += r.float()
                f == 3 && wt == 2 -> { val s = r.sub(); while (s.more()) curv += s.float() }
                f == 4 && wt == 5 -> wobbliness = r.float()
                f == 5 && wt == 5 -> height = r.float()
                f == 6 && wt == 5 -> extent = r.float()
                f == 13 && wt == 2 -> { val s = r.sub(); name = String(s.b, s.pos, s.end - s.pos, Charsets.UTF_8) }
                f == 14 && wt == 0 -> locked = r.varint() != 0L
                f == 15 && wt == 0 -> visible = r.varint() != 0L
                f == FIELD_AREA && wt == 0 -> area = r.varint() != 0L
                f == FIELD_CURVE && wt == 2 -> { val s = r.sub(); val l = ArrayList<Float>(); while (s.more()) l += s.float(); curve = l.toFloatArray() }
                f == FIELD_USE_CURVE && wt == 0 -> useCurve = r.varint() != 0L
                f == FIELD_OWN_EXTENT && wt == 5 -> ownExtent = r.float()
                f == FIELD_CEILING && wt == 5 -> ceiling = r.float().coerceIn(0f, Falloff.CEIL_OFF)
                f == FIELD_FREEZE && wt == 0 -> freeze = r.varint() != 0L
                f == FIELD_HOLD && wt == 5 -> hold = r.float().coerceIn(0f, 1f)
                else -> r.skip(wt)
            }
        }
        // A curve needs ≥ 2 points, starting at x 0 and ending at x 1; anything else is ignored.
        if (curve != null && (curve.size < 4 || curve.size % 2 != 0 || curve[0] != 0f || curve[curve.size - 2] != 1f)) { curve = null; useCurve = false }
        if (curve == null) useCurve = false
        return BubbleData(pos[0], pos[1], pts, curv, wobbliness, height, ownExtent ?: extent, name, locked, visible, area, curve, useCurve, ceiling,
            freeze, hold).let { if (freeze) it.withHeight(1f) else it }   // a zone is stored with height 0 for the original app
    }
}
