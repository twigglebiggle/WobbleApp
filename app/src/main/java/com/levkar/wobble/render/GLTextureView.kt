package com.levkar.wobble.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A GL ES 3 view that, unlike GLSurfaceView, is part of the normal view hierarchy: its frames are
 * composited together with the views drawn over it. The editor uses it so bubble outlines and the
 * image move in the same frame when panning (a GLSurfaceView sits on its own layer and can be a frame off).
 *
 * Same calls the editor used on GLSurfaceView: setRenderer, queueEvent, requestRender, onResume, onPause.
 * Rendering is on demand only.
 */
class GLTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var renderer: GLSurfaceView.Renderer? = null
    private val lock = Object()
    private val events = ArrayList<Runnable>()
    private var renderRequested = false
    private var paused = false
    private var exiting = false
    private var texture: SurfaceTexture? = null
    private var width = 0; private var height = 0
    private var sizeChanged = false
    private var thread: Thread? = null
    private val frameWaiters = ArrayList<CountDownLatch>()

    init { surfaceTextureListener = this; isOpaque = true }

    fun setRenderer(r: GLSurfaceView.Renderer) {
        renderer = r
        thread = Thread({ loop() }, "GLTextureView").apply { start() }
    }

    fun queueEvent(r: Runnable) = synchronized(lock) { events += r; lock.notifyAll() }

    fun requestRender() = synchronized(lock) { renderRequested = true; lock.notifyAll() }

    /**
     * Requests a frame and waits (up to [timeoutMs]) until it has been handed to the compositor, so views
     * invalidated right after land in the same screen frame. Main thread; used for pan/zoom.
     */
    fun requestRenderAndWait(timeoutMs: Long = 12) {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            if (texture == null || paused) return
            frameWaiters += latch; renderRequested = true; lock.notifyAll()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun onResume() = synchronized(lock) { paused = false; renderRequested = true; lock.notifyAll() }
    fun onPause() = synchronized(lock) { paused = true; lock.notifyAll() }

    override fun onDetachedFromWindow() {
        synchronized(lock) { exiting = true; lock.notifyAll() }
        super.onDetachedFromWindow()
    }

    // ---- SurfaceTextureListener (main thread) ----

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = synchronized(lock) {
        texture = st; width = w; height = h; sizeChanged = true; renderRequested = true; lock.notifyAll()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = synchronized(lock) {
        width = w; height = h; sizeChanged = true; renderRequested = true; lock.notifyAll()
    }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        synchronized(lock) { texture = null; lock.notifyAll() }
        // Wait briefly for the GL thread to drop its window surface before the texture goes away.
        synchronized(lock) { val end = System.currentTimeMillis() + 200; while (hasSurface && System.currentTimeMillis() < end) lock.wait(20) }
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---- GL thread ----

    @Volatile private var hasSurface = false

    private fun loop() {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0]
        val ctx: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var created = false
        try {
            while (true) {
                var work: List<Runnable>
                var draw: Boolean
                var resize = false
                var st: SurfaceTexture?
                var w: Int; var h: Int
                var isPaused: Boolean
                synchronized(lock) {
                    while (true) {
                        if (exiting) return
                        st = texture
                        val ready = st != null && !paused
                        if (st == null && surface != EGL14.EGL_NO_SURFACE) break      // must release the surface
                        if (ready && (events.isNotEmpty() || renderRequested || sizeChanged)) break
                        if (!ready && events.isNotEmpty() && created) break           // events may run without drawing
                        lock.wait()
                    }
                    work = ArrayList(events); events.clear()
                    draw = renderRequested && st != null && !paused; if (draw) renderRequested = false
                    if (sizeChanged && st != null) { resize = true; sizeChanged = false }
                    w = width; h = height; isPaused = paused
                }
                if (st == null) {
                    if (surface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx)
                        EGL14.eglDestroySurface(display, surface); surface = EGL14.EGL_NO_SURFACE
                    }
                    synchronized(lock) { hasSurface = false; lock.notifyAll() }
                    if (!created) { synchronized(lock) { events.addAll(0, work) }; continue }
                    for (r in work) r.run()
                    releaseWaiters()
                    continue
                }
                if (isPaused) {                                   // keep the surface, run events, don't draw
                    if (created) for (r in work) r.run() else synchronized(lock) { events.addAll(0, work) }
                    releaseWaiters()
                    synchronized(lock) { if (paused && events.isEmpty()) lock.wait() }
                    continue
                }
                if (surface == EGL14.EGL_NO_SURFACE) {
                    surface = EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(EGL14.EGL_NONE), 0)
                    EGL14.eglMakeCurrent(display, surface, surface, ctx)
                    hasSurface = true
                    if (!created) { created = true; renderer?.onSurfaceCreated(null, null) }
                    resize = true
                }
                for (r in work) r.run()
                if (resize) { renderer?.onSurfaceChanged(null, w, h); draw = true }
                if (draw) {
                    renderer?.onDrawFrame(null)
                    EGL14.eglSwapBuffers(display, surface)
                }
                releaseWaiters()
            }
        } finally {
            releaseWaiters()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
            synchronized(lock) { hasSurface = false; lock.notifyAll() }
        }
    }

    private fun releaseWaiters() {
        val ws = synchronized(lock) { ArrayList(frameWaiters).also { frameWaiters.clear() } }
        for (l in ws) l.countDown()
    }
}
