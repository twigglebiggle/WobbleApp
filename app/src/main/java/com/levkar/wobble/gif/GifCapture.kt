package com.levkar.wobble.gif

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import com.levkar.wobble.core.Body
import com.levkar.wobble.core.TiltInput
import com.levkar.wobble.core.WblDocument
import com.levkar.wobble.core.WobblePhysics
import com.levkar.wobble.render.TapRings
import com.levkar.wobble.render.WobbleRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The GIF maker's engine. Renders the piece off-screen with the same renderer as the viewer, on a clock it
 * drives itself: still frame → one tap → fixed steps until every bubble has snapped to rest (motion under ¼ px
 * at the GIF's size) and the ring is gone. That frame is the still picture again, so it is dropped and the
 * GIF loops straight back to frame 0: no seam. Runs on the calling (background) thread.
 */
class GifCapture(private val spec: Spec) {

    data class Spec(
        val bitmap: Bitmap, val doc: WblDocument,
        val outW: Int, val outH: Int,
        val fill: Boolean, val zoom: Float, val panX: Float, val panY: Float,   // framing; pan in GIF pixels
        val tapX: Float, val tapY: Float,                     // image px, Y up
        val frameMs: Int, val stillMs: Int,
        val ringMode: Int, val ringStyle: TapRings.Style,
        val touchGain: Float, val density: Float,              // density already scaled to the GIF's size
        /** Ending: once every bubble's swing is under this many GIF px, it eases to rest in [TAIL_EASE_MS]. 0 = wait for ¼ px. */
        val tailPx: Float = 0f,
    )

    class Result(val file: File, val frames: Int, val durationMs: Int, val width: Int, val height: Int)

    @Volatile var cancelled = false

    /** [progress] gets (frames rendered, expected total). Throws on failure. */
    fun run(out: File, progress: (Int, Int) -> Unit): Result {
        val w = spec.outW; val h = spec.outH
        val egl = Egl(w, h)
        try {
            var now = 100_000L
            val r = WobbleRenderer(spec.density, TiltInput(), spec.fill)
            r.clock = { now }
            r.onSurfaceCreated(null, null)
            r.onSurfaceChanged(null, w, h)
            r.ringMode = spec.ringMode
            r.tiltGain = 0f; r.touchGain = spec.touchGain
            r.setScene(spec.bitmap, spec.doc)
            r.setView(spec.zoom, spec.panX, spec.panY)
            // Wait for the wobble grid (built on its own thread), drawing at a frozen clock.
            val t0 = System.currentTimeMillis()
            while (!r.meshReady) {
                r.onDrawFrame(null)
                if (cancelled) throw InterruptedException()
                if (System.currentTimeMillis() - t0 > 60_000) error("the wobble grid didn't build")
                Thread.sleep(8)
            }
            r.onDrawFrame(null)
            val still = read(w, h, IntArray(w * h))
            val frameBuf = IntArray(w * h)
            val expected = estimateFrames()

            val palette = Palette.build(still, if (spec.ringMode != TapRings.MODE_OFF) intArrayOf(spec.ringStyle.color) else IntArray(0))
            val cs = spec.frameMs / 10
            out.outputStream().buffered(1 shl 16).use { os ->
                val gif = GifAssembler(os, w, h, palette)
                gif.add(still, cs + spec.stillMs / 10)
                r.tapImage(spec.tapX, spec.tapY)
                val end = Ending(spec.frameMs, spec.tailPx, scaleOf(spec))
                var t = 0; var n = 1
                while (true) {
                    if (cancelled) throw InterruptedException()
                    t += spec.frameMs; now += spec.frameMs
                    end.before(r.bodies, t)
                    r.onDrawFrame(null)
                    if (r.settled) break                          // back to the still picture: the loop closes here
                    gif.add(read(w, h, frameBuf), cs)
                    n++
                    end.after(r.bodies, t)
                    progress(n, maxOf(expected, n + 1))
                }
                gif.finish()
                return Result(out, n, n * spec.frameMs + spec.stillMs, w, h)
            }
        } finally { egl.release() }
    }

    /**
     * How the motion ends (Lev: the long, barely-visible tail read as a pause). Two rules, whichever comes first:
     * - tail: once every bubble's swing (its amplitude, not just where it is this instant) is under [tailPx] GIF px,
     *   it eases to rest over [TAIL_EASE_MS];
     * - limit: from [EASE_FROM_MS] whatever still moves eases so it's at rest by [LIMIT_MS].
     * Either way it ends exactly at rest, so the loop still closes on the still picture.
     */
    class Ending(private val frameMs: Int, private val tailPx: Float, private val scale: Float) {
        private var from = EASE_FROM_MS; private var until = LIMIT_MS
        fun before(bodies: List<Body>, t: Int) {
            if (t < from) return
            val left = (until - t).coerceAtLeast(0)
            val k = left.toFloat() / (left + frameMs)
            for (b in bodies) { b.offX *= k; b.offY *= k; b.velX *= k; b.velY *= k; b.impX = 0f; b.impY = 0f }
        }
        fun after(bodies: List<Body>, t: Int) {
            if (tailPx <= 0f || until != LIMIT_MS) return
            var amp2 = 0f
            for (b in bodies) {
                val w2 = 1f + 300f * b.sti2                            // spring: the swing's angular speed²
                amp2 = maxOf(amp2, b.offX * b.offX + b.offY * b.offY + (b.velX * b.velX + b.velY * b.velY) / w2)
            }
            if (kotlin.math.sqrt(amp2) * scale < tailPx) { from = t; until = minOf(LIMIT_MS, t + TAIL_EASE_MS) }
        }
    }

    /** Frames the loop will take, from the physics alone (fast; the same rules the capture stops on). */
    fun estimateFrames(): Int = estimate(spec)

    private fun restEpsilon() = restEpsilon(spec)

    private val pixBuf by lazy { ByteBuffer.allocateDirect(spec.outW * spec.outH * 4).order(ByteOrder.nativeOrder()) }
    private val bytes by lazy { ByteArray(spec.outW * spec.outH * 4) }

    /** The frame as 0xRRGGBB, rows top-down (GL reads bottom-up). */
    private fun read(w: Int, h: Int, px: IntArray): IntArray {
        pixBuf.clear()
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixBuf)
        pixBuf.get(bytes)
        for (y in 0 until h) {
            var s = (h - 1 - y) * w * 4; val o = y * w
            for (x in 0 until w) {
                px[o + x] = ((bytes[s].toInt() and 0xFF) shl 16) or ((bytes[s + 1].toInt() and 0xFF) shl 8) or (bytes[s + 2].toInt() and 0xFF)
                s += 4
            }
        }
        return px
    }

    /** An off-screen GLES 3 context with a w×h framebuffer bound. */
    private class Egl(w: Int, h: Int) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: android.opengl.EGLContext
        val surface: android.opengl.EGLSurface
        val fbo = IntArray(1); val rb = IntArray(1)
        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL init failed" }
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no GLES 3 config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL makeCurrent failed" }
            GLES30.glGenFramebuffers(1, fbo, 0); GLES30.glGenRenderbuffers(1, rb, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rb[0])
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete" }
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        }
        fun release() {
            GLES30.glDeleteFramebuffers(1, fbo, 0); GLES30.glDeleteRenderbuffers(1, rb, 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context)
        }
    }

    companion object {
        /** Image px → GIF px scale for a spec (Fit/Fill base × zoom). */
        fun scaleOf(s: Spec): Float {
            val w = s.bitmap.width.toFloat(); val h = s.bitmap.height.toFloat()
            val base = if (s.fill) maxOf(s.outW / w, s.outH / h) else minOf(s.outW / w, s.outH / h)
            return base * s.zoom
        }
        /** Motion below this (image px) is under ¼ px in the GIF: the stop rule. */
        fun restEpsilon(s: Spec) = WobblePhysics.REST_SCREEN_PX / scaleOf(s)

        /** Hard limit on the motion after the tap (Lev: 3–5 s at most, lower end). */
        const val LIMIT_MS = 3000
        const val EASE_FROM_MS = 2400

        const val TAIL_EASE_MS = 250

        /** Physics-only dry run of a capture: frames including the still one, same stepping and ending rules. */
        fun estimate(s: Spec): Int {
            val ph = WobblePhysics.from(s.doc, s.bitmap.width, s.bitmap.height)
            ph.restEpsilon = restEpsilon(s); ph.touchGain = s.touchGain
            ph.tap(s.tapX, s.tapY)
            val end = Ending(s.frameMs, s.tailPx, scaleOf(s))
            var t = 0; var n = 1; var acc = 0L
            while (t < LIMIT_MS + 1000) {
                t += s.frameMs; acc += s.frameMs
                end.before(ph.bodies, t)
                var moving = false
                while (acc >= WobblePhysics.STEP_MS) { if (ph.step()) moving = true; acc -= WobblePhysics.STEP_MS }
                if (!moving) break
                n++
                end.after(ph.bodies, t)
            }
            return n
        }

        /** Old signature (kept for callers that don't have a spec). */
        fun estimate(doc: WblDocument, w: Int, h: Int, eps: Float, tapX: Float, tapY: Float, touchGain: Float, frameMs: Int): Int {
            val ph = WobblePhysics.from(doc, w, h)
            ph.restEpsilon = eps; ph.touchGain = touchGain
            ph.tap(tapX, tapY)
            var steps = 0
            while (ph.step() && steps * WobblePhysics.STEP_MS < LIMIT_MS) steps++
            val ms = minOf((steps + 1) * WobblePhysics.STEP_MS.toInt(), LIMIT_MS)
            return 1 + (ms + frameMs - 1) / frameMs
        }
    }
}
