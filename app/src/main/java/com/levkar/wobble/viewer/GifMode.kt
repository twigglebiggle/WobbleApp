package com.levkar.wobble.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLSurfaceView
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.levkar.wobble.gallery.ShareProvider
import com.levkar.wobble.gif.GifCapture
import com.levkar.wobble.render.TapRings
import com.levkar.wobble.render.ViewXform
import com.levkar.wobble.render.WblLoader
import com.levkar.wobble.render.WobbleRenderer
import com.levkar.wobble.settings.Options
import com.levkar.wobble.ui.FloatingBar
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.ToolButton
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.uiText
import java.io.File
import java.util.concurrent.Executors

/**
 * Viewer → GIF: pick where one tap lands, then record it from the still picture until the last pixel stops
 * moving (see [GifCapture]), so the GIF loops without a seam. Lives on top of the viewer: its own chips,
 * options card and bar; the viewer's bar is hidden meanwhile.
 */
class GifMode(
    private val act: Activity,
    private val root: FrameLayout,
    private val glView: GLSurfaceView,
    private val renderer: WobbleRenderer,
    private val hide: List<View>,
    private val loaded: () -> WblLoader.Loaded?,
    private val fill: () -> Boolean,
    private val view: ViewXform,
    private val name: () -> String,
) {
    private val c: Context = act
    private val d = c.resources.displayMetrics.density
    private fun px(v: Float) = (v * d).toInt()
    private val prefs = c.getSharedPreferences("gif", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    var active = false; private set
    private enum class State { PICK, RENDER, RESULT }
    private var state = State.PICK

    // choices (remembered)
    private var sizeIx = prefs.getInt("size", 1)
    private var fpsIx = prefs.getInt("fps", 1)
    private var stillIx = prefs.getInt("still", 1)
    private var ringOn = prefs.getBoolean("ring", false)
    /** Lev: crop to the picture (whole, its own shape) or to the screen (exactly what's shown: zoom, pan, bars). */
    private var cropScreen = prefs.getBoolean("crop_screen", false)
    /** Lev: the faint tail read as a pause. Full = wait for ¼ px; Quick/Snappy = cut the tail short and ease to rest. */
    private var endIx = prefs.getInt("ending", 1)
    /** Lev: tap strength for the GIF, 10–300 % (starts from Settings → Touch strength). */
    private var strength = prefs.getInt("strength", Options.TOUCH.get(c)).coerceIn(10, 300)

    // the tap: screen px (marker) and image px (physics)
    private var tapSet = false
    private var tapSx = 0f; private var tapSy = 0f
    private var tapIx = 0f; private var tapIy = 0f

    private val layer = FrameLayout(c)
    private val title = chip("Make GIF", Ui.TEXT, Ui.uiSemi, 14f, Ui.LINE)
    private val info = chip("Tap where the poke lands", Ui.ACCENT, Ui.mono, 12f, Ui.ACCENT)
    private val marker = Marker(c)
    private val options = LinearLayout(c)
    /** The option rows; the card's header folds them away (Lev: the card covered the picture while picking the tap). */
    private val optRows = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val optSummary = c.uiText("", 11f, Ui.mono, Ui.ACCENT)
    private val optChevron = android.widget.ImageView(c)
    private var optFolded = prefs.getBoolean("folded", false)
    private val bar = FloatingBar(c)
    private val progressCard = LinearLayout(c)
    private val progressText = c.uiText("", 13f, Ui.ui, Ui.TEXT2)
    private val progressFill = View(c)
    private val result = LinearLayout(c)
    private val preview = ImageView(c)
    private val meta = c.uiText("", 14f, Ui.uiSemi, Ui.TEXT)
    private val metaSub = c.uiText("", 11f, Ui.mono, Ui.MUTED)
    private val veil = View(c).apply { setBackgroundColor(0x99000000.toInt()) }

    private var savedRingMode = TapRings.MODE_OVERLAY
    private var capture: GifCapture? = null
    private var lastResult: GifCapture.Result? = null

    init { build() }

    // ---------------------------------------------------------------- open / close

    fun open() {
        if (active) return
        if (loaded() == null) { toast("Still loading…"); return }
        active = true; state = State.PICK
        for (v in hide) v.visibility = View.GONE
        root.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        savedRingMode = Options.TAP_RINGS.get(c)
        fitWidth()
        applyPreviewRing()
        applyPreviewStrength()
        showState()
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration((160 * Ui.anim).toLong()).start()
    }

    fun close() {
        if (!active) return
        capture?.cancelled = true; capture = null
        active = false
        root.removeView(layer)
        for (v in hide) v.visibility = View.VISIBLE
        val m = savedRingMode
        glView.queueEvent { renderer.ringMode = m }
        renderer.loadSettings(c)                                       // back to Settings' touch strength
        (preview.drawable as? android.graphics.drawable.Animatable)?.stop()
        preview.setImageDrawable(null)
    }

    /** Back: result → pick again; pick → leave; while rendering → stop it. */
    fun back() {
        when (state) {
            State.RESULT -> { state = State.PICK; showState() }
            State.RENDER -> { capture?.cancelled = true; state = State.PICK; showState() }
            State.PICK -> close()
        }
    }

    /** The screen turned: the old tap spot no longer matches the picture. */
    fun onLayoutChanged() {
        fitWidth()
        if (!active) return
        tapSet = false; marker.invalidate()
        if (state == State.PICK) refreshInfo()
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var multi = false

    fun onTouch(e: MotionEvent) {
        if (state != State.PICK) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_UP -> {
                val moved = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble())
                if (!multi && moved < 24 * d && e.eventTime - downT < 500) setTap(e.x, e.y)
            }
        }
    }

    private fun setTap(sx: Float, sy: Float) {
        val l = loaded() ?: return
        view.fill = fill()
        val ix = view.toImageX(sx); val iy = view.toImageY(sy)
        if (ix < 0 || iy < 0 || ix > l.bitmap.width || iy > l.bitmap.height) return     // on the bars around a Fit picture
        tapSet = true; tapSx = sx; tapSy = sy; tapIx = ix; tapIy = iy
        Ui.haptic(marker, android.view.HapticFeedbackConstants.CLOCK_TICK)
        marker.invalidate()
        playPreview()
        refreshInfo()
        recordBtn.alpha = 1f
    }

    private fun playPreview() {
        if (!tapSet) return
        val x = tapSx; val y = tapSy
        glView.queueEvent { renderer.commandTap(x, y) }
        glView.requestRender()
    }

    private fun applyPreviewStrength() { renderer.touchGain = strength / 100f }

    private fun applyPreviewRing() {
        val m = gifRingMode()
        glView.queueEvent { renderer.ringMode = m }
    }

    private fun gifRingMode() = if (!ringOn) TapRings.MODE_OFF else if (savedRingMode != TapRings.MODE_OFF) savedRingMode else TapRings.MODE_OVERLAY

    // ---------------------------------------------------------------- the spec

    private val frameMs get() = FRAME_MS[fpsIx]
    private val stillMs get() = STILL_MS[stillIx]

    /** GIF size: Screen = the screen's shape, Picture = the picture's own shape; short side = the chosen size. */
    private fun outSize(l: WblLoader.Loaded): Pair<Int, Int> {
        val n = SIZES[sizeIx]
        val aw: Float; val ah: Float
        if (cropScreen) { aw = view.viewW.toFloat(); ah = view.viewH.toFloat() } else { aw = l.bitmap.width.toFloat(); ah = l.bitmap.height.toFloat() }
        var w: Int; var h: Int
        if (aw <= ah) { w = n; h = Math.round(n * ah / aw) } else { h = n; w = Math.round(n * aw / ah) }
        val longCap = 4096
        if (maxOf(w, h) > longCap) { val k = longCap.toFloat() / maxOf(w, h); w = (w * k).toInt(); h = (h * k).toInt() }
        return w to h
    }

    private fun spec(l: WblLoader.Loaded): GifCapture.Spec {
        val (w, h) = outSize(l)
        view.fill = fill()
        val k = w.toFloat() / maxOf(1, view.viewW)
        // Screen: the viewer's framing scaled to the GIF. Picture: the whole picture, nothing around it.
        val s = if (cropScreen) GifCapture.Spec(l.bitmap, l.doc, w, h, view.fill, view.zoom, view.panX * k, view.panY * k,
                    tapIx, tapIy, frameMs, stillMs, gifRingMode(), TapRings.Style.from(c), strength / 100f, d)
                else GifCapture.Spec(l.bitmap, l.doc, w, h, false, 1f, 0f, 0f,
                    tapIx, tapIy, frameMs, stillMs, gifRingMode(), TapRings.Style.from(c), strength / 100f, d)
        // Rings keep the size they have on screen, relative to the picture.
        return s.copy(density = d * GifCapture.scaleOf(s) / view.scale, tailPx = ENDING_FRAC[endIx] * minOf(w, h))
    }

    private var estimateToken = 0

    private fun refreshInfo() {
        updateSummary()
        val l = loaded() ?: return
        if (!tapSet) { info.text = "Tap where the poke lands"; return }
        val s = spec(l)
        val token = ++estimateToken
        info.text = "…"
        io.execute {
            val frames = GifCapture.estimate(s)
            val ms = frames * s.frameMs + s.stillMs
            act.runOnUiThread { if (token == estimateToken && state == State.PICK) info.text = "%.1f s · %d frames · %d×%d".format(ms / 1000f, frames, s.outW, s.outH) }
        }
    }

    // ---------------------------------------------------------------- record

    private fun record() {
        if (state != State.PICK) return
        if (!tapSet) { toast("Tap the picture first"); return }
        val l = loaded() ?: return
        val s = spec(l)
        val cap = GifCapture(s); capture = cap
        state = State.RENDER; showState()
        setProgress(0, 1)
        val out = File(File(c.cacheDir, "gif").apply { mkdirs() }, "${safeName()}.gif")
        io.execute {
            val r = runCatching { cap.run(out) { n, total -> act.runOnUiThread { if (capture === cap) setProgress(n, total) } } }
            act.runOnUiThread {
                if (capture !== cap || !active) return@runOnUiThread
                capture = null
                r.onSuccess { lastResult = it; state = State.RESULT; showState() }
                 .onFailure { if (it !is InterruptedException) toast("Couldn't make the GIF: ${it.message}"); state = State.PICK; showState() }
            }
        }
    }

    private fun setProgress(n: Int, total: Int) {
        progressText.text = "Rendering  $n / $total"
        val p = (n.toFloat() / total).coerceIn(0f, 1f)
        progressFill.layoutParams = (progressFill.layoutParams as LinearLayout.LayoutParams).apply { weight = p }
        (progressFill.parent as? LinearLayout)?.getChildAt(1)?.let { (it.layoutParams as LinearLayout.LayoutParams).weight = 1f - p; it.requestLayout() }
        progressFill.requestLayout()
    }

    private fun safeName() = name().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "wobble" }

    private fun showResult(r: GifCapture.Result) {
        meta.text = r.file.name
        metaSub.text = "%d × %d · %d frames · %.1f s · %s".format(r.width, r.height, r.frames, r.durationMs / 1000f, sizeText(r.file.length()))
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching {
                val dr = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(r.file))
                preview.setImageDrawable(dr)
                (dr as? android.graphics.drawable.AnimatedImageDrawable)?.apply { repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; start() }
            }
        } else preview.setImageBitmap(android.graphics.BitmapFactory.decodeFile(r.file.path))
    }

    private fun sizeText(b: Long) = if (b >= 1_000_000) "%.1f MB".format(b / 1_000_000f) else "${b / 1000} KB"

    private fun save() {
        val r = lastResult ?: return
        if (android.os.Build.VERSION.SDK_INT < 29) { toast("Saving needs Android 10 or newer; use Share"); return }
        io.execute {
            runCatching {
                val cr = c.contentResolver
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, r.file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Wobble")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no storage")
                cr.openOutputStream(uri)!!.use { o -> r.file.inputStream().use { it.copyTo(o) } }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            }.onSuccess { toast("Saved to Pictures/Wobble") }
             .onFailure { toast("Couldn't save: ${it.message}") }
        }
    }

    private fun share() {
        val r = lastResult ?: return
        io.execute {
            runCatching {
                val dir = ShareProvider.dir(c)
                dir.listFiles()?.forEach { it.delete() }                  // only the latest share is kept
                val f = File(dir, r.file.name); r.file.copyTo(f, overwrite = true)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/gif"
                    putExtra(Intent.EXTRA_STREAM, ShareProvider.uri(c, f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                act.runOnUiThread { act.startActivity(Intent.createChooser(send, null)) }
            }.onFailure { toast("Couldn't share: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- views

    private lateinit var recordBtn: ToolButton
    private lateinit var replayBtn: ToolButton
    private lateinit var cancelBtn: ToolButton

    private fun showState() {
        bar.removeAllViews()
        val pick = state == State.PICK; val rend = state == State.RENDER; val res = state == State.RESULT
        info.visibility = if (pick) View.VISIBLE else View.GONE
        marker.visibility = if (pick) View.VISIBLE else View.GONE
        options.visibility = if (res) View.GONE else View.VISIBLE
        options.alpha = if (rend) 0.35f else 1f
        setEnabledDeep(options, pick)
        progressCard.visibility = if (rend) View.VISIBLE else View.GONE
        result.visibility = if (res) View.VISIBLE else View.GONE
        veil.visibility = if (pick) View.GONE else View.VISIBLE
        when (state) {
            State.PICK -> {
                cancelBtn = ToolButton(c, Icon.CLOSE, "Cancel").apply { setOnClickListener { close() } }
                replayBtn = ToolButton(c, Icon.REPLAY, "Replay", "live").apply { setOnClickListener { playPreview() } }
                recordBtn = ToolButton(c, Icon.RECORD, "Record").apply { accent = true; alpha = if (tapSet) 1f else 0.5f; setOnClickListener { record() } }
                bar.add(cancelBtn); bar.add(replayBtn); bar.add(recordBtn, 1.2f)
                (preview.drawable as? android.graphics.drawable.Animatable)?.stop()
                preview.setImageDrawable(null)
                refreshInfo()
            }
            State.RENDER -> bar.add(ToolButton(c, Icon.CLOSE, "Stop").apply { setOnClickListener { back() } })
            State.RESULT -> {
                bar.add(ToolButton(c, Icon.REPLAY, "Again").apply { setOnClickListener { back() } })
                bar.add(ToolButton(c, Icon.SHARE, "Share").apply { setOnClickListener { share() } })
                bar.add(ToolButton(c, Icon.EXPORT, "Save GIF").apply { accent = true; setOnClickListener { save() } }, 1.2f)
                lastResult?.let { showResult(it) }
            }
        }
    }

    private fun applyFold() {
        optRows.visibility = if (optFolded) View.GONE else View.VISIBLE
        optChevron.setImageDrawable((if (optFolded) Icon.CHEVRON_UP else Icon.CHEVRON_DOWN).drawable(Ui.TEXT2))
        updateSummary()
    }

    private fun updateSummary() {
        optSummary.text = if (!optFolded) "" else
            "${if (cropScreen) "Screen" else "Picture"} · ${SIZES[sizeIx]} · ${1000 / FRAME_MS[fpsIx]} fps · ${strength}%" + if (ringOn) " · ring" else ""
    }

    /** Landscape: the card and the bar don't stretch across the whole width (like the viewer's bar). */
    private fun fitWidth() {
        val land = c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        for (v in listOf(options, bar)) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            lp.width = if (land) px(440f) else ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            v.layoutParams = lp
        }
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    private fun build() {
        layer.addView(marker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer.addView(veil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(22f) })
        layer.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(72f) })

        // options card
        options.apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL_GLASS, 12f, Ui.LINE)
            setPadding(px(12f), px(2f), px(12f), px(4f))
            isClickable = true                                        // taps on the card don't fall through to the picture
            // Header: tap to fold/unfold the rows; folded it shows the choices in one line.
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6f), 0, px(6f))
                isClickable = true; background = Ui.pressable(c, null, 8f)
                addView(c.uiText("GIF OPTIONS", 10.5f, Ui.mono, Ui.MUTED).apply { letterSpacing = 0.1f })
                addView(optSummary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(10f) })
                addView(optChevron, LinearLayout.LayoutParams(px(20f), px(20f)))
                setOnClickListener { optFolded = !optFolded; prefs.edit().putBoolean("folded", optFolded).apply(); applyFold() }
            })
            addView(optRows)
        }
        fun row(label: String, labels: List<String>, cur: () -> Int, pick: (Int) -> Unit, first: Boolean = false) {
            optRows.addView(View(c).apply { setBackgroundColor(Ui.LINE_SOFT) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            optRows.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6f), 0, px(6f))
                addView(c.uiText(label, 12.5f, Ui.ui, Ui.TEXT2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(segments(labels, cur, pick), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32f)))
            })
        }
        row("Crop to", listOf("Picture", "Screen"), { if (cropScreen) 1 else 0 }, { cropScreen = it == 1; prefs.edit().putBoolean("crop_screen", cropScreen).apply(); refreshInfo() }, first = true)
        row("Size", SIZES.map { "$it" }, { sizeIx }, { sizeIx = it; prefs.edit().putInt("size", it).apply(); refreshInfo() })
        row("Frames", listOf("25", "33", "50 fps"), { fpsIx }, { fpsIx = it; prefs.edit().putInt("fps", it).apply(); refreshInfo() })
        row("Still at start", listOf("0", "0.5", "1 s"), { stillIx }, { stillIx = it; prefs.edit().putInt("still", it).apply(); refreshInfo() })
        row("Ending", listOf("Full", "Quick", "Snappy"), { endIx }, { endIx = it; prefs.edit().putInt("ending", it).apply(); refreshInfo() })
        row("Tap ring", listOf("Off", "On"), { if (ringOn) 1 else 0 }, { ringOn = it == 1; prefs.edit().putBoolean("ring", ringOn).apply(); applyPreviewRing(); updateSummary() })
        optRows.addView(View(c).apply { setBackgroundColor(Ui.LINE_SOFT) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        optRows.addView(com.levkar.wobble.editor.PercentRow(c, "Strength", 300,
            onChange = { v -> strength = Math.round(v * 100).coerceIn(10, 300); applyPreviewStrength(); updateSummary() },
            onCommit = { prefs.edit().putInt("strength", strength).apply(); refreshInfo(); playPreview() }).apply { set(strength / 100f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40f)))
        layer.addView(options, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(px(14f), 0, px(14f), px(94f)) })
        applyFold()

        // progress card
        progressCard.apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL, 14f, Ui.LINE)
            setPadding(px(14f), px(14f), px(14f), px(14f))
            addView(progressText)
            addView(LinearLayout(c).apply {
                background = Ui.box(c, Ui.LINE, 3f)
                addView(progressFill.apply { background = Ui.box(c, Ui.ACCENT, 3f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
                addView(View(c), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6f)).apply { topMargin = px(10f) })
            addView(c.uiText("Frames are computed, not filmed: the same tap always makes the same GIF.", 11f, Ui.ui, Ui.MUTED),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })
        }
        layer.addView(progressCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(px(14f), px(84f), px(14f), 0) })

        // result
        result.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val frame = FrameLayout(c).apply {
                background = Ui.box(c, Color.BLACK, 14f, 0xFF3A3C41.toInt())
                clipToOutline = true
                addView(preview.apply { scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(c.uiText("∞ seamless loop", 11f, Ui.mono, Ui.ON_ACCENT).apply {
                    background = Ui.box(c, Ui.ACCENT, 6f); setPadding(px(8f), px(4f), px(8f), px(4f))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(px(10f), px(10f), 0, 0) })
            }
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(meta.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
            addView(metaSub.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
        }
        layer.addView(result, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(px(30f), px(76f), px(30f), px(104f)) })

        layer.addView(bar, bar.placement())
    }

    private fun chip(text: String, color: Int, tf: android.graphics.Typeface, size: Float, border: Int) = c.uiText(text, size, tf, color).apply {
        background = Ui.box(c, Ui.PANEL_GLASS, 8f, border)
        setPadding(px(12f), px(7f), px(12f), px(7f))
    }

    /** Segmented control, the same look as the settings screen. */
    private fun segments(labels: List<String>, current: () -> Int, pick: (Int) -> Unit): View {
        val boxes = labels.map { l -> c.uiText(l, 12f, Ui.ui).apply { gravity = Gravity.CENTER; val h = px(9f); setPadding(h, 0, h, 0) } }
        val bar = LinearLayout(c).apply { background = Ui.box(c, Ui.FIELD, 8f, 0xFF2F3136.toInt()); val p = px(3f); setPadding(p, p, p, p) }
        fun paint() = boxes.forEachIndexed { i, t ->
            val on = i == current()
            t.background = if (on) Ui.box(c, Ui.ACCENT, 6f) else Ui.pressable(c, null, 6f)
            t.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT2)
        }
        boxes.forEachIndexed { i, t ->
            t.setOnClickListener { if (state == State.PICK) { pick(i); paint() } }
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        paint()
        return bar
    }

    /** Crosshair + ring where the tap lands. */
    private inner class Marker(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * d; color = 0x40000000 }
        override fun onDraw(cv: Canvas) {
            if (!tapSet) return
            p.color = Ui.ACCENT
            val r = 26 * d; val a = 15 * d
            cv.drawCircle(tapSx, tapSy, r, shade)
            cv.drawCircle(tapSx, tapSy, r, p)
            cv.drawLine(tapSx - a, tapSy, tapSx + a, tapSy, p); cv.drawLine(tapSx, tapSy - a, tapSx, tapSy + a, p)
        }
    }

    private fun toast(msg: String) = act.runOnUiThread { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        val SIZES = listOf(320, 480, 720, 1080)
        /** GIF delays are in 1/100 s, so frame times are whole hundredths: 25, 33⅓, 50 fps. */
        val FRAME_MS = listOf(40, 30, 20)
        val STILL_MS = listOf(0, 500, 1000)
        /** Ending thresholds as a fraction of the GIF's short side (Quick ≈ 2.4 px at 480, Snappy ≈ 3.8 px). */
        val ENDING_FRAC = listOf(0f, 0.005f, 0.008f)
    }
}
