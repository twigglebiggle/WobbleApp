package com.levkar.wobble.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import com.levkar.wobble.core.Finger
import com.levkar.wobble.core.TiltInput
import com.levkar.wobble.core.WblDocument
import com.levkar.wobble.core.WobbleMesh
import com.levkar.wobble.core.WobblePhysics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the wobbling image and runs the physics on the GL thread at a fixed 17 ms step.
 * All public methods except the GLSurfaceView callbacks must be called through queueEvent.
 *
 * [fill]: false = fit the whole image (viewer, letterboxed); true = cover the screen, center-cropped (wallpaper).
 * [onNeedsFrame]: on-demand rendering. Called from the GL thread while anything is still moving, with the
 * delay (ms) until the next physics step is due, so frames are drawn at the physics rate (~58/s) rather
 * than the screen's rate. When it stops being called, everything is at rest and the renderer is idle.
 */
class WobbleRenderer(
    private val density: Float,
    val tilt: TiltInput,
    fill: Boolean = false,
    private val onNeedsFrame: ((delayMs: Long) -> Unit)? = null,
) : GLSurfaceView.Renderer {

    private class Scene(val bitmap: Bitmap, val doc: WblDocument) {
        val w = bitmap.width
        val h = bitmap.height
        var physics = WobblePhysics.from(doc, w, h)
        var mesh: WobbleMesh? = null
        var positions: FloatArray? = null
        var posBuf: FloatBuffer? = null
        var tex = 0; var vboPos = 0; var vboUv = 0; var ibo = 0; var indexCount = 0
        var uploaded = false
        /** Editing (not live): the rest positions are already on the GPU, no need to redo them per frame. */
        var restDrawn = false
        // Mesh building runs off the GL thread (Lev: "load the image first, then the grid onto it").
        // Until the first mesh is ready the picture is drawn flat; later rebuilds keep the old mesh on screen.
        var needMesh = true
        var token = 0
        @Volatile var ready: WobbleMesh? = null
        @Volatile var readyToken = -1
        var shownOnce = false
    }

    private var scene: Scene? = null
    private var pending: Scene? = null
    private var viewW = 1; private var viewH = 1
    private var program = 0
    private var uScale = 0; private var uOffset = 0; private var uTex = 0
    private var lastTime = -1L
    private var accumulator = 0L
    private var physicsMoving = true
    private val tiltOut = FloatArray(2)
    private val fingers = HashMap<Int, Finger>()
    private val rings = TapRings(density)
    private var lastTouchTap = -1_000L
    private var lastCommandTap = -1_000L

    /** Time source (ms). null = the real clock; the GIF maker drives it frame by frame. */
    var clock: (() -> Long)? = null
    private fun nowMs() = clock?.invoke() ?: SystemClock.uptimeMillis()

    /** True once nothing moves and no ring is left (after a drawn frame). GL thread. */
    val settled get() = !physicsMoving && !rings.active && fingers.isEmpty()
    /** True once the scene's wobble grid is built and in use. GL thread. */
    val meshReady get() = pending == null && scene?.mesh != null
    /** The bubbles' oscillators (GIF maker: eases leftover motion at its time limit). GL thread. */
    val bodies get() = scene?.physics?.bodies ?: emptyList()
    /** Image px → screen px (current layout). GL thread. */
    fun imageToScreen(x: Float, y: Float) = floatArrayOf(xf.toScreenX(x), xf.toScreenY(y))

    /** A tap at an image point (GIF maker): the same kick as a finger tap, plus its ring. GL thread. */
    fun tapImage(x: Float, y: Float) {
        val s = scene ?: pending ?: return
        s.physics.touchGain = touchGain
        s.physics.tap(x, y)
        physicsMoving = true
        if (ringMode != TapRings.MODE_OFF) rings.spawn(x, y, nowMs())
    }

    /**
     * Editor heat map (Lev): colour over the picture showing where it moves. While editing = how far each spot
     * CAN move (all bubbles' reach, summed, after Freeze); while live (Preview) = how far it moves right now.
     */
    @Volatile var heat = false
    private var heatProgram = 0; private var hScale = 0; private var hOffset = 0; private var hMax = 0
    private var heatVbo = 0; private var heatVals: FloatArray? = null; private var heatBuf: FloatBuffer? = null
    private var heatFor: WobbleMesh? = null
    /** Colour scale: red = this piece's own hottest spots (98th percentile), not a fixed amount (Lev: "almost everything is red"). */
    private var heatMax = 0f
    /** Colour floor: the 5th percentile, so a big bubble moving everything a little reads as faint blue, not orange. */
    private var heatMin = 0f
    private var hMin = 0
    /** Mid-gesture (editor): keep the colour scale, so the change being made shows against the rest. */
    @Volatile var heatHold = false

    /** Feel (Settings): multipliers on tilt and touch, 1 = the original. */
    @Volatile var tiltGain = 1f
    @Volatile var touchGain = 1f

    /** Reads the rings/feel settings (any thread; values are picked up on the next frame). */
    fun loadSettings(c: android.content.Context) {
        val o = com.levkar.wobble.settings.Options
        ringMode = o.TAP_RINGS.get(c)
        rings.style = TapRings.Style.from(c)
        tiltGain = o.TILT.get(c) / 100f; touchGain = o.TOUCH.get(c) / 100f
    }

    /** Tap ring style: [TapRings.MODE_OFF], [TapRings.MODE_OVERLAY] or [TapRings.MODE_ON_IMAGE]. */
    var ringMode = TapRings.MODE_OVERLAY

    // fit-to-screen transform (image px, Y up → screen px)
    private var fitScale = 1f; private var fitOffX = 0f; private var fitOffY = 0f

    fun setScene(bitmap: Bitmap, doc: WblDocument) { pending = Scene(bitmap, doc) }

    /** Editor: new bubble data for the same image (keeps the texture, rebuilds physics and mesh). */
    fun updateDoc(doc: WblDocument) {
        val s = pending ?: scene ?: return
        val old = s.physics
        s.physics = WobblePhysics.from(doc, s.w, s.h)
        s.needMesh = true; s.restDrawn = false
        if (s === scene) layout(s)
        if (live && old.bodies.size == s.physics.bodies.size) {
            // Same bubbles, new values (live slider tuning in preview): keep them moving.
            for ((a, b) in old.bodies.zip(s.physics.bodies)) {
                b.offX = a.offX; b.offY = a.offY; b.velX = a.velX; b.velY = a.velY; b.impX = a.impX; b.impY = a.impY
            }
            fingers.clear(); physicsMoving = true
        } else { rings.clear(); fingers.clear() }
    }

    private val xf = ViewXform()

    /** false = fit the whole image, true = cover the screen (center-crop). GL thread. */
    var fill = fill
        set(v) { if (field != v) { field = v; scene?.let { layout(it) } } }          // view only: the mesh fits both

    /**
     * Wallpaper framing (zoom, pan, Fit/Fill, surround). null = the plain [fill] behaviour (viewer, editor).
     * GL thread.
     */
    var frame: com.levkar.wobble.wallpaper.Frame? = null
        set(v) {
            field = v
            scene?.let { layout(it) }
        }
    /** Home-screen page position 0..1 (wallpaper offsets), used by a framing with pages on. GL thread. */
    var pageX = 0.5f
        set(v) { field = v; scene?.let { layout(it) } }

    // Blurred, darkened copy of the picture behind it (framing surround "blur").
    private var bgTex = 0; private var bgVbo = 0; private var bgFor: Scene? = null
    private var bgW = 1; private var bgH = 1

    /** Editor zoom/pan on top of fit (screen px). */
    fun setView(zoom: Float, panX: Float, panY: Float) {
        xf.zoom = zoom; xf.panX = panX; xf.panY = panY
        scene?.let { layout(it) }
    }

    /**
     * false = editing: the image is drawn at rest, physics and tilt are ignored, nothing animates.
     * true = wobbling (viewer, wallpaper, editor preview).
     */
    var live = true
        set(v) {
            field = v
            scene?.restDrawn = false
            if (!v) scene?.let { s ->
                for (b in s.physics.bodies) { b.offX = 0f; b.offY = 0f; b.velX = 0f; b.velY = 0f; b.impX = 0f; b.impY = 0f }
                s.physics.fingers.clear(); fingers.clear(); rings.clear()
            }
            lastTime = -1L; accumulator = 0L
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VS, FS)
        uScale = GLES30.glGetUniformLocation(program, "u_scale")
        uOffset = GLES30.glGetUniformLocation(program, "u_offset")
        uTex = GLES30.glGetUniformLocation(program, "u_tex")
        scene?.let { it.uploaded = false; it.tex = 0; it.vboPos = 0 }   // new context: old GL ids are gone
        heatProgram = 0; heatVbo = 0; heatFor = null
        bgTex = 0; bgVbo = 0; bgFor = null; quadVbo = 0
        rings.onSurfaceCreated()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewW = width; viewH = height
        scene?.let { layout(it); it.needMesh = true }
    }

    private fun layout(s: Scene) {
        xf.imgW = s.w; xf.imgH = s.h; xf.viewW = viewW; xf.viewH = viewH
        val f = frame
        if (f != null) f.apply(xf, pageX) else xf.fill = fill
        fitScale = xf.scale
        fitOffX = xf.left
        fitOffY = xf.bottomGl
        s.physics.restEpsilon = WobblePhysics.REST_SCREEN_PX / fitScale
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let { p ->
            scene?.let { release(it) }
            scene = p; pending = null; fingers.clear(); rings.clear(); lastTime = -1L; physicsMoving = true
            layout(p)
        }
        val fr = frame
        val bg = fr?.background ?: 0xFF000000.toInt()
        GLES30.glClearColor(android.graphics.Color.red(bg) / 255f, android.graphics.Color.green(bg) / 255f, android.graphics.Color.blue(bg) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val s = scene ?: return
        if (fr != null && fr.surround == com.levkar.wobble.wallpaper.Frame.BLUR) drawBlurBackground(s)
        s.ready?.let { m -> if (s.readyToken == s.token) adopt(s, m); s.ready = null }
        if (s.needMesh) { s.needMesh = false; startBuild(s) }
        if (s.tex == 0) uploadTexture(s)
        if (s.mesh != null && !s.uploaded) upload(s)
        if (!s.shownOnce) { s.shownOnce = true; onFirstImage?.invoke() }

        // physics: fixed 17 ms steps, at most 10 per frame
        val now = nowMs()
        val woke = lastTime < 0
        if (woke) lastTime = now
        accumulator = (accumulator + now - lastTime).coerceAtMost(WobblePhysics.STEP_MS * WobblePhysics.MAX_STEPS_PER_FRAME)
        lastTime = now
        var kicked = woke
        if (!live) { tilt.drain(tiltOut); accumulator = 0L; kicked = false }
        else if (tilt.drain(tiltOut)) { s.physics.tilt(tiltOut[0] * tiltGain, tiltOut[1] * tiltGain); kicked = true }
        s.physics.touchGain = touchGain
        var stepped = false; var stepMoving = false
        while (accumulator >= WobblePhysics.STEP_MS) {
            if (s.physics.step()) stepMoving = true
            stepped = true
            accumulator -= WobblePhysics.STEP_MS
        }
        // A frame can be shorter than a physics step (120 Hz screens): no step ran, so keep the last verdict.
        if (stepped) physicsMoving = stepMoving
        if (live && (kicked || physicsMoving || s.physics.fingers.isNotEmpty() || rings.active))
            onNeedsFrame?.invoke(max(1L, WobblePhysics.STEP_MS - accumulator))
        else { lastTime = -1L; accumulator = 0L }   // settled: go idle until the next input

        val mesh = s.mesh ?: run { drawFlat(s); return }       // grid still building: the picture, flat
        val pos = s.positions!!; val buf = s.posBuf!!
        if (live || !s.restDrawn) {                      // editing: the image is at rest, upload it once
            mesh.deform(pos)
            buf.clear(); buf.put(pos); buf.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vboPos)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, pos.size * 4, buf)
            s.restDrawn = !live
        }

        GLES30.glUseProgram(program)
        // image px → NDC: x' = x*fit + off, then to [-1,1]; Y up in both image and NDC
        GLES30.glUniform2f(uScale, 2f * fitScale / viewW, 2f * fitScale / viewH)
        GLES30.glUniform2f(uOffset, 2f * fitOffX / viewW - 1f, 2f * fitOffY / viewH - 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.tex)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vboPos)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vboUv)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.ibo)
        // Clip to the image rect: the mesh extends past the frame (see WobbleMesh edge behavior).
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val sx0 = max(0, fitOffX.toInt()); val sy0 = max(0, fitOffY.toInt())
        val sx1 = min(viewW, Math.round(fitOffX + s.w * fitScale)); val sy1 = min(viewH, Math.round(fitOffY + s.h * fitScale))
        GLES30.glScissor(sx0, sy0, sx1 - sx0, sy1 - sy0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, s.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        if (heat) drawHeat(s, mesh, pos)
        rings.draw(ringMode, now, 2f * fitScale / viewW, 2f * fitScale / viewH,
            2f * fitOffX / viewW - 1f, 2f * fitOffY / viewH - 1f, fitScale, mesh, pos)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    /** The heat colours, drawn on the same grid (so they ride the wobble), blended over the picture. */
    private fun drawHeat(s: Scene, mesh: WobbleMesh, pos: FloatArray) {
        if (heatProgram == 0) {
            heatProgram = buildProgram(HEAT_VS, HEAT_FS)
            hScale = GLES30.glGetUniformLocation(heatProgram, "u_scale"); hOffset = GLES30.glGetUniformLocation(heatProgram, "u_offset")
            hMax = GLES30.glGetUniformLocation(heatProgram, "u_max"); hMin = GLES30.glGetUniformLocation(heatProgram, "u_min")
            val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); heatVbo = ids[0]
        }
        var vals = heatVals
        if (vals == null || vals.size != mesh.vertexCount) {
            vals = FloatArray(mesh.vertexCount); heatVals = vals
            heatBuf = ByteBuffer.allocateDirect(mesh.vertexCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            heatFor = null
        }
        val moving = live
        if (moving) mesh.motionHeat(pos, vals)
        else if (heatFor !== mesh) {
            mesh.reachHeat(vals)
            if (!heatHold || heatMax <= 0f) {
                heatMax = percentileInImage(mesh, vals, 0.98f).coerceAtLeast(minOf(s.w, s.h) * 0.004f)
                heatMin = percentileInImage(mesh, vals, 0.05f).coerceAtMost(heatMax * 0.8f)
            }
        }
        if (moving || heatFor !== mesh) {
            val hb = heatBuf!!; hb.clear(); hb.put(vals); hb.flip()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, heatVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vals.size * 4, hb, GLES30.GL_DYNAMIC_DRAW)
            heatFor = if (moving) null else mesh
        }
        GLES30.glUseProgram(heatProgram)
        GLES30.glUniform2f(hScale, 2f * fitScale / viewW, 2f * fitScale / viewH)
        GLES30.glUniform2f(hOffset, 2f * fitOffX / viewW - 1f, 2f * fitOffY / viewH - 1f)
        // Moving now: a poke reaches only part of the full swing, so the scale is a third of the reach scale.
        val reachMax = if (heatMax > 0f) heatMax else minOf(s.w, s.h) * 0.055f
        GLES30.glUniform1f(hMax, if (moving) reachMax * 0.35f else reachMax)
        GLES30.glUniform1f(hMin, if (moving) 0f else heatMin)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vboPos)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, heatVbo)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.ibo)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, s.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glUseProgram(program)
    }

    /** The value [q] of the way up the heat inside the picture (moving spots only), from a sample of the grid. */
    private fun percentileInImage(mesh: WobbleMesh, vals: FloatArray, q: Float): Float {
        val pick = ArrayList<Float>()
        val step = maxOf(1, vals.size / 20000)
        var i = 0
        while (i < vals.size) {
            val x = mesh.rest[2 * i]; val y = mesh.rest[2 * i + 1]
            if (vals[i] > 0f && x >= 0f && y >= 0f && x <= mesh.imgW && y <= mesh.imgH) pick += vals[i]
            i += step
        }
        if (pick.isEmpty()) return 0f
        pick.sort()
        return pick[((pick.size - 1) * q).toInt()]
    }

    /** Fills the screen with a tiny, darkened copy of the picture, stretched (bilinear) = a soft blur. */
    private fun drawBlurBackground(s: Scene) {
        if (xf.left <= 0.5f && xf.top <= 0.5f && xf.left + s.w * xf.scale >= viewW - 0.5f && xf.top + s.h * xf.scale >= viewH - 0.5f) return
        if (bgFor !== s || bgTex == 0) {
            val ids = IntArray(1)
            if (bgTex == 0) { GLES30.glGenTextures(1, ids, 0); bgTex = ids[0] }
            if (bgVbo == 0) { GLES30.glGenBuffers(1, ids, 0); bgVbo = ids[0] }
            bgW = 18; bgH = maxOf(1, Math.round(18f * s.h / s.w))
            val small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(s.bitmap, bgW * 4, bgH * 4, true), bgW, bgH, true)
            val dark = Bitmap.createBitmap(bgW, bgH, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(dark).drawBitmap(small, 0f, 0f, android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
            })
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, dark, 0)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            bgFor = s
        }
        // Cover the screen: crop the texture to the screen's aspect. Interleaved x, y (NDC), u, v.
        val va = viewW.toFloat() / viewH; val ia = s.w.toFloat() / s.h
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        if (ia > va) { val k = va / ia; u0 = (1 - k) / 2; u1 = 1 - u0 } else { val k = ia / va; v0 = (1 - k) / 2; v1 = 1 - v0 }
        val q = floatArrayOf(-1f, -1f, u0, v1,  1f, -1f, u1, v1,  -1f, 1f, u0, v0,  1f, 1f, u1, v0)
        val fb = ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(q); fb.flip()
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, 1f, 1f); GLES30.glUniform2f(uOffset, 0f, 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTex); GLES30.glUniform1i(uTex, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bgVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, q.size * 4, fb, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Called once per scene from the GL thread, right after its picture is first drawn (to drop a placeholder). */
    var onFirstImage: (() -> Unit)? = null

    /**
     * Builds the mesh on a background thread. Cell size comes from the Fill scale (the larger of Fit/Fill), never
     * from zoom: one mesh serves both, so switching Fit ↔ Fill only moves the view. A newer request makes older
     * results stale (token), so only the latest bubbles' mesh is ever adopted.
     */
    private fun startBuild(s: Scene) {
        val token = ++s.token
        val physics = s.physics
        val fillScale = maxOf(viewW.toFloat() / s.w, viewH.toFloat() / s.h)
        meshThread.execute {
            if (token != s.token) return@execute                      // a newer request is queued: skip this one
            val mesh = runCatching { WobbleMesh.create(s.w, s.h, fillScale, physics) }.getOrNull() ?: return@execute
            if (token != s.token) return@execute
            s.readyToken = token; s.ready = mesh
            onNeedsFrame?.invoke(0L)
        }
    }

    private fun adopt(s: Scene, mesh: WobbleMesh) {
        s.mesh = mesh
        s.positions = FloatArray(mesh.vertexCount * 2)
        s.posBuf = ByteBuffer.allocateDirect(mesh.vertexCount * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        s.uploaded = false; s.restDrawn = false
    }

    // Flat picture (a single quad), shown while the first mesh builds.
    private var quadVbo = 0
    private fun drawFlat(s: Scene) {
        if (quadVbo == 0) { val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); quadVbo = ids[0] }
        val w = s.w.toFloat(); val h = s.h.toFloat()
        val q = floatArrayOf(0f, 0f, 0f, 1f,  w, 0f, 1f, 1f,  0f, h, 0f, 0f,  w, h, 1f, 0f)   // x, y (image px, Y up), u, v
        val fb = ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(q); fb.flip()
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, 2f * fitScale / viewW, 2f * fitScale / viewH)
        GLES30.glUniform2f(uOffset, 2f * fitOffX / viewW - 1f, 2f * fitOffY / viewH - 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.tex); GLES30.glUniform1i(uTex, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, q.size * 4, fb, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun upload(s: Scene) {
        val mesh = s.mesh!!
        s.restDrawn = false                              // positions buffer is (re)allocated empty
        val ids = IntArray(3)
        if (s.vboPos == 0) { GLES30.glGenBuffers(3, ids, 0); s.vboPos = ids[0]; s.vboUv = ids[1]; s.ibo = ids[2] }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vboPos)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertexCount * 8, null, GLES30.GL_DYNAMIC_DRAW)
        val uvBuf = ByteBuffer.allocateDirect(mesh.uv.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.uv)
        uvBuf.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vboUv)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.uv.size * 4, uvBuf, GLES30.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.indices)
        ib.flip()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        s.indexCount = mesh.indices.size
        s.uploaded = true
    }

    private fun uploadTexture(s: Scene) {
        val ids = IntArray(1)
        run {
            GLES30.glGenTextures(1, ids, 0); s.tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.tex)
            val max = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
            val bmp = if (s.w > max[0] || s.h > max[0]) {
                val k = max[0].toFloat() / maxOf(s.w, s.h)
                Bitmap.createScaledBitmap(s.bitmap, (s.w * k).toInt(), (s.h * k).toInt(), true)
            } else s.bitmap
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
    }

    private fun release(s: Scene) {
        if (s.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(s.tex), 0)
        if (s.vboPos != 0) GLES30.glDeleteBuffers(3, intArrayOf(s.vboPos, s.vboUv, s.ibo), 0)
    }

    // ---- touch, in screen pixels (Y down) ----

    private fun toImageX(sx: Float) = xf.toImageX(sx)
    @Suppress("UNUSED_PARAMETER") private fun toImageY(sy: Float, s: Scene) = xf.toImageY(sy)

    fun touchDown(id: Int, sx: Float, sy: Float, timeMs: Long) {
        val s = scene ?: return
        if (!live) return
        val radius = WobblePhysics.FINGER_RADIUS_DP * density / fitScale
        fingers[id] = s.physics.fingerDown(toImageX(sx), toImageY(sy, s), radius, timeMs)
    }

    fun touchMove(id: Int, sx: Float, sy: Float) {
        val s = scene ?: return
        fingers[id]?.let { s.physics.fingerMove(it, toImageX(sx), toImageY(sy, s)) }
    }

    fun touchUp(id: Int, sx: Float, sy: Float, timeMs: Long) {
        val s = scene ?: return
        val f = fingers.remove(id) ?: return
        val x = toImageX(sx); val y = toImageY(sy, s)
        s.physics.fingerUp(f, x, y, timeMs)
        if (timeMs - f.downTimeMs < WobblePhysics.TAP_MS) {     // the same "quick tap" the physics reacts to
            lastTouchTap = nowMs()
            if (ringMode != TapRings.MODE_OFF && lastTouchTap - lastCommandTap > 250L) rings.spawn(x, y, lastTouchTap)
        }
    }

    /**
     * A tap reported by the launcher instead of raw touches (some launchers only send this).
     * Ignored if the same tap already arrived as touch events.
     */
    fun commandTap(sx: Float, sy: Float) {
        val s = scene ?: return
        val now = nowMs()
        if (now - lastTouchTap < 250L) return
        lastCommandTap = now
        val x = toImageX(sx); val y = toImageY(sy, s)
        s.physics.touchGain = touchGain
        s.physics.tap(x, y)
        if (ringMode != TapRings.MODE_OFF) rings.spawn(x, y, now)
    }

    companion object {
        /** One background thread for mesh builds (the build itself fans out over all cores). */
        private val meshThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "wobble-mesh-build").apply { isDaemon = true } }

        private const val VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
uniform vec2 u_scale;
uniform vec2 u_offset;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos * u_scale + u_offset, 0.0, 1.0);
}"""
        private const val FS = """#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
out vec4 color;
void main() { color = texture(u_tex, v_uv); }"""

        private const val HEAT_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in float a_heat;
uniform vec2 u_scale;
uniform vec2 u_offset;
out float v_heat;
void main() {
    v_heat = a_heat;
    gl_Position = vec4(a_pos * u_scale + u_offset, 0.0, 1.0);
}"""
        // still (clear) → blue → light blue → amber → orange → red; the picture is dimmed a little under it.
        private const val HEAT_FS = """#version 300 es
precision mediump float;
in float v_heat;
uniform float u_max;
uniform float u_min;
out vec4 color;
vec4 ramp(float v) {
    vec4 c0 = vec4(0.16, 0.35, 1.0, 0.0);
    vec4 c1 = vec4(0.16, 0.43, 1.0, 0.35);
    vec4 c2 = vec4(0.24, 0.67, 1.0, 0.59);
    vec4 c3 = vec4(1.0, 0.75, 0.24, 0.71);
    vec4 c4 = vec4(1.0, 0.35, 0.2, 0.78);
    vec4 c5 = vec4(1.0, 0.16, 0.24, 0.86);
    if (v < 0.08) return mix(c0, c1, v / 0.08);
    if (v < 0.35) return mix(c1, c2, (v - 0.08) / 0.27);
    if (v < 0.6) return mix(c2, c3, (v - 0.35) / 0.25);
    if (v < 0.85) return mix(c3, c4, (v - 0.6) / 0.25);
    return mix(c4, c5, clamp((v - 0.85) / 0.15, 0.0, 1.0));
}
void main() {
    // Anything that moves at all starts at faint blue; the range between the piece's 5th and 98th percentile spreads the colours.
    float v = v_heat <= 0.0005 * u_max ? 0.0 : 0.08 + 0.92 * clamp((v_heat - u_min) / max(u_max - u_min, 0.0001), 0.0, 1.0);
    vec4 h = ramp(v);
    float a = 1.0 - 0.7 * (1.0 - h.a);
    color = vec4(h.rgb * h.a / max(a, 0.001), a);
}"""

        fun buildProgram(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src); GLES30.glCompileShader(sh)
                val ok = IntArray(1); GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw RuntimeException("Shader: " + GLES30.glGetShaderInfoLog(sh))
                return sh
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Link: " + GLES30.glGetProgramInfoLog(p))
            return p
        }
    }
}
