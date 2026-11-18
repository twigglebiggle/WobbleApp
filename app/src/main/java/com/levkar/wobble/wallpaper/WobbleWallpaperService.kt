package com.levkar.wobble.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.os.Bundle
import com.levkar.wobble.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.levkar.wobble.core.TiltInput
import com.levkar.wobble.render.WblLoader
import com.levkar.wobble.render.WobbleRenderer
import java.io.File
import java.util.concurrent.Executors

/**
 * Live wallpaper: the same engine as the viewer, framed as set in the framing screen ([Frame]);
 * without a framing, filling the screen (center-crop).
 * Draws only while something moves; the accelerometer is off while the wallpaper is hidden.
 * Shows the file saved by "Set as wallpaper" ([file]), or a bundled sample if there is none.
 */
class WobbleWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WobbleEngine()

    inner class WobbleEngine : Engine(), SensorEventListener {

        /** GLSurfaceView that draws into the wallpaper's surface instead of a window. */
        private inner class WallpaperGLView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destroy() = super.onDetachedFromWindow()
        }

        private val tilt = TiltInput()
        private lateinit var glView: WallpaperGLView
        private lateinit var renderer: WobbleRenderer
        private val io = Executors.newSingleThreadExecutor()
        private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
        private var loadedStamp = Long.MIN_VALUE
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val nextFrame = Runnable { glView.requestRender() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = WobbleRenderer(resources.displayMetrics.density, tilt, fill = true) { delay ->
                handler.removeCallbacks(nextFrame); handler.postDelayed(nextFrame, delay)
            }
            glView = WallpaperGLView(this@WobbleWallpaperService).apply {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            setTouchEventsEnabled(true)
            applySettings()
            reloadIfChanged()
        }

        /** Loads the wallpaper file if it changed since last time (e.g. a new "Set as wallpaper"). */
        private fun reloadIfChanged() {
            val f = file(this@WobbleWallpaperService)
            val stamp = if (f.exists()) f.lastModified() else -1L
            if (stamp == loadedStamp) return
            loadedStamp = stamp
            io.execute {
                val bytes = if (f.exists()) f.readBytes()
                            else assets.open("samples/" + (assets.list("samples")?.sorted()?.firstOrNull() ?: return@execute)).use { it.readBytes() }
                val loaded = WblLoader.load(bytes) ?: return@execute
                glView.queueEvent { renderer.setScene(loaded.bitmap, loaded.doc) }
                glView.requestRender()
            }
        }

        private fun applySettings() {
            val mode = Settings.ringMode(this@WobbleWallpaperService)
            val c = this@WobbleWallpaperService
            val frame = Frame.current(c)?.let { if (Frame.pagesSupported(c)) it else it.copy(pages = false) }   // Pages only where the launcher reports pages
            glView.queueEvent { renderer.ringMode = mode; renderer.frame = frame }; renderer.loadSettings(c)
        }

        /** Home-screen page swipes: a framing with Pages on slides the picture along. */
        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) {
            // A real multi-page swipe report (xStep < 1 = more than one page): this launcher supports Pages.
            if (xStep > 0f && xStep < 1f && xOffset > 0f && xOffset < 1f) Frame.markPagesSupported(this@WobbleWallpaperService)
            glView.queueEvent { renderer.pageX = xOffset }
            glView.requestRender()
        }

        /** Launchers report taps on empty home-screen space here (some send only this, no raw touches). */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                val fx = x.toFloat(); val fy = y.toFloat()
                glView.queueEvent { renderer.commandTap(fx, fy) }
                glView.requestRender()
            }
            return null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                glView.onResume()
                applySettings()
                reloadIfChanged()
                sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                }
                glView.requestRender()
            } else {
                sensors.unregisterListener(this)
                handler.removeCallbacks(nextFrame)
                glView.onPause()
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (tilt.onSample(event.values[0], event.values[1])) glView.requestRender()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onTouchEvent(e: MotionEvent) {
            val idx = e.actionIndex
            val t = e.eventTime
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                    glView.queueEvent { renderer.touchDown(id, x, y, t) }
                }
                MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) {
                    val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p)
                    glView.queueEvent { renderer.touchMove(id, x, y) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                    glView.queueEvent { renderer.touchUp(id, x, y, t) }
                }
                MotionEvent.ACTION_CANCEL -> for (p in 0 until e.pointerCount) {
                    val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p)
                    glView.queueEvent { renderer.touchUp(id, x, y, Long.MAX_VALUE) }
                }
            }
            glView.requestRender()
        }

        override fun onDestroy() {
            sensors.unregisterListener(this)
            handler.removeCallbacks(nextFrame)
            glView.destroy()
            io.shutdown()
            super.onDestroy()
        }
    }

    companion object {
        /** The app-private copy of the current wallpaper image. */
        fun file(context: Context) = File(context.filesDir, "wallpaper.wbl")
    }
}
