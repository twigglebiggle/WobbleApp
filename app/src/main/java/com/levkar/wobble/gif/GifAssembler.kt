package com.levkar.wobble.gif

import java.io.OutputStream

/**
 * Turns full RGB frames into a small GIF: frame 0 whole, then only the rectangle that changed, with unchanged
 * pixels inside it transparent. A frame identical to the one before just lengthens that one's delay.
 */
class GifAssembler(out: OutputStream, private val w: Int, private val h: Int, private val palette: Palette) {

    private val gif = GifWriter(out, w, h, palette.colors)
    private val shown = ByteArray(w * h)          // indices currently on the GIF canvas
    private val lastRgb = IntArray(w * h)
    private val cur = ByteArray(w * h)
    private var first = true
    var frames = 0; private set

    // The frame waiting for its delay to be known (a following identical frame adds to it).
    private var pendX = 0; private var pendY = 0; private var pendW = 0; private var pendH = 0
    private var pendPx: ByteArray? = null; private var pendDelay = 0; private var pendTransparent = false

    /** Adds a frame (0xRRGGBB per pixel, rows top-down), shown for [delayCs] hundredths of a second. */
    fun add(rgb: IntArray, delayCs: Int) {
        if (first) {
            first = false
            for (y in 0 until h) { val o = y * w; for (x in 0 until w) cur[o + x] = palette.index(rgb[o + x], x, y).toByte() }
            System.arraycopy(cur, 0, shown, 0, cur.size); System.arraycopy(rgb, 0, lastRgb, 0, rgb.size)
            pend(0, 0, w, h, cur.copyOf(), delayCs, false)
            return
        }
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val c = rgb[o + x]
                if (c == lastRgb[o + x]) continue                  // same colour, same spot = same index
                lastRgb[o + x] = c
                val ix = palette.index(c, x, y).toByte()
                cur[o + x] = ix
                if (ix != shown[o + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
            }
        }
        if (x1 < 0) { pendDelay += delayCs; return }               // nothing visible changed
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val px = ByteArray(rw * rh)
        val t = gif.transparent.toByte()
        var k = 0
        for (y in y0..y1) {
            val o = y * w
            for (x in x0..x1) {
                val ix = cur[o + x]
                if (ix == shown[o + x]) px[k++] = t else { px[k++] = ix; shown[o + x] = ix }
            }
        }
        pend(x0, y0, rw, rh, px, delayCs, true)
    }

    private fun pend(x: Int, y: Int, pw: Int, ph: Int, px: ByteArray, delay: Int, transparent: Boolean) {
        flush()
        pendX = x; pendY = y; pendW = pw; pendH = ph; pendPx = px; pendDelay = delay; pendTransparent = transparent
    }

    private fun flush() {
        val px = pendPx ?: return
        gif.frame(pendX, pendY, pendW, pendH, px, pendDelay, pendTransparent)
        pendPx = null; frames++
    }

    fun finish() { flush(); gif.finish() }
}
