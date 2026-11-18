package com.levkar.wobble.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.levkar.wobble.Settings
import com.levkar.wobble.core.TiltInput
import com.levkar.wobble.editor.EditorActivity
import com.levkar.wobble.library.Library
import com.levkar.wobble.render.WblLoader
import com.levkar.wobble.render.WobbleRenderer
import com.levkar.wobble.wallpaper.WallpaperSetter
import com.levkar.wobble.ui.FloatingBar
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.IconButton
import com.levkar.wobble.ui.ToolButton
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.uiText
import java.util.concurrent.Executors

/**
 * Plays one wobble full-screen with tilt and touch. Opened from the gallery with the file's Uri as intent data.
 */
class ViewerActivity : Activity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: WobbleRenderer
    private val tilt = TiltInput()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val nextFrame = Runnable { glView.requestRender() }
    private val io = Executors.newSingleThreadExecutor()
    private var sensorManager: SensorManager? = null
    private val samples by lazy { assets.list("samples")?.sorted() ?: emptyList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = WobbleRenderer(resources.displayMetrics.density, tilt) { delay ->
            handler.removeCallbacks(nextFrame); handler.postDelayed(nextFrame, delay)
        }
        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean { handleTouch(e); requestRender(); return true }
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        Ui.init(this)
        fill = com.levkar.wobble.settings.Options.VIEW_FILL.get(this)
        // Floating graphite bar over the art; state lines (ring mode, Fit/Fill) in mono amber.
        val bar = FloatingBar(this).apply {
            add(editBtn.apply { setOnClickListener { edit() } })
            add(ToolButton(context, Icon.WALLPAPER, "Wallpaper").apply { setOnClickListener { setAsWallpaper() } })
            add(ToolButton(context, Icon.RINGS, "Rings", ringState()).apply { setOnClickListener { cycleRings(this) } })
            add(ToolButton(context, Icon.FIT, "View", fitState()).apply { setOnClickListener { toggleFill(this) } })
            add(ToolButton(context, Icon.ROTATE, "Rotate", ROTATE_LABELS[rotateMode]).apply { setOnClickListener { cycleRotate(this) } })
            add(ToolButton(context, Icon.GIF, "GIF").apply { setOnClickListener { gif.open() } })
        }
        this.bar = bar
        val tag = uiText(BUILD_TAG, 9.5f, Ui.mono, 0xFF55575C.toInt()).apply {
            gravity = Gravity.CENTER
            visibility = if (com.levkar.wobble.settings.Options.SHOW_BUILD.get(context)) android.view.View.VISIBLE else android.view.View.GONE
        }
        val dens = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glView)
            addView(placeholder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(bar, bar.placement())
            // Small tab to tuck the bar away (Lev: it got in the way in landscape) and bring it back.
            addView(barTab.apply { setOnClickListener { toggleBar() } },
                FrameLayout.LayoutParams((34 * dens).toInt(), (26 * dens).toInt(), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, (14 * dens).toInt(), (BAR_UP_DP * dens).toInt()) })
            addView(tag, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = (4 * dens).toInt() })
            addView(IconButton(context, Icon.BACK).apply { setOnClickListener { if (gif.active) gif.back() else finish() } },   // back to the gallery
                FrameLayout.LayoutParams((42 * dens).toInt(), (42 * dens).toInt(), Gravity.TOP or Gravity.START).apply { setMargins((14 * dens).toInt(), (18 * dens).toInt(), 0, 0) })
        }
        root.addView(zoomChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (26 * dens).toInt() })
        setContentView(root)
        gif = GifMode(this, root, glView, renderer, listOf(bar, barTab, tag), { loadedScene }, { fill }, view, { currentName })
        hideSystemBars()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        renderer.fill = fill
        applyRotate()
        fitBar()
        root.addOnLayoutChangeListener { _, _, _, r, b, _, _, _, _ -> view.viewW = r; view.viewH = b }

        // "Open with Wobble" from another app (Lev): shown, not imported; the Edit button becomes "Add".
        val external = intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND
        if (external) {
            @Suppress("DEPRECATION")
            val src = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri else intent.data
            if (src == null) { toast("Nothing to open"); finish(); return }
            externalUri = src
            editBtn.set(Icon.IMPORT, "Add")
            io.execute {
                val name = runCatching { Library.displayName(this, src) }.getOrDefault("wobble")
                currentName = name.substringBeforeLast('.')
                show(name, { Library.read(this, src) })
            }
            return
        }
        // Opened from the gallery with a library file; without one, play a bundled sample.
        val uri = intent.data
        // Instant stand-in: the gallery's thumbnail, stretched, until the real picture is on the GPU.
        renderer.onFirstImage = { runOnUiThread {
            if (placeholder.visibility == android.view.View.VISIBLE)
                placeholder.animate().alpha(0f).setDuration((140 * Ui.anim).toLong()).withEndAction { placeholder.visibility = android.view.View.GONE }.start()
        } }
        if (uri != null) io.execute {
            com.levkar.wobble.gallery.Thumbs.find(this, uri)?.let { t -> runOnUiThread {
                placeholder.scaleType = if (fill) android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.FIT_CENTER
                placeholder.setImageBitmap(t); placeholder.alpha = 1f; placeholder.visibility = android.view.View.VISIBLE
            } }
        }
        val openGif = intent.getBooleanExtra(EXTRA_GIF, false)
        if (uri != null) io.execute {
            val name = Library.displayName(this, uri)
            if (show(name, { Library.read(this, uri) })) {
                currentUri = uri; currentName = name
                if (openGif) runOnUiThread { gif.open() }
            }
        } else showSample()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- loading ----

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EDIT) {                     // back from the editor: show the saved result
            currentUri?.let { u -> io.execute { show("edited", { Library.read(this, u) }) } }
        }
    }

    private fun showSample() {
        val name = samples.firstOrNull() ?: return
        currentUri = null; currentName = name.substringBeforeLast('.')
        io.execute { show(name, { assets.open("samples/$name").use { it.readBytes() } }) }
    }

    // ---- Fit / Fill ----

    /** false = whole image (letterboxed); true = cover the screen like the wallpaper, two fingers pan. */
    private var fill = false
    private val view = com.levkar.wobble.render.ViewXform()

    private fun fitState() = if (fill) "Fill" else "Fit"

    private fun toggleFill(b: ToolButton) {
        fill = !fill
        com.levkar.wobble.settings.Options.VIEW_FILL.set(this, fill)
        b.set(sub = fitState())
        view.reset()
        val f = fill
        glView.queueEvent { renderer.fill = f; renderer.setView(1f, 0f, 0f) }
        glView.requestRender()
    }

    /**
     * Keeps the picture in view while zooming/panning: along a side where it's bigger than the screen it must
     * cover the screen; along a side where it's smaller it stays centered.
     */
    private fun clampPan() {
        view.fill = fill
        val mx = kotlin.math.max(0f, (view.imgW * view.scale - view.viewW) / 2f)
        val my = kotlin.math.max(0f, (view.imgH * view.scale - view.viewH) / 2f)
        view.panX = view.panX.coerceIn(-mx, mx); view.panY = view.panY.coerceIn(-my, my)
    }

    private var pinchDist = 0f
    private fun spread(e: MotionEvent) = if (e.pointerCount < 2) 0f else
        Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()

    /** "ZOOM 150" while pinching (100 = Fit/Fill as chosen), like the wallpaper framing screen. */
    private val zoomChip by lazy {
        uiText("", 12f, Ui.mono, Ui.ACCENT).apply {
            background = Ui.box(context, Ui.PANEL_GLASS, 7f, Ui.ACCENT)
            val p = Ui.px(context, 8f); setPadding(p, p / 2, p, p / 2); alpha = 0f
        }
    }
    private fun showZoomChip() {
        zoomChip.text = "ZOOM ${Math.round(view.zoom * 100)}"
        zoomChip.animate().cancel(); zoomChip.alpha = 1f
    }
    private fun hideZoomChip() = zoomChip.animate().alpha(0f).setStartDelay(500).setDuration((200 * Ui.anim).toLong()).start()

    private var panning = false
    private var panMidX = 0f; private var panMidY = 0f

    private fun ringState() = Settings.RING_LABELS[Settings.ringMode(this)]

    /** The library file on screen (null while showing a bundled sample). */
    @Volatile private var currentUri: Uri? = null

    /** A file opened from another app, not (yet) in the library. */
    @Volatile private var externalUri: Uri? = null
    private val editBtn by lazy { ToolButton(this, Icon.EDIT, "Edit") }

    /** "Add": copies the opened file into the library; then it's a library piece (Edit, framing) like any other. */
    private fun addToLibrary() {
        val src = externalUri ?: return
        val tree = Library.treeUri(this) ?: run { toast("Open Wobble from your launcher once and pick a library folder first"); return }
        io.execute {
            runCatching { Library.importAny(this, tree, src) }
                .onSuccess { u -> runOnUiThread {
                    currentUri = u; externalUri = null
                    editBtn.set(Icon.EDIT, "Edit")
                    toast("Added to your library as ${Library.displayName(this, u)}")
                } }
                .onFailure { toast("Couldn't add: ${it.message}") }
        }
    }

    private fun edit() {
        if (externalUri != null) { addToLibrary(); return }
        val u = currentUri ?: run { toast("Samples can't be edited; open one from your gallery"); return }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, EditorActivity::class.java).setData(u), REQ_EDIT)
    }

    /** Viewer → GIF (one tap, recorded as a seamless loop). */
    private lateinit var gif: GifMode
    @Volatile private var loadedScene: WblLoader.Loaded? = null
    @Volatile private var currentName = "wobble"

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (gif.active) gif.back() else @Suppress("DEPRECATION") super.onBackPressed() }

    private val placeholder by lazy { android.widget.ImageView(this).apply { visibility = android.view.View.GONE } }

    // ---- hiding the bar ----

    private val barTab by lazy { IconButton(this, Icon.CHEVRON_DOWN, 18f) }
    private var barHidden = false

    /** Slides the bar off the bottom (the tab drops to the corner), or back up. */
    private fun toggleBar() {
        barHidden = !barHidden
        val d = resources.displayMetrics.density
        val k = Ui.anim
        val drop = (BAR_UP_DP - 14f) * d
        bar.animate().translationY(if (barHidden) bar.height + 40 * d else 0f).alpha(if (barHidden) 0f else 1f).setDuration((200 * k).toLong()).start()
        barTab.animate().translationY(if (barHidden) drop else 0f).setDuration((200 * k).toLong()).start()
        barTab.setIcon(if (barHidden) Icon.CHEVRON_UP else Icon.CHEVRON_DOWN)
    }

    // ---- rotation (viewer only; every other screen stays portrait) ----

    private lateinit var bar: FloatingBar
    /** 0 = Auto (follows the rotation sensor, even with the system's rotation lock on), 1 = Landscape, 2 = Portrait. */
    private val rotateMode: Int
        get() = com.levkar.wobble.settings.Options.VIEW_ROTATE.get(this)

    private fun applyRotate() {
        requestedOrientation = when (rotateMode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE   // either side, by sensor
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    /** Auto → Landscape → Portrait → Auto, remembered. */
    private fun cycleRotate(b: ToolButton) {
        val m = (rotateMode + 1) % 3
        com.levkar.wobble.settings.Options.VIEW_ROTATE.set(this, m)
        b.set(sub = ROTATE_LABELS[m])
        applyRotate()
    }

    /** In landscape the bar doesn't stretch across the whole width. */
    private fun fitBar() {
        val lp = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        val d = resources.displayMetrics.density
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        lp.width = if (land) (440 * d).toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bar.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        fitBar()
        tilt.reset()
        view.reset()                                            // re-center for the new shape
        glView.queueEvent { renderer.setView(1f, 0f, 0f) }
        glView.requestRender()
        gif.onLayoutChanged()
    }

    /** Off → Overlay → On image, shared with the wallpaper's settings. */
    private fun cycleRings(b: ToolButton) {
        val mode = (Settings.ringMode(this) + 1) % Settings.RING_LABELS.size
        Settings.setRingMode(this, mode)
        b.set(sub = ringState())
        glView.queueEvent { renderer.ringMode = mode }; renderer.loadSettings(this)
    }

    /** Raw bytes of what's on screen, kept for "Set as wallpaper". */
    @Volatile private var currentBytes: ByteArray? = null

    /** Loads and displays; returns false on failure (toasts unless [quiet]). Background thread. */
    private fun show(label: String, read: () -> ByteArray, quiet: Boolean = false): Boolean {
        try {
            val bytes = read()
            val loaded = WblLoader.load(bytes)
            if (loaded == null) { if (!quiet) toast("Can't decode image: $label"); return false }
            currentBytes = bytes
            loadedScene = loaded
            view.imgW = loaded.bitmap.width; view.imgH = loaded.bitmap.height; view.reset()
            glView.queueEvent { renderer.setScene(loaded.bitmap, loaded.doc); renderer.setView(1f, 0f, 0f) }
            glView.requestRender()
            return true
        } catch (e: Exception) {
            if (!quiet) toast("Failed to open $label: ${e.message}")
            return false
        }
    }

    private fun setAsWallpaper() {
        currentUri?.let { WallpaperSetter.frame(this, it); return }          // library piece: framing screen first
        val bytes = currentBytes ?: return
        io.execute { WallpaperSetter.set(this, bytes) }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ---- input ----

    private fun handleTouch(e: MotionEvent) {
        if (gif.active) { gif.onTouch(e); return }
        val idx = e.actionIndex
        val t = e.eventTime
        // A second finger turns the gesture into pinch-zoom + pan (the pokes so far are released without a tap).
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !panning) {
            panning = true
            for (p in 0 until e.pointerCount) {
                if (p == idx) continue
                val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p)
                glView.queueEvent { renderer.touchUp(id, x, y, Long.MAX_VALUE) }
            }
            panMidX = (e.getX(0) + e.getX(1)) / 2; panMidY = (e.getY(0) + e.getY(1)) / 2
            pinchDist = spread(e)
            return
        }
        if (panning) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    view.panX += mx - panMidX; view.panY += my - panMidY
                    panMidX = mx; panMidY = my
                    val dist = spread(e)
                    if (pinchDist > 0f && dist > 0f) {
                        val z = (view.zoom * dist / pinchDist).coerceIn(1f, MAX_ZOOM)
                        view.zoomAbout(z / view.zoom, mx, my)
                    }
                    pinchDist = dist
                    clampPan()
                    showZoomChip()
                    val z = view.zoom; val px = view.panX; val py = view.panY
                    glView.queueEvent { renderer.setView(z, px, py) }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Keep panning with the remaining two if three were down; otherwise wait for all up.
                    if (e.pointerCount > 2) {
                        val rest = (0 until e.pointerCount).filter { it != idx }
                        panMidX = (e.getX(rest[0]) + e.getX(rest[1])) / 2; panMidY = (e.getY(rest[0]) + e.getY(rest[1])) / 2
                        pinchDist = Math.hypot((e.getX(rest[0]) - e.getX(rest[1])).toDouble(), (e.getY(rest[0]) - e.getY(rest[1])).toDouble()).toFloat()
                    } else pinchDist = 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { panning = false; hideZoomChip() }
            }
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                glView.queueEvent { renderer.touchDown(id, x, y, t) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p)
                    glView.queueEvent { renderer.touchMove(id, x, y) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx)
                glView.queueEvent { renderer.touchUp(id, x, y, t) }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (p in 0 until e.pointerCount) {
                    val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p)
                    glView.queueEvent { renderer.touchUp(id, x, y, Long.MAX_VALUE) }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // The sensor's axes belong to the phone, not the screen: turn them with the display.
        val ax = event.values[0]; val ay = event.values[1]
        val rot = if (android.os.Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        val (x, y) = when (rot) {
            android.view.Surface.ROTATION_90 -> -ay to ax
            android.view.Surface.ROTATION_180 -> -ax to -ay
            android.view.Surface.ROTATION_270 -> ay to -ax
            else -> ax to ay
        }
        if (tilt.onSample(x, y)) glView.requestRender()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val mode = Settings.ringMode(this)
        glView.queueEvent { renderer.ringMode = mode }; renderer.loadSettings(this)
        glView.requestRender()
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)   // same rate as the original
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(nextFrame)
        glView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    companion object {
        /** Shown on screen so we can tell which build is on the phone. */
        const val BUILD_TAG = "b53 details+match"
        /** Intent extra: open straight into Make GIF (gallery tile menu). */
        const val EXTRA_GIF = "gif"
        private const val REQ_EDIT = 3
        private val ROTATE_LABELS = listOf("Auto", "Landscape", "Portrait")
        /** The tab sits just above the bar (bar: 18dp margin + 66dp tall). */
        private const val BAR_UP_DP = 92f
        /** Pinch zoom limit, × the Fit/Fill size. */
        private const val MAX_ZOOM = 6f
    }
}
