package com.levkar.wobble.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How motion fades from a bubble's peak (r = 0) to its edge (r = 1).
 *
 * Extent (the original's): f(r) = 1 − r^p, p = (1.5·extent + 0.5)² + 0.75.
 * Curve (ours): points (x, y) the user places, joined by a monotone cubic (Fritsch–Carlson; Lev's pick "A"):
 * smooth, and never rises above or dips below the points on either side, so what you place is what you get.
 *
 * Points are a flat array x0,y0,x1,y1,… with x0 = 0, xn = 1 and x increasing.
 */
object Falloff {

    /** Ceiling value meaning "no cap" (the Scale slider's top, so it can never bite). */
    const val CEIL_OFF = 1.5f

    /** The ceiling is absolute (in motion, after Scale): the curve itself is capped at ceiling / scale. */
    fun cap(ceiling: Float, scale: Float) = if (scale <= 1e-4f) 1f else (ceiling / scale).coerceIn(0f, 1f)

    fun exponent(extent: Float) = (extent * 1.5f + 0.5f).let { it * it + 0.75f }
    fun extentValue(extent: Float, r: Float) = 1f - r.coerceIn(0f, 1f).pow(exponent(extent))

    /** Starting curve when a bubble first switches to Curve: three points on its Extent shape. */
    fun seed(extent: Float) = floatArrayOf(0f, 1f, 0.5f, extentValue(extent, 0.5f), 1f, 0f)

    /** The Extent value whose shape is closest to [pts] (written to files so the original app shows something similar). */
    fun fitExtent(pts: FloatArray, ceiling: Float = 1f): Float {
        val c = Curve(pts, ceiling)
        var best = 0.5f; var bestErr = Float.MAX_VALUE
        for (i in 0..100) {
            val e = i / 100f
            var err = 0f
            for (k in 0..32) { val r = k / 32f; val d = c.eval(r) - extentValue(e, r); err += d * d }
            if (err < bestErr) { bestErr = err; best = e }
        }
        return best
    }

    /** A curve ready for fast lookups (precomputed table), capped at [ceiling] (1 = no cap). */
    class Curve(pts: FloatArray, ceiling: Float = 1f) {
        private val lut = FloatArray(LUT + 1)

        init {
            val n = pts.size / 2
            val xs = FloatArray(n) { pts[2 * it] }; val ys = FloatArray(n) { pts[2 * it + 1] }
            val m = tangents(xs, ys)
            var seg = 0
            for (i in 0..LUT) {
                val x = i.toFloat() / LUT
                while (seg < n - 2 && x > xs[seg + 1]) seg++
                lut[i] = hermite(xs, ys, m, seg, x).coerceIn(0f, ceiling.coerceIn(0f, 1f))
            }
        }

        /** Value at r in 0..1 (linear between table entries). */
        fun eval(r: Float): Float {
            val f = r.coerceIn(0f, 1f) * LUT
            val i = f.toInt().coerceAtMost(LUT - 1)
            val t = f - i
            return lut[i] + (lut[i + 1] - lut[i]) * t
        }
    }

    /** Value of the curve through [pts] at x (used by the editor for hit-testing and drawing). */
    fun value(pts: FloatArray, x: Float): Float {
        val n = pts.size / 2
        val xs = FloatArray(n) { pts[2 * it] }; val ys = FloatArray(n) { pts[2 * it + 1] }
        val m = tangents(xs, ys)
        var seg = 0
        while (seg < n - 2 && x > xs[seg + 1]) seg++
        return hermite(xs, ys, m, seg, x.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    // ---- monotone cubic (Fritsch–Carlson) ----

    private fun tangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-6f) }
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (abs(d[i]) < 1e-9f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        return m
    }

    private fun hermite(xs: FloatArray, ys: FloatArray, m: FloatArray, i: Int, x: Float): Float {
        val h = (xs[i + 1] - xs[i]).coerceAtLeast(1e-6f)
        val t = ((x - xs[i]) / h).coerceIn(0f, 1f)
        val t2 = t * t; val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
               (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
    }

    private const val LUT = 256
}
