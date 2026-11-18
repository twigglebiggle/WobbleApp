package com.levkar.wobble.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Outline and height profile of one bubble ("Droplet" mode, the original's method).
 *
 * Corners are given relative to the bubble's center (the motion peak), in pixels, Y up.
 * Each corner j becomes a rational quadratic Bézier from the previous edge midpoint, through the
 * corner, to the next edge midpoint, with weight 0.69 + 4.5·c² (c = corner curvature 0..1).
 * Works for any corner count ≥ 3; the outline must be star-shaped as seen from the center.
 *
 * Distance-to-edge is measured along the ray from the center, like the original.
 */
class BubbleShape(corners: FloatArray, curvature: FloatArray, val height: Float, extent: Float, val area: Boolean = false,
                  private val curve: Falloff.Curve? = null) {

    /** Falloff from peak (r 0) to edge (r 1): the user's curve if set, else the original Extent dome. */
    private fun profile(r: Float) = curve?.eval(r) ?: (1f - r.pow(profileExp))

    val n = corners.size / 2
    private val px = FloatArray(n) { corners[2 * it] }
    private val py = FloatArray(n) { corners[2 * it + 1] }
    private val mx = FloatArray(n) { (px[it] + px[(it + 1) % n]) * 0.5f }   // midpoint after corner `it`
    private val my = FloatArray(n) { (py[it] + py[(it + 1) % n]) * 0.5f }
    private val w = FloatArray(n) { val c = curvature[it]; 0.69f + c * c * 4.5f }

    /** Exponent of the dome: extent 0 → 1.0 (cone, slime), extent 1 → 4.75 (plateau, rigid). */
    val profileExp: Float = (extent * 1.5f + 0.5f).let { it * it + 0.75f }

    // Conservative bounds: the outline never leaves the corners' bounding box.
    val minX = px.min(); val maxX = px.max(); val minY = py.min(); val maxY = py.max()

    /**
     * Distance from the center to the outline along direction (dx, dy). Used by the physics to scale kicks
     * and the wall. In Area mode the center may sit anywhere (even outside a curvy outline), so the bubble
     * acts as a circle of the same area there.
     */
    fun radius(dx: Float, dy: Float): Float = if (area) areaRadius else hit(dx, dy, null)

    private val areaRadius by lazy { sqrt(area() / Math.PI.toFloat()).coerceAtLeast(1f) }

    // ---- Area mode: weight from the distance to the outline, shaped toward the apex (the center dot):
    // see [apexT]. Any outline shape works.
    // Outline detail scales with the corner count (≈128 points total), so many-cornered shapes stay cheap.
    private val poly by lazy { outline((128 / n).coerceIn(4, 16)) }

    /**
     * Distance to the outline, precomputed on a DF×DF grid over the bounds and read back bilinearly
     * (it's 1-Lipschitz, so the error stays below a grid cell and the result is just as smooth).
     * Replaces an exact point-to-every-edge search per mesh vertex, which made Area bubbles slow to load.
     */
    /** Grid size: cells about 1/12 of the bubble's depth (thin shapes need finer cells), 16..DF across. */
    private val dfN by lazy {
        val est = buildDf(16).max().coerceAtLeast(1f)                 // quick coarse pass for the depth
        (max(maxX - minX, maxY - minY) / (est / 12f)).toInt().coerceIn(16, DF)
    }
    private val df by lazy { buildDf(dfN) }

    private fun buildDf(nn: Int): FloatArray {
        val r = nn + 1
        val g = FloatArray(r * r)
        val p = poly; val m = p.size / 2
        val xs = FloatArray(m)
        for (j in 0..nn) {
            val y = minY + (maxY - minY) * j / nn
            // Scanline: where this row crosses the outline; cells between pairs of crossings are inside.
            var k = 0; var jj = m - 1
            for (i in 0 until m) {
                val yi = p[2 * i + 1]; val yj = p[2 * jj + 1]
                if ((yi > y) != (yj > y)) { val xi = p[2 * i]; val xj = p[2 * jj]; xs[k++] = (xj - xi) * (y - yi) / (yj - yi) + xi }
                jj = i
            }
            java.util.Arrays.sort(xs, 0, k)
            var c = 0
            for (i in 0..nn) {
                val x = minX + (maxX - minX) * i / nn
                while (c < k && xs[c] <= x) c++
                // Signed: negative outside, so cells straddling the outline interpolate straight across it.
                g[j * r + i] = if (c % 2 == 1) edgeDistance(x, y) else -edgeDistance(x, y)
            }
        }
        return g
    }

    private val depthMax by lazy { max(1e-3f, df.max()) }

    private fun distanceAt(x: Float, y: Float): Float {
        val nn = dfN
        val gx = ((x - minX) / (maxX - minX).coerceAtLeast(1e-6f) * nn).coerceIn(0f, nn.toFloat())
        val gy = ((y - minY) / (maxY - minY).coerceAtLeast(1e-6f) * nn).coerceIn(0f, nn.toFloat())
        val i = gx.toInt().coerceAtMost(nn - 1); val j = gy.toInt().coerceAtMost(nn - 1)
        val fx = gx - i; val fy = gy - j
        val r = nn + 1; val g = df
        val a = g[j * r + i] + (g[j * r + i + 1] - g[j * r + i]) * fx
        val b = g[(j + 1) * r + i] + (g[(j + 1) * r + i + 1] - g[(j + 1) * r + i]) * fx
        return max(0f, a + (b - a) * fy)
    }

    private fun insidePoly(x: Float, y: Float): Boolean {
        val p = poly; val m = p.size / 2
        var inside = false
        var j = m - 1
        for (i in 0 until m) {
            val xi = p[2 * i]; val yi = p[2 * i + 1]; val xj = p[2 * j]; val yj = p[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun edgeDistance(x: Float, y: Float): Float {
        val p = poly; val m = p.size / 2
        var best = Float.MAX_VALUE
        for (i in 0 until m) {
            val ax = p[2 * i]; val ay = p[2 * i + 1]
            val bx = p[2 * ((i + 1) % m)]; val by = p[2 * ((i + 1) % m) + 1]
            val ex = bx - ax; val ey = by - ay
            val len2 = ex * ex + ey * ey
            val t = if (len2 < 1e-9f) 0f else (((x - ax) * ex + (y - ay) * ey) / len2).coerceIn(0f, 1f)
            val qx = ax + ex * t - x; val qy = ay + ey * t - y
            best = minOf(best, qx * qx + qy * qy)
        }
        return sqrt(best)
    }

    /**
     * Area mode with an apex (Lev, b50): how far a point is "up" from the outline toward the apex, 0..1:
     * t = d_edge / (d_edge + d_apex). 0 on the outline, 1 at the apex (the center dot), any outline shape.
     * With the apex off-centre the far side gets a long, gradual slope, like Point with its peak moved;
     * a square-ish curve still gives the flat plateau. Apex in the middle of a round shape = the old Area exactly.
     * Divided by its largest value inside, so an apex left outside the outline still reaches full height.
     */
    private fun apexT(dx: Float, dy: Float): Float {
        val de = distanceAt(dx, dy)
        val t = de / (de + sqrt(dx * dx + dy * dy) + 1e-4f)
        return (t / apexTMax).coerceIn(0f, 1f)
    }

    private val apexTMax by lazy {
        val nn = dfN; val r = nn + 1; val g = df
        var best = 1e-3f
        for (j in 0..nn) for (i in 0..nn) {
            val de = g[j * r + i]; if (de <= 0f) continue
            val x = minX + (maxX - minX) * i / nn; val y = minY + (maxY - minY) * j / nn
            best = max(best, de / (de + sqrt(x * x + y * y) + 1e-4f))
        }
        if (insidePoly(0f, 0f)) 1f else best                     // apex inside: its own spot is 1 exactly
    }

    /** Deepest distance from the outline inside the bubble (Area mode's notion of size). */
    fun depth(): Float = depthMax

    /** Height-profile weight at a point relative to the center; 0 outside. */
    fun weight(dx: Float, dy: Float): Float {
        if (area) {
            if (!insidePoly(dx, dy)) return 0f
            return profile(1f - apexT(dx, dy)) * height
        }
        val d = sqrt(dx * dx + dy * dy)
        val r = if (d < 1e-4f) 0f else (d / radius(dx, dy)).coerceIn(0f, 1f)
        return profile(r) * height
    }

    /**
     * Weight at a point if it's inside the outline, else 0 — [contains] and [weight] in one pass
     * (the mesh calls this for every vertex in the bounds; doing both separately repeated the costly part).
     */
    fun weightIfInside(dx: Float, dy: Float): Float {
        if (dx < minX || dx > maxX || dy < minY || dy > maxY) return 0f
        if (area) return weight(dx, dy)                       // weight() already tests the polygon
        val d2 = dx * dx + dy * dy
        val r = radius(dx, dy)
        if (d2 >= r * r) return 0f
        val d = sqrt(d2)
        return profile(if (d < 1e-4f) 0f else (d / r).coerceIn(0f, 1f)) * height
    }

    fun contains(dx: Float, dy: Float): Boolean {
        if (dx < minX || dx > maxX || dy < minY || dy > maxY) return false
        if (area) return insidePoly(dx, dy)
        val d2 = dx * dx + dy * dy
        val r = radius(dx, dy)
        return d2 < r * r
    }

    /** Outward unit normal of the outline where the ray (dx, dy) hits it. */
    fun normal(dx: Float, dy: Float, out: FloatArray) {
        if (area) { val l = max(1e-6f, sqrt(dx * dx + dy * dy)); out[0] = dx / l; out[1] = dy / l; return }
        hit(dx, dy, out)
    }

    /** The outline as a closed polyline relative to the center: x0,y0,x1,y1,… ([perCurve] points per corner). */
    fun outline(perCurve: Int = 16): FloatArray {
        val out = FloatArray(n * perCurve * 2)
        var k = 0
        for (j in 0 until n) {
            val ia = (j + n - 1) % n
            for (s in 0 until perCurve) {
                val t = s.toFloat() / perCurve; val u = 1f - t
                val k0 = u * u; val k1 = 2f * w[j] * t * u; val k2 = t * t
                val d = k0 + k1 + k2
                out[k++] = (mx[ia] * k0 + px[j] * k1 + mx[j] * k2) / d
                out[k++] = (my[ia] * k0 + py[j] * k1 + my[j] * k2) / d
            }
        }
        return out
    }

    /**
     * Point mode needs every part of the outline visible from the center: walking along the outline,
     * the angle seen from the center must turn one way only and make exactly one full turn.
     */
    fun isStarShaped(): Boolean {
        val o = outline(24)
        val m = o.size / 2
        var total = 0.0
        var sign = 0
        for (i in 0 until m) {
            val a0 = kotlin.math.atan2(o[2 * i + 1].toDouble(), o[2 * i].toDouble())
            val i1 = (i + 1) % m
            val a1 = kotlin.math.atan2(o[2 * i1 + 1].toDouble(), o[2 * i1].toDouble())
            var d = a1 - a0
            if (d > Math.PI) d -= 2 * Math.PI
            if (d < -Math.PI) d += 2 * Math.PI
            if (abs(d) < 1e-9) continue
            val s = if (d > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
            total += d
        }
        return abs(abs(total) - 2 * Math.PI) < 0.05
    }

    /** Narrowest width through the center (sampled over 36 directions), for the minimum-size rule. */
    fun minWidth(): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until 36) {
            val a = Math.PI * i / 36
            val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
            best = minOf(best, radius(dx, dy) + radius(-dx, -dy))
        }
        return best
    }

    /** Area of the outline (for "smallest bubble wins" hit-testing). */
    fun area(): Float {
        val o = outline(8)
        val m = o.size / 2
        var a = 0f
        for (i in 0 until m) {
            val i1 = (i + 1) % m
            a += o[2 * i] * o[2 * i1 + 1] - o[2 * i1] * o[2 * i + 1]
        }
        return abs(a) / 2f
    }

    /**
     * Intersect the ray from the origin with every corner curve, keep the nearest hit.
     * Curve j: P(t) = (A(1-t)² + 2wB·t(1-t) + Ct²) / ((1-t)² + 2w·t(1-t) + t²),
     * A = previous midpoint, B = corner j, C = next midpoint.
     * The ray condition cross(dir, P(t)) = 0 reduces to a quadratic in t.
     */
    private fun hit(dx: Float, dy: Float, normalOut: FloatArray?): Float {
        var best = Float.MAX_VALUE
        var bestJ = -1; var bestT = 0f
        for (j in 0 until n) {
            val ia = (j + n - 1) % n
            val ax = mx[ia]; val ay = my[ia]
            val bx = px[j]; val by = py[j]
            val cx = mx[j]; val cy = my[j]
            val wj = w[j]
            val a = dx * ay - dy * ax
            val b = dx * by - dy * bx
            val c = dx * cy - dy * cx
            val qa = a - 2f * b * wj + c
            val qb = -2f * a + 2f * b * wj
            val qc = a
            var t0 = Float.NaN; var t1 = Float.NaN
            if (abs(qa) < 1e-9f) {
                if (abs(qb) > 1e-12f) t0 = -qc / qb
            } else {
                val disc = qb * qb - 4f * qa * qc
                if (disc >= -1e-6f) {
                    val s = sqrt(max(0f, disc))
                    t0 = (-qb + s) / (2f * qa); t1 = (-qb - s) / (2f * qa)
                }
            }
            for (t in floatArrayOf(t0, t1)) {
                if (t.isNaN() || t < -1e-4f || t > 1.0001f) continue
                val tc = t.coerceIn(0f, 1f)
                val u = 1f - tc
                val k0 = u * u; val k1 = 2f * wj * tc * u; val k2 = tc * tc
                val den = k0 + k1 + k2
                val hx = (ax * k0 + bx * k1 + cx * k2) / den
                val hy = (ay * k0 + by * k1 + cy * k2) / den
                if (hx * dx + hy * dy <= 0f) continue
                val len = sqrt(hx * hx + hy * hy)
                if (len < best) { best = len; bestJ = j; bestT = tc }
            }
        }
        if (bestJ < 0) {
            // Degenerate direction (should not happen for star-shaped outlines): fall back to farthest corner.
            var far = 1f
            for (j in 0 until n) far = max(far, sqrt(px[j] * px[j] + py[j] * py[j]))
            normalOut?.let { val l = max(1e-6f, sqrt(dx * dx + dy * dy)); it[0] = dx / l; it[1] = dy / l }
            return far
        }
        if (normalOut != null) curveNormal(bestJ, bestT, normalOut)
        return best
    }

    private fun curveNormal(j: Int, t: Float, out: FloatArray) {
        val ia = (j + n - 1) % n
        val ax = mx[ia]; val ay = my[ia]; val bx = px[j]; val by = py[j]; val cx = mx[j]; val cy = my[j]
        val wj = w[j]; val u = 1f - t
        val k0 = u * u; val k1 = 2f * wj * t * u; val k2 = t * t
        val d = k0 + k1 + k2
        val nx = ax * k0 + bx * k1 + cx * k2
        val ny = ay * k0 + by * k1 + cy * k2
        val dk0 = -2f * u; val dk1 = 2f * wj * (1f - 2f * t); val dk2 = 2f * t
        val dd = dk0 + dk1 + dk2
        val dnx = ax * dk0 + bx * dk1 + cx * dk2
        val dny = ay * dk0 + by * dk1 + cy * dk2
        var tx = dnx * d - nx * dd
        var ty = dny * d - ny * dd
        val tl = sqrt(tx * tx + ty * ty)
        if (tl < 1e-9f) { val l = max(1e-6f, sqrt(nx * nx + ny * ny)); out[0] = nx / l; out[1] = ny / l; return }
        tx /= tl; ty /= tl
        var ox = ty; var oy = -tx
        if (ox * nx + oy * ny < 0f) { ox = -ox; oy = -oy }   // make it point away from the center
        out[0] = ox; out[1] = oy
    }

    companion object {
        /** Max distance-field resolution for Area mode. */
        private const val DF = 160
        /** Build from file data, scaling normalized coordinates to an image of w×h pixels. */
        fun from(b: BubbleData, imgW: Int, imgH: Int): BubbleShape {
            val n = b.points.size
            val corners = FloatArray(n * 2)
            for (i in 0 until n) { corners[2 * i] = b.points[i][0] * imgW; corners[2 * i + 1] = b.points[i][1] * imgH }
            val curv = FloatArray(n) { i ->
                when {
                    b.curvature.size > 1 -> b.curvature.getOrElse(i) { 0f }
                    b.curvature.size == 1 -> b.curvature[0]
                    else -> 0f
                }
            }
            val c = if (b.useCurve) b.curve?.let { Falloff.Curve(it, Falloff.cap(b.ceiling, b.height)) } else null
            return BubbleShape(corners, curv, b.height, b.extent, b.area, c)
        }
    }
}

