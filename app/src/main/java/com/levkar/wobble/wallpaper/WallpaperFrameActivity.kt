package com.levkar.wobble.wallpaper

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.levkar.wobble.Settings
import com.levkar.wobble.core.TiltInput
import com.levkar.wobble.library.Library
import com.levkar.wobble.render.ViewXform
import com.levkar.wobble.render.WblLoader
import com.levkar.wobble.render.WobbleRenderer
import com.levkar.wobble.ui.FloatingBar
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.IconButton
import com.levkar.wobble.ui.ToolButton
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.UiDialog
import com.levkar.wobble.ui.uiText
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Wallpaper framing (Lev's plan): between "Set as wallpaper" and Android's preview. The piece wobbles live,
 * framed exactly as the wallpaper will be. Pinch = zoom, drag = move, tap = poke, double-tap = re-center.
 * Fit/Fill, guides for clock/icons/dock, what goes around a Fit picture, and Pages for wide pictures.
 * The framing is remembered per piece ([Frame]).
 */
class WallpaperFrameActivity : Activity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: WobbleRenderer
    private val tilt = TiltInput()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val nextFrame = Runnable { glView.requestRender() }
    private val io = Executors.newSingleThreadExecutor()
    private var sensors: SensorManager? = null

    private var bytes: ByteArray? = null
    private var piece: String? = null
    private var bitmap: Bitmap? = null
    private var frame = Frame()
    private var guides = true
    private var pagePreview = 0.5f
    private val pagesSupported by lazy { Frame.pagesSupported(this) }      // hidden on launchers that never report pages
    private val xf = ViewXform()          // mirror of the renderer's mapping, for gestures and guides

    private lateinit var guideView: Overlay
    private lateinit var fitBtn: ToolButton
    private lateinit var guidesBtn: ToolButton
    private lateinit var pagesBtn: ToolButton
    private lateinit var zoomChip: TextView
    private lateinit var hint: TextView
    private lateinit var surroundRow: LinearLayout
    private lateinit var strip: PagesStrip
    private val swatches = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val d = resources.displayMetrics.density
        renderer = WobbleRenderer(d, tilt, fill = true) { delay -> handler.removeCallbacks(nextFrame); handler.postDelayed(nextFrame, delay) }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3); setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        guideView = Overlay(this)

        fitBtn = ToolButton(this, Icon.FIT, "Fill", "crop").apply { setOnClickListener { toggleFill() } }
        guidesBtn = ToolButton(this, Icon.GUIDES, "Guides").apply { highlighted = true; setOnClickListener { guides = !guides; highlighted = guides; guideView.invalidate() } }
        pagesBtn = ToolButton(this, Icon.PAGES, "Pages", "off").apply { setOnClickListener { togglePages() } }
        val bar = FloatingBar(this).apply {
            add(fitBtn); add(guidesBtn); add(pagesBtn)
            add(ToolButton(context, Icon.UNDO, "Reset").apply { setOnClickListener { reset() } })
            add(ToolButton(context, Icon.WALLPAPER, "Set").apply { accent = true; setOnClickListener { apply() } }, 1.2f)
        }

        zoomChip = uiText("", 12f, Ui.monoMedium, Ui.ACCENT).apply {
            background = Ui.box(context, 0xEB1C1D21.toInt(), 7f, Ui.ACCENT)
            val h = (10 * d).toInt(); setPadding(h, (6 * d).toInt(), h, (6 * d).toInt()); visibility = View.GONE
        }
        hint = uiText("Pinch to zoom · drag to move", 11f, Ui.mono, Ui.TEXT2).apply {
            background = Ui.box(context, 0xD91C1D21.toInt(), 6f)
            val h = (10 * d).toInt(); setPadding(h, (6 * d).toInt(), h, (6 * d).toInt())
        }
        surroundRow = buildSurroundRow()
        strip = PagesStrip(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glView); addView(guideView)
            addView(IconButton(context, Icon.BACK).apply { setOnClickListener { finish() } },
                FrameLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt(), Gravity.TOP or Gravity.START).apply { setMargins((14 * d).toInt(), (18 * d).toInt(), 0, 0) })
            addView(uiText("Frame wallpaper", 14f, Ui.uiSemi).apply {
                background = Ui.box(context, 0xD91C1D21.toInt(), 8f, Ui.LINE)
                val h = (14 * d).toInt(); setPadding(h, (9 * d).toInt(), h, (9 * d).toInt())
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (22 * d).toInt() })
            addView(zoomChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (72 * d).toInt() })
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (104 * d).toInt() })
            val side = (12 * d).toInt()
            addView(surroundRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(side, 0, side, (100 * d).toInt()) })
            addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (62 * d).toInt(), Gravity.BOTTOM).apply { setMargins(side, 0, side, (100 * d).toInt()) })
            addView(bar, bar.placement())
        }
        setContentView(root)
        hideSystemBars()
        handler.postDelayed({ hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE }.start() }, 3500)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        guideView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> xf.viewW = v.width; xf.viewH = v.height; push() }

        val uri = intent.data ?: return finish()
        io.execute {
            runCatching {
                val b = Library.read(this, uri)
                val name = Library.displayName(this, uri).removeSuffix(".wbl").removeSuffix(".WBL")
                val loaded = WblLoader.load(b) ?: error("can't decode")
                runOnUiThread {
                    bytes = b; piece = name; bitmap = loaded.bitmap
                    xf.imgW = loaded.bitmap.width; xf.imgH = loaded.bitmap.height
                    frame = (Frame.forPiece(this, name) ?: Frame(surround = com.levkar.wobble.settings.Options.FIT_SURROUND.get(this))).let { if (pagesSupported) it else it.copy(pages = false) }
                    strip.bitmap = loaded.bitmap
                    swatches.getOrNull(Frame.BLUR)?.background = blurSwatch(loaded.bitmap)
                    glView.queueEvent { renderer.setScene(loaded.bitmap, loaded.doc) }
                    push()
                }
            }.onFailure { runOnUiThread { Toast.makeText(this, "Couldn't open: ${it.message}", Toast.LENGTH_LONG).show(); finish() } }
        }
    }

    // ---- state → screen ----

    private val wide get() = xf.imgW > 1 && xf.imgW * xf.scale > xf.viewW + 1f

    /** Applies [frame] to the mirror and the renderer, and refreshes the controls. */
    private fun push() {
        if (xf.viewW <= 1 || xf.imgW <= 1) return
        frame.apply(xf, pagePreview)
        val f = frame; val page = pagePreview
        glView.queueEvent { renderer.frame = f; renderer.pageX = page }
        glView.requestRender()
        fitBtn.set(label = if (f.fill) "Fill" else "Fit", sub = if (f.fill) "crop" else "whole")
        val canPage = f.fill && wide && pagesSupported
        pagesBtn.visibility = if (canPage) View.VISIBLE else View.GONE
        pagesBtn.set(label = "Pages", sub = if (f.pages) "on" else "off"); pagesBtn.highlighted = f.pages
        surroundRow.visibility = if (!f.fill) View.VISIBLE else View.GONE
        strip.visibility = if (canPage && f.pages) View.VISIBLE else View.GONE
        for ((i, s) in swatches.withIndex()) s.foreground = if (i == f.surround) Ui.box(this, 0, 8f, Ui.ACCENT, 2f) else null
        strip.invalidate(); guideView.invalidate()
    }

    /** Gesture changed the mirror: take zoom/pan back into the framing (clamped by [push]). */
    private fun takeFromMirror() {
        val z = xf.zoom.coerceIn(Frame.MIN_ZOOM, Frame.MAX_ZOOM)
        if (frame.pages && frame.fill && wide) {
            val rx = abs(xf.imgW * xf.scale - xf.viewW) / 2f
            pagePreview = (0.5f - xf.panX / (2f * rx)).coerceIn(0f, 1f)
            frame = frame.copy(zoom = z, panY = xf.panY / xf.viewH)
        } else frame = frame.copy(zoom = z, panX = xf.panX / xf.viewW, panY = xf.panY / xf.viewH)
        push()
    }

    private fun toggleFill() { frame = frame.copy(fill = !frame.fill, zoom = 1f, panX = 0f, panY = 0f); push() }
    private fun togglePages() { frame = frame.copy(pages = !frame.pages); pagePreview = 0.5f; push() }
    private fun reset() { frame = frame.copy(zoom = 1f, panX = 0f, panY = 0f); pagePreview = 0.5f; push() }

    private fun apply() {
        val b = bytes ?: return
        val f = frame; val name = piece
        io.execute {
            WallpaperSetter.set(this, b, f, name)
            runOnUiThread { finish() }
        }
    }

    // ---- surround (Fit) ----

    private fun buildSurroundRow(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.box(context, 0xE61C1D21.toInt(), 12f, Ui.LINE)
            val p = (12 * d).toInt(); setPadding(p, (10 * d).toInt(), p, (10 * d).toInt())
            addView(uiText("AROUND IT", 11f, Ui.ui, Ui.MUTED).apply { letterSpacing = 0.06f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val s = (30 * d).toInt()
            fun sw(bg: android.graphics.drawable.Drawable, label: String?, kind: Int) = TextView(context).apply {
                background = bg; gravity = Gravity.CENTER; label?.let { text = it; setTextColor(Ui.TEXT2); textSize = 16f; typeface = Ui.ui }
                setOnClickListener { if (kind == Frame.CUSTOM) pickColour() else { frame = frame.copy(surround = kind); push() } }
            }.also { swatches += it; addView(it, LinearLayout.LayoutParams(s, s).apply { marginStart = (10 * d).toInt() }) }
            sw(Ui.box(context, 0xFF000000.toInt(), 8f, 0xFF3A3C41.toInt()), null, Frame.BLACK)
            sw(Ui.box(context, 0xFF16171A.toInt(), 8f, 0xFF3A3C41.toInt()), null, Frame.GRAPHITE)
            sw(Ui.box(context, 0xFF2A2C31.toInt(), 8f, 0xFF3A3C41.toInt()), null, Frame.BLUR)
            sw(Ui.box(context, 0, 8f, 0xFF3A3C41.toInt()), "+", Frame.CUSTOM)
        }
    }

    private fun blurSwatch(b: Bitmap): android.graphics.drawable.Drawable {
        val small = Bitmap.createScaledBitmap(b, 6, maxOf(1, 6 * b.height / b.width), true)
        return android.graphics.drawable.BitmapDrawable(resources, small).apply { isFilterBitmap = true; alpha = 200 }
    }

    /** Custom colour around a Fit picture: the colour picker, live on the wallpaper behind it. */
    private fun pickColour() {
        val before = frame
        fun use(c: Int) {
            frame = frame.copy(surround = Frame.CUSTOM, color = c or 0xFF000000.toInt())
            swatches.getOrNull(Frame.CUSTOM)?.let { sw -> sw.background = Ui.box(this, frame.color, 8f, 0xFF3A3C41.toInt()); (sw as? TextView)?.text = "" }
            push()
        }
        com.levkar.wobble.ui.ColorPicker.show(this, "Colour around it", frame.color,
            onChange = { c -> if (c == before.color && before.surround != Frame.CUSTOM) { frame = before; push() } else use(c) },
            onDone = { c -> use(c) })
    }

    // ---- gestures and guides ----

    private inner class Overlay(c: Context) : View(c) {
        private val d = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x73FFFFFF; pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f) }
        private val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 64 * d; runCatching { typeface = Ui.ui } }
        private val date = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 14 * d; runCatching { typeface = Ui.ui } }
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            if (!guides) return
            val w = width.toFloat(); val h = height.toFloat()
            val now = java.util.Date()
            c.drawText(java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(now), w / 2, h * 0.15f, clock)
            c.drawText(java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(now), w / 2, h * 0.15f + 26 * d, date)
            val icon = 52 * d; val cols = 4; val gx = (w - 60 * d) / cols
            fun slot(cx: Float, cy: Float) { r.set(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2); c.drawRoundRect(r, 16 * d, 16 * d, dash) }
            for (row in 0 until 2) for (col in 0 until cols) slot(30 * d + gx * (col + 0.5f), h * 0.55f + row * (icon + 26 * d))
            val dockY = h - 160 * d
            c.drawLine(30 * d, dockY - icon / 2 - 10 * d, w - 30 * d, dockY - icon / 2 - 10 * d, dash)
            for (col in 0 until cols) slot(30 * d + gx * (col + 0.5f), dockY)
        }

        private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f; private var downT = 0L
        private var moved = false; private var pinchDist = 0f; private var midX = 0f; private var midY = 0f
        private var lastTapT = 0L; private var lastTapX = -1e9f; private var lastTapY = -1e9f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downT = e.eventTime; moved = false }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    moved = true
                    pinchDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
                    midX = (e.getX(0) + e.getX(1)) / 2; midY = (e.getY(0) + e.getY(1)) / 2
                    zoomChip.visibility = View.VISIBLE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val dist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
                        val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                        xf.zoomAbout(dist / pinchDist, mx, my)
                        xf.zoom = xf.zoom.coerceIn(Frame.MIN_ZOOM, Frame.MAX_ZOOM)
                        xf.panX += mx - midX; xf.panY += my - midY
                        pinchDist = dist; midX = mx; midY = my
                        zoomChip.text = "ZOOM ${Math.round(xf.zoom * 100)}"
                        takeFromMirror()
                    } else {
                        if (!moved && hypot(e.x - downX, e.y - downY) < slop) return true
                        if (!moved) { moved = true; lastX = e.x; lastY = e.y }
                        xf.panX += e.x - lastX; xf.panY += e.y - lastY
                        lastX = e.x; lastY = e.y
                        takeFromMirror()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {           // the finger that stays continues as a drag
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(keep); lastY = e.getY(keep)
                    zoomChip.visibility = View.GONE
                }
                MotionEvent.ACTION_UP -> {
                    zoomChip.visibility = View.GONE
                    if (!moved && e.eventTime - downT < 250) {
                        val x = e.x; val y = e.y
                        if (e.eventTime - lastTapT < 300 && hypot(x - lastTapX, y - lastTapY) < 60 * d) { reset(); lastTapT = 0L }
                        else { lastTapT = e.eventTime; lastTapX = x; lastTapY = y }
                        glView.queueEvent { renderer.commandTap(x, y) }               // a poke, to see the motion
                        glView.requestRender()
                    }
                }
                MotionEvent.ACTION_CANCEL -> zoomChip.visibility = View.GONE
            }
            return true
        }
    }

    /** Wide picture with Pages on: the full width, with the window you'd see on the current page. Drag to try pages. */
    private inner class PagesStrip(c: Context) : View(c) {
        var bitmap: Bitmap? = null
        private val d = resources.displayMetrics.density
        private val bg = Ui.box(c, 0xE61C1D21.toInt(), 12f, Ui.LINE)
        private val img = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 140 }
        private val win = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Ui.ACCENT }
        private val src = android.graphics.Rect(); private val dst = RectF()

        init { background = bg; visibility = GONE }

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            val p = 8 * d
            dst.set(p, p, width - p, height - p)
            val k = dst.width() / dst.height(); val bh = (b.width / k).toInt().coerceAtMost(b.height)
            src.set(0, (b.height - bh) / 2, b.width, (b.height + bh) / 2)
            c.drawBitmap(b, src, dst, img)
            val frac = (xf.viewW / (xf.imgW * xf.scale)).coerceIn(0.05f, 1f)
            val x0 = dst.left + (dst.width() * (1 - frac)) * pagePreview
            c.drawRoundRect(x0, dst.top - 2 * d, x0 + dst.width() * frac, dst.bottom + 2 * d, 6 * d, 6 * d, win)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = 8 * d
            val frac = (xf.viewW / (xf.imgW * xf.scale)).coerceIn(0.05f, 1f)
            val usable = (width - 2 * p) * (1 - frac)
            if (usable > 0) { pagePreview = ((e.x - p - (width - 2 * p) * frac / 2) / usable).coerceIn(0f, 1f); push() }
            return true
        }
    }

    // ---- lifecycle ----

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
    }

    override fun onSensorChanged(event: SensorEvent) { if (tilt.onSample(event.values[0], event.values[1])) glView.requestRender() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val mode = Settings.ringMode(this)
        glView.queueEvent { renderer.ringMode = mode }; renderer.loadSettings(this)
        glView.requestRender()
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        handler.removeCallbacks(nextFrame)
        glView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars() }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
