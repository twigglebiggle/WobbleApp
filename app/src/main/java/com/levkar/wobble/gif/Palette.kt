package com.levkar.wobble.gif

/**
 * One 256-colour palette for the whole GIF, built from the still picture (median cut on a 5-bit histogram),
 * plus steady ordered dithering: the dither depends only on the pixel's position and colour, so a pixel that
 * doesn't change between frames gets the same index and never flickers (and frame diffs stay small).
 * Index 255 is kept free for transparency.
 */
class Palette private constructor(val colors: IntArray, private val used: Int) {

    // Nearest palette entry per 6-bit-per-channel colour, filled on demand.
    private val cache = IntArray(1 shl 18).apply { fill(-1) }

    /** Palette index for an RGB colour (0xRRGGBB) at pixel (x, y). */
    fun index(rgb: Int, x: Int, y: Int): Int {
        val d = DITHER[(y and 3) * 4 + (x and 3)]
        val r = ((rgb shr 16 and 0xFF) + d).coerceIn(0, 255) shr 2
        val g = ((rgb shr 8 and 0xFF) + d).coerceIn(0, 255) shr 2
        val b = ((rgb and 0xFF) + d).coerceIn(0, 255) shr 2
        val k = (r shl 12) or (g shl 6) or b
        val hit = cache[k]
        if (hit >= 0) return hit
        val cr = (r shl 2) + 2; val cg = (g shl 2) + 2; val cb = (b shl 2) + 2
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in 0 until used) {
            val c = colors[i]
            val dr = (c shr 16 and 0xFF) - cr; val dg = (c shr 8 and 0xFF) - cg; val db = (c and 0xFF) - cb
            val dist = 3 * dr * dr + 4 * dg * dg + 2 * db * db
            if (dist < bestD) { bestD = dist; best = i }
        }
        cache[k] = best
        return best
    }

    companion object {
        /** 4×4 Bayer thresholds, centered, in 8-bit units (±~7). */
        private val DITHER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { (it - 7.5f) * 1.9f }.map { Math.round(it) }.toIntArray()

        /** From [rgb] pixels (0xRRGGBB, alpha ignored); [extra] colours (e.g. the tap ring) are always included. */
        fun build(rgb: IntArray, extra: IntArray = IntArray(0)): Palette {
            val hist = IntArray(1 shl 15)
            val step = maxOf(1, rgb.size / 400_000)                // a sample is plenty
            var i = 0
            while (i < rgb.size) { val c = rgb[i]; hist[((c shr 19 and 31) shl 10) or ((c shr 11 and 31) shl 5) or (c shr 3 and 31)]++; i += step }
            val bins = (0 until hist.size).filter { hist[it] > 0 }.toIntArray()
            val want = 255 - extra.size
            val boxes = ArrayList<IntArray>().apply { add(bins) }
            while (boxes.size < want) {
                // Split the box with the most pixels × widest channel.
                var bi = -1; var bestScore = 0L; var bestCh = 0
                for ((n, box) in boxes.withIndex()) {
                    if (box.size < 2) continue
                    var count = 0L
                    val lo = intArrayOf(31, 31, 31); val hi = intArrayOf(0, 0, 0)
                    for (b in box) { count += hist[b]; for (ch in 0..2) { val v = b shr (10 - 5 * ch) and 31; if (v < lo[ch]) lo[ch] = v; if (v > hi[ch]) hi[ch] = v } }
                    var ch = 0; for (q in 1..2) if (hi[q] - lo[q] > hi[ch] - lo[ch]) ch = q
                    val score = count * (hi[ch] - lo[ch])
                    if (score > bestScore) { bestScore = score; bi = n; bestCh = ch }
                }
                if (bi < 0) break
                val box = boxes.removeAt(bi)
                val sh = 10 - 5 * bestCh
                val sorted = box.sortedBy { it shr sh and 31 }
                val total = sorted.sumOf { hist[it].toLong() }
                var acc = 0L; var cut = 1
                for ((n, b) in sorted.withIndex()) { acc += hist[b]; if (acc * 2 >= total) { cut = (n + 1).coerceIn(1, sorted.size - 1); break } }
                boxes += sorted.subList(0, cut).toIntArray(); boxes += sorted.subList(cut, sorted.size).toIntArray()
            }
            val colors = IntArray(256)
            var u = 0
            for (box in boxes) {
                var r = 0L; var g = 0L; var b = 0L; var n = 0L
                for (k in box) { val w = hist[k].toLong(); r += (k shr 10 and 31) * w; g += (k shr 5 and 31) * w; b += (k and 31) * w; n += w }
                if (n == 0L) continue
                fun ch(v: Long) = ((v * 255 + n * 31 / 2) / (n * 31)).toInt().coerceIn(0, 255)
                colors[u++] = (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            for (c in extra) if (u < 255) colors[u++] = c and 0xFFFFFF
            if (u == 0) colors[u++] = 0
            return Palette(colors, u)
        }
    }
}
