package com.levkar.wobble.core

import kotlin.math.hypot

/**
 * The kernel of an outline: the set of points from which the whole outline is visible.
 * A Point bubble's center must lie in it. It is the intersection of the inner half-planes of all
 * outline edges, so it is always convex, which lets a dragged center slide smoothly along its border.
 */
object Kernel {

    /**
     * Kernel of the closed polyline [o] (x0,y0,x1,y1,…), shrunk inward by [margin] so points on its border
     * still pass the strict visibility check. Returns a convex polygon (same format) or null if empty.
     */
    fun of(o: FloatArray, margin: Float = 0.5f): FloatArray? {
        val m = o.size / 2
        if (m < 3) return null
        var area2 = 0f
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until m) {
            val i1 = (i + 1) % m
            area2 += o[2 * i] * o[2 * i1 + 1] - o[2 * i1] * o[2 * i + 1]
            x0 = minOf(x0, o[2 * i]); x1 = maxOf(x1, o[2 * i]); y0 = minOf(y0, o[2 * i + 1]); y1 = maxOf(y1, o[2 * i + 1])
        }
        val ccw = area2 > 0
        var poly = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)        // start from the bounding box
        for (i in 0 until m) {
            val i1 = (i + 1) % m
            var ax = o[2 * i]; var ay = o[2 * i + 1]; var bx = o[2 * i1]; var by = o[2 * i1 + 1]
            if (!ccw) { val tx = ax; val ty = ay; ax = bx; ay = by; bx = tx; by = ty }
            val ex = bx - ax; val ey = by - ay
            val len = hypot(ex, ey)
            if (len < 1e-6f) continue
            poly = clip(poly, ax, ay, ex / len, ey / len, margin) ?: return null
        }
        return poly
    }

    /** Keeps the part of [p] where the signed distance left of the line (a, unit dir e) is ≥ [margin]. */
    private fun clip(p: FloatArray, ax: Float, ay: Float, ex: Float, ey: Float, margin: Float): FloatArray? {
        val n = p.size / 2
        val out = ArrayList<Float>(p.size + 4)
        fun dist(x: Float, y: Float) = ex * (y - ay) - ey * (x - ax) - margin
        for (i in 0 until n) {
            val i1 = (i + 1) % n
            val px = p[2 * i]; val py = p[2 * i + 1]; val qx = p[2 * i1]; val qy = p[2 * i1 + 1]
            val dp = dist(px, py); val dq = dist(qx, qy)
            if (dp >= 0) { out += px; out += py }
            if ((dp >= 0) != (dq >= 0)) {
                val t = dp / (dp - dq)
                out += px + (qx - px) * t; out += py + (qy - py) * t
            }
        }
        return if (out.size < 6) null else out.toFloatArray()
    }

    /** Nearest point of the convex polygon [k] to (x, y), written to [res]. */
    fun nearest(k: FloatArray, x: Float, y: Float, res: FloatArray) {
        val n = k.size / 2
        // Inside (all edges on the same side as the polygon's orientation)?
        var area2 = 0f
        for (i in 0 until n) { val i1 = (i + 1) % n; area2 += k[2 * i] * k[2 * i1 + 1] - k[2 * i1] * k[2 * i + 1] }
        val s = if (area2 >= 0) 1f else -1f
        var inside = true
        for (i in 0 until n) {
            val i1 = (i + 1) % n
            val cr = (k[2 * i1] - k[2 * i]) * (y - k[2 * i + 1]) - (k[2 * i1 + 1] - k[2 * i + 1]) * (x - k[2 * i])
            if (cr * s < 0) { inside = false; break }
        }
        if (inside) { res[0] = x; res[1] = y; return }
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            val i1 = (i + 1) % n
            val ax = k[2 * i]; val ay = k[2 * i + 1]; val ex = k[2 * i1] - ax; val ey = k[2 * i1 + 1] - ay
            val l2 = ex * ex + ey * ey
            val t = if (l2 < 1e-12f) 0f else (((x - ax) * ex + (y - ay) * ey) / l2).coerceIn(0f, 1f)
            val qx = ax + ex * t; val qy = ay + ey * t
            val d = (qx - x) * (qx - x) + (qy - y) * (qy - y)
            if (d < best) { best = d; res[0] = qx; res[1] = qy }
        }
    }
}
