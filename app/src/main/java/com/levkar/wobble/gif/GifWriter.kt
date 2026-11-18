package com.levkar.wobble.gif

import java.io.OutputStream

/**
 * A small GIF89a writer (no libraries): one global 256-colour palette, endless loop, and frames that may cover
 * only part of the canvas and use [transparent] for "keep what's there" (disposal = leave in place).
 */
class GifWriter(private val out: OutputStream, val width: Int, val height: Int, palette: IntArray, val transparent: Int = 255) {

    init {
        require(palette.size == 256)
        out.write("GIF89a".toByteArray())
        short(width); short(height)
        out.write(0xF7)                 // global colour table, 8-bit colour resolution, 256 entries
        out.write(0); out.write(0)       // background index, pixel aspect
        for (c in palette) { out.write(c shr 16 and 0xFF); out.write(c shr 8 and 0xFF); out.write(c and 0xFF) }
        // NETSCAPE2.0: loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
    }

    /** One frame: [pixels] = palette indices of the [w]×[h] rectangle at ([x], [y]); [delayCs] in 1/100 s. */
    fun frame(x: Int, y: Int, w: Int, h: Int, pixels: ByteArray, delayCs: Int, useTransparent: Boolean) {
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write((1 shl 2) or (if (useTransparent) 1 else 0))     // disposal 1: leave in place
        short(delayCs.coerceIn(0, 65535))
        out.write(if (useTransparent) transparent else 0)
        out.write(0)
        out.write(0x2C); short(x); short(y); short(w); short(h); out.write(0)
        Lzw(out).encode(pixels, w * h)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun short(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }

    /** GIF LZW, 8-bit codes, 12-bit table with clear-on-full, packed into 255-byte sub-blocks. */
    private class Lzw(private val out: OutputStream) {
        private val block = ByteArray(255); private var blockLen = 0
        private var acc = 0; private var accBits = 0
        private var bits = 9

        private fun put(code: Int) {
            acc = acc or (code shl accBits); accBits += bits
            while (accBits >= 8) { byte(acc and 0xFF); acc = acc ushr 8; accBits -= 8 }
        }
        private fun byte(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) { out.write(255); out.write(block, 0, 255); blockLen = 0 }
        }

        fun encode(px: ByteArray, n: Int) {
            out.write(8)                                  // minimum code size
            val clear = 256; val eoi = 257
            val keys = IntArray(HSIZE); val codes = IntArray(HSIZE)
            java.util.Arrays.fill(keys, -1)
            var next = eoi + 1
            put(clear)
            if (n > 0) {
                var ent = px[0].toInt() and 0xFF
                for (i in 1 until n) {
                    val c = px[i].toInt() and 0xFF
                    val key = (ent shl 8) or c
                    var h = ((key * 0x9E3779B1.toInt()) ushr 18) and (HSIZE - 1)
                    var found = -1
                    while (keys[h] != -1) { if (keys[h] == key) { found = codes[h]; break }; h = (h + 1) and (HSIZE - 1) }
                    if (found >= 0) { ent = found; continue }
                    put(ent)
                    if (next < 4096) {
                        keys[h] = key; codes[h] = next++
                        if (next > (1 shl bits) && bits < 12) bits++
                    } else {                                   // table full: start over
                        put(clear); java.util.Arrays.fill(keys, -1); next = eoi + 1; bits = 9
                    }
                    ent = c
                }
                put(ent)
            }
            put(eoi)
            if (accBits > 0) byte(acc and 0xFF)
            if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen) }
            out.write(0)                                  // block terminator
        }

        companion object { const val HSIZE = 1 shl 14 }
    }
}
