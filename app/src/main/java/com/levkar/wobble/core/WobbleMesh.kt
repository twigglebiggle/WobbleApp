package com.levkar.wobble.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Uniform grid over the image, extended past the frame wherever a bubble crosses the border.
 * Each vertex's position each frame is
 * rest + Σ(bubble offset × bubble weight at the vertex) + Σ(finger offset × finger weight).
 * Coordinates: image pixels, Y up.
 *
 * Edge behavior (same as the original): the part of the mesh outside the frame has UVs outside 0..1,
 * which the texture clamps to the border pixels. When a bubble pulls that part inward, the edge
 * colors get stretched into view instead of leaving a gap. Drawing is clipped to the image rect.
 */
class WobbleMesh(
    val imgW: Int, val imgH: Int,
    private val cellW: Float, private val cellH: Float,
    private val physics: WobblePhysics,
) {
    /** Grid origin and size (may extend beyond the image). */
    val x0: Float; val y0: Float
    val cols: Int; val rows: Int

    val vertexCount: Int
    val rest: FloatArray
    val uv: FloatArray
    /** Max bubble weight at each vertex; scales finger deformation like the original's vertex z. */
    val z: FloatArray
    val indices: IntArray
    private val bodyVerts: Array<IntArray>
    private val bodyWeights: Array<FloatArray>
    private val fingerCache = HashMap<Finger, Pair<IntArray, FloatArray>>()
    /**
     * Freeze zones: per-vertex share of movement that survives, Π(1 − hold × weight) over the zones covering it.
     * Only vertices below 1 are listed. Applied after bubbles and fingers, so a frozen part ignores both.
     */
    private val keepVerts: IntArray
    private val keep: FloatArray

    init {
        // Domain = image ∪ every bubble's bounds, capped at half an image beyond each side.
        var minX = 0f; var minY = 0f; var maxX = imgW.toFloat(); var maxY = imgH.toFloat()
        for (b in physics.bodies) {
            minX = min(minX, b.cx + b.shape.minX); maxX = max(maxX, b.cx + b.shape.maxX)
            minY = min(minY, b.cy + b.shape.minY); maxY = max(maxY, b.cy + b.shape.maxY)
        }
        minX = max(minX, -0.5f * imgW); maxX = min(maxX, 1.5f * imgW)
        minY = max(minY, -0.5f * imgH); maxY = min(maxY, 1.5f * imgH)
        // Snap to whole cells so the image's own grid lines stay where they'd be without the extension.
        val c0 = floor(minX / cellW).toInt(); val c1 = ceil(maxX / cellW).toInt()
        val r0 = floor(minY / cellH).toInt(); val r1 = ceil(maxY / cellH).toInt()
        x0 = c0 * cellW; y0 = r0 * cellH
        cols = c1 - c0; rows = r1 - r0

        vertexCount = (cols + 1) * (rows + 1)
        rest = FloatArray(vertexCount * 2)
        uv = FloatArray(vertexCount * 2)
        z = FloatArray(vertexCount)
        for (r in 0..rows) for (c in 0..cols) {
            val i = r * (cols + 1) + c
            val x = x0 + c * cellW; val y = y0 + r * cellH
            rest[2 * i] = x; rest[2 * i + 1] = y
            uv[2 * i] = x / imgW; uv[2 * i + 1] = 1f - y / imgH     // bitmap row 0 is the top
        }
        indices = IntArray(cols * rows * 6)
        var k = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val i0 = r * (cols + 1) + c; val i1 = i0 + 1; val i2 = i0 + cols + 1; val i3 = i2 + 1
            indices[k++] = i0; indices[k++] = i1; indices[k++] = i2
            indices[k++] = i1; indices[k++] = i3; indices[k++] = i2
        }
        // Each bubble's weights are independent: computed in parallel, one task per bubble, then merged.
        val bodies = physics.bodies
        val results = parallel(bodies.size) { bi ->
            val b = bodies[bi]; val s = b.shape
            val vs = IntList(); val ws = FloatList()
            forCells(b.cx + s.minX, b.cy + s.minY, b.cx + s.maxX, b.cy + s.maxY) { i ->
                val wgt = s.weightIfInside(rest[2 * i] - b.cx, rest[2 * i + 1] - b.cy)
                if (wgt > 0f) { vs.add(i); ws.add(wgt) }
            }
            vs.toArray() to ws.toArray()
        }
        bodyVerts = Array(bodies.size) { results[it].first }
        bodyWeights = Array(bodies.size) { results[it].second }
        for (bi in bodies.indices) {
            val vs = bodyVerts[bi]; val ws = bodyWeights[bi]
            for (k in vs.indices) if (ws[k] > z[vs[k]]) z[vs[k]] = ws[k]
        }
        val keepMap = HashMap<Int, Float>()
        for (z0 in physics.freezes) {
            val s = z0.shape
            forCells(z0.cx + s.minX, z0.cy + s.minY, z0.cx + s.maxX, z0.cy + s.maxY) { i ->
                val dx = rest[2 * i] - z0.cx; val dy = rest[2 * i + 1] - z0.cy
                val wgt = s.weightIfInside(dx, dy).coerceIn(0f, 1f)
                if (wgt > 0f) keepMap[i] = (keepMap[i] ?: 1f) * (1f - z0.hold * wgt)
            }
        }
        keepVerts = keepMap.keys.toIntArray(); keep = FloatArray(keepVerts.size) { keepMap.getValue(keepVerts[it]) }
    }

    private inline fun forCells(ax: Float, ay: Float, bx: Float, by: Float, f: (Int) -> Unit) {
        val c0 = max(0, floor((ax - x0) / cellW).toInt()); val c1 = min(cols, ceil((bx - x0) / cellW).toInt())
        val r0 = max(0, floor((ay - y0) / cellH).toInt()); val r1 = min(rows, ceil((by - y0) / cellH).toInt())
        for (r in r0..r1) for (c in c0..c1) f(r * (cols + 1) + c)
    }

    fun deform(out: FloatArray) {
        System.arraycopy(rest, 0, out, 0, rest.size)
        for ((bi, b) in physics.bodies.withIndex()) {
            val ox = b.offX; val oy = b.offY
            if (ox * ox + oy * oy < 1e-8f) continue
            val vs = bodyVerts[bi]; val ws = bodyWeights[bi]
            for (k in vs.indices) {
                val i = vs[k]; val wgt = ws[k]
                out[2 * i] += ox * wgt; out[2 * i + 1] += oy * wgt
            }
        }
        fingerCache.keys.retainAll(physics.fingers.toSet())
        for (f in physics.fingers) {
            val (vs, ws) = fingerCache.getOrPut(f) { fingerVerts(f) }
            for (k in vs.indices) {
                val i = vs[k]; val wgt = ws[k]
                out[2 * i] += f.offX * wgt; out[2 * i + 1] += f.offY * wgt
            }
        }
        for (j in keepVerts.indices) {
            val i = keepVerts[j]; val m = keep[j]
            out[2 * i] = rest[2 * i] + (out[2 * i] - rest[2 * i]) * m
            out[2 * i + 1] = rest[2 * i + 1] + (out[2 * i + 1] - rest[2 * i + 1]) * m
        }
    }

    /**
     * Heat map, "how far can it move": per vertex Σ(bubble weight × how far that bubble can travel, image px), after Freeze.
     * Overlaps add up, the same way their motion does. Fills [out] (one value per vertex).
     */
    fun reachHeat(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        val bodies = physics.bodies                          // the ones this grid was built for
        for (bi in bodyVerts.indices) {
            val b = bodies[bi]
            val r = (0.05f + 0.45f * b.amp) * (b.shape.radius(1f, 0f) + b.shape.radius(0f, 1f)) / 2f   // how far its wall lets it travel
            val vs = bodyVerts[bi]; val ws = bodyWeights[bi]
            for (k in vs.indices) out[vs[k]] += ws[k] * r
        }
        for (j in keepVerts.indices) out[keepVerts[j]] *= keep[j]
    }

    /** Heat map, "moving now": per vertex distance from rest in the last [deform] result. */
    fun motionHeat(deformed: FloatArray, out: FloatArray) {
        for (i in out.indices) {
            val dx = deformed[2 * i] - rest[2 * i]; val dy = deformed[2 * i + 1] - rest[2 * i + 1]
            out[i] = kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    /**
     * Displacement of the deformed mesh at an arbitrary image point (bilinear between grid vertices),
     * so overlays can ride the wobble. [deformed] is the array last filled by [deform]. Zero outside the grid.
     */
    fun displacementAt(deformed: FloatArray, x: Float, y: Float, out: FloatArray) {
        val gx = (x - x0) / cellW; val gy = (y - y0) / cellH
        if (gx < 0f || gy < 0f || gx > cols || gy > rows) { out[0] = 0f; out[1] = 0f; return }
        val c = min(cols - 1, gx.toInt()); val r = min(rows - 1, gy.toInt())
        val fx = gx - c; val fy = gy - r
        val i00 = r * (cols + 1) + c; val i10 = i00 + 1; val i01 = i00 + cols + 1; val i11 = i01 + 1
        for (k in 0..1) {
            val d00 = deformed[2 * i00 + k] - rest[2 * i00 + k]; val d10 = deformed[2 * i10 + k] - rest[2 * i10 + k]
            val d01 = deformed[2 * i01 + k] - rest[2 * i01 + k]; val d11 = deformed[2 * i11 + k] - rest[2 * i11 + k]
            out[k] = (d00 * (1 - fx) + d10 * fx) * (1 - fy) + (d01 * (1 - fx) + d11 * fx) * fy
        }
    }

    private fun fingerVerts(f: Finger): Pair<IntArray, FloatArray> {
        val rad = f.radius; val r2 = rad * rad
        val vs = ArrayList<Int>(); val ws = ArrayList<Float>()
        forCells(f.ox - rad, f.oy - rad, f.ox + rad, f.oy + rad) { i ->
            val dx = rest[2 * i] - f.ox; val dy = rest[2 * i + 1] - f.oy
            val d2 = dx * dx + dy * dy
            if (d2 < r2) {
                val wgt = (1f - d2 / r2) * z[i]
                if (wgt > 0f) { vs += i; ws += wgt }
            }
        }
        return vs.toIntArray() to ws.toFloatArray()
    }

    /** Growable primitive lists (no boxing: the mesh can have ~150k vertices per big bubble). */
    private class IntList { var a = IntArray(256); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n) }
    private class FloatList { var a = FloatArray(256); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n) }

    companion object {
        private val pool by lazy {
            java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) { r ->
                Thread(r, "wobble-mesh").apply { isDaemon = true }
            }
        }

        /** Runs [f] for 0 until [n] on all cores and returns the results in order. */
        private fun <T> parallel(n: Int, f: (Int) -> T): List<T> =
            if (n <= 1) List(n) { f(it) }
            else pool.invokeAll((0 until n).map { i -> java.util.concurrent.Callable { f(i) } }).map { it.get() }

        /** Grid sized so a cell is about [cellPx] screen pixels when the image is drawn at [displayScale]. */
        fun create(imgW: Int, imgH: Int, displayScale: Float, physics: WobblePhysics, cellPx: Float = 6f): WobbleMesh {
            val cols = (imgW * displayScale / cellPx).toInt().coerceIn(8, 512)
            val rows = (imgH * displayScale / cellPx).toInt().coerceIn(8, 1024)
            return WobbleMesh(imgW, imgH, imgW.toFloat() / cols, imgH.toFloat() / rows, physics)
        }
    }
}
