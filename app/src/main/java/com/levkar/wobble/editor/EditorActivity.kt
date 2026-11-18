package com.levkar.wobble.editor

import android.app.Activity
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.levkar.wobble.ui.BigButton
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.IconButton
import com.levkar.wobble.ui.MenuItem
import com.levkar.wobble.ui.ToolButton
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.UiMenu
import com.levkar.wobble.ui.UiDialog
import com.levkar.wobble.ui.uiText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.levkar.wobble.Settings
import com.levkar.wobble.core.EditBubble
import com.levkar.wobble.core.EditDoc
import com.levkar.wobble.core.Falloff
import com.levkar.wobble.core.TiltInput
import com.levkar.wobble.library.Library
import com.levkar.wobble.render.GLTextureView
import com.levkar.wobble.render.ViewXform
import com.levkar.wobble.render.WblLoader
import com.levkar.wobble.render.WobbleRenderer
import java.util.concurrent.Executors

/**
 * The bubble editor. Opened from the viewer with the library file's Uri as intent data.
 * Every finished gesture is an undo step and is autosaved (see [Autosave]).
 */
class EditorActivity : Activity(), EditorOverlay.Host, BubbleList.Host, SensorEventListener {

    private lateinit var glView: GLTextureView
    private lateinit var bubbleList: BubbleList
    private lateinit var renderer: WobbleRenderer
    private lateinit var editOverlay: EditorOverlay
    private lateinit var sliders: ValueSliders
    private lateinit var addBtn: ToolButton
    private lateinit var previewBtn: ToolButton
    private lateinit var copyBtn: ToolButton
    private lateinit var bubbleBtn: ToolButton
    private lateinit var undoBtn: ToolButton
    private lateinit var redoBtn: ToolButton
    private lateinit var row2: LinearLayout
    private lateinit var bottomBar: android.view.View
    private val redo = ArrayDeque<EditDoc>()
    private var peekAnim: android.animation.ValueAnimator? = null
    private lateinit var lockBtn: ToolButton
    private val tilt = TiltInput()
    private val xf = ViewXform()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val nextFrame = Runnable { glView.requestRender() }
    private var sensors: SensorManager? = null

    private lateinit var uri: Uri
    private var autosave: Autosave? = null
    override var doc = EditDoc(null, mutableListOf())
    private val undo = ArrayDeque<EditDoc>()
    override var previewing = false
    private var padBefore: EditDoc? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: run { finish(); return }
        Ui.init(this)
        val dens = resources.displayMetrics.density

        renderer = WobbleRenderer(dens, tilt) { delay -> handler.removeCallbacks(nextFrame); handler.postDelayed(nextFrame, delay) }
        renderer.live = false
        // A TextureView, not a GLSurfaceView: the image then reaches the screen in the same frame as the
        // outlines drawn over it, so they don't trail behind when panning.
        glView = GLTextureView(this).apply { setRenderer(renderer) }
        editOverlay = EditorOverlay(this, xf).also { it.host = this }

        // ---- bottom panel (nothing at the top): sliders, edit buttons, then Undo + Preview in thumb reach ----
        sliders = ValueSliders(this).apply {
            onChange = { which, v ->
                selectedBubble()?.let {
                    if (padBefore == null) padBefore = doc.copy()
                    if (which == 0) { if (it.freeze) it.hold = v else it.wobbliness = v } else it.extent = v
                    if (previewing) { val wbl = doc.toWbl(); glView.queueEvent { renderer.updateDoc(wbl) }; glView.requestRender() }
                    else shapeChanging()
                }
            }
            onCommit = { padBefore?.let { commit(it) }; padBefore = null }
            onDragging = { on -> if (previewing) peek(on) }
            onEditCurve = { openCurveSheet() }
        }
        // ---- Two rows (Lev's layout, b27) ----
        // Row 1, always: history │ make & protect │ look. Order as in most drawing apps.
        undoBtn = ToolButton(this, Icon.UNDO, "Undo").apply { iconOnly(); setOnClickListener { undoLast() } }
        redoBtn = ToolButton(this, Icon.REDO, "Redo").apply { iconOnly(); setOnClickListener { redoLast() } }
        addBtn = ToolButton(this, Icon.ADD, "Add").apply { setOnClickListener { addBubble() } }
        copyBtn = ToolButton(this, Icon.DUPLICATE, "Copy").apply { setOnClickListener { val before = doc.copy(); if (editOverlay.duplicate()) commit(before) } }
        lockBtn = ToolButton(this, Icon.LOCK, "Lock").apply { setOnClickListener { toggleLock() } }
        bubbleBtn = ToolButton(this, Icon.GEAR, "Bubble").apply { setOnClickListener { showBubblePopup(this) } }
        val fitBtn = ToolButton(this, Icon.FIT, "Fit").apply { setOnClickListener { cycleFit() } }
        previewBtn = ToolButton(this, Icon.PLAY, "Preview").apply { accent = true; setOnClickListener { setPreview(!previewing) } }
        fun group(vararg cells: Pair<android.view.View, Float>) = LinearLayout(this).apply {
            background = Ui.box(context, 0, 8f, Ui.LINE)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = object : android.graphics.drawable.ColorDrawable(Ui.LINE) { override fun getIntrinsicWidth() = 1 }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 8 * dens)
            }
            for ((v, w) in cells) addView(v, LinearLayout.LayoutParams(0, (56 * dens).toInt(), w))
        }
        val row1 = LinearLayout(this).apply {
            val gap = (6 * dens).toInt()
            addView(group(undoBtn to 1f, redoBtn to 1f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.7f).apply { marginEnd = gap })
            addView(group(addBtn to 1f, copyBtn to 1f, lockBtn to 1f, bubbleBtn to 1f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4.4f).apply { marginEnd = gap })
            addView(group(fitBtn to 0.9f, previewBtn to 1.25f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.3f))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Ui.PANEL), android.graphics.drawable.ColorDrawable(Ui.LINE))).apply { setLayerHeight(1, 1) }
            val p = (10 * dens).toInt(); setPadding(p, (8 * dens).toInt(), p, (14 * dens).toInt())
            addView(row1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        bottomBar = bar
        // Row 2: the sliders, only while an unlocked bubble is selected. It floats over the bottom of the
        // canvas (glass), so the canvas never changes size and the image doesn't jump when it appears.
        row2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Ui.PANEL_GLASS), android.graphics.drawable.ColorDrawable(Ui.LINE))).apply { setLayerHeight(1, 1) }
            val p = (12 * dens).toInt(); setPadding(p, (4 * dens).toInt(), p, (4 * dens).toInt())
            addView(sliders, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = android.view.View.GONE
            isClickable = true                                  // touches on the strip don't reach the canvas
        }
        // Bubble list (Lev): the ◇ count, top-right, opens a column of cut-out thumbnails.
        bubbleList = BubbleList(this).also { it.host = this }
        val canvas = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(glView)
            addView(editOverlay)
            addView(IconButton(context, Icon.BACK).apply { setOnClickListener { finish() } },
                FrameLayout.LayoutParams((42 * dens).toInt(), (42 * dens).toInt(), Gravity.TOP or Gravity.START).apply { setMargins((14 * dens).toInt(), (18 * dens).toInt(), 0, 0) })
            addView(bubbleList, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, (18 * dens).toInt(), (10 * dens).toInt(), (10 * dens).toInt()) })
            addView(row2, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(binView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (72 * dens).toInt(), Gravity.BOTTOM))
            addView(heatChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, (34 * dens).toInt(), Gravity.TOP or Gravity.START).apply { setMargins((64 * dens).toInt(), (22 * dens).toInt(), 0, 0) })
            addView(matchHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (22 * dens).toInt() })
            addView(heatLegend, FrameLayout.LayoutParams((250 * dens).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (68 * dens).toInt() })
        }
        // Keep the bubble list above row 2 when row 2 is showing.
        row2.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (bubbleList.layoutParams as FrameLayout.LayoutParams).let { lp ->
                val want = (10 * dens).toInt() + if (v.visibility == android.view.View.VISIBLE) v.height else 0
                if (lp.bottomMargin != want) { lp.bottomMargin = want; bubbleList.layoutParams = lp }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PANEL)
            addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        setHeat(heatPrefs.getBoolean("heat", false))
        editOverlay.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> xf.viewW = v.width; xf.viewH = v.height; editOverlay.invalidate() }
        hideSystemBars()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        selectionChanged()
        load()
    }

    private fun load() = io.execute {
        try {
            val original = Library.read(this, uri)
            val recovered = Autosave.recover(this, uri)
            val loaded = WblLoader.load(recovered ?: original) ?: run { toast("Can't decode this image"); finish(); return@execute }
            runOnUiThread {
                xf.imgW = loaded.bitmap.width; xf.imgH = loaded.bitmap.height
                doc = EditDoc.from(loaded.doc)
                bubbleList.setImage(loaded.bitmap)        // after the doc: the list reads the bubbles; starts closed
                autosave = Autosave(this, uri, original)
                if (recovered != null) { toast("Recovered unsaved changes"); autosave?.commit(doc.toWbl()) }
                glView.queueEvent { renderer.setScene(loaded.bitmap, loaded.doc) }
                glView.requestRender()
                editOverlay.invalidate()
                selectionChanged()                        // count, panel state for the loaded file
            }
        } catch (e: Exception) {
            toast("Couldn't open: ${e.message}"); finish()
        }
    }

    // ---- Host (called by the editOverlay) ----

    override fun commit(before: EditDoc) {
        redo.clear()                                          // a new change ends the redo trail
        undo.addLast(before)
        while (undo.size > 100) undo.removeFirst()
        saveAndRefresh()
    }

    private fun saveAndRefresh() {
        val wbl = doc.toWbl()
        autosave?.commit(wbl)
        glView.removeCallbacks(heatPush)
        glView.queueEvent { renderer.heatHold = false; renderer.updateDoc(wbl) }
        glView.requestRender()
        editOverlay.invalidate()
        selectionChanged()
    }

    override fun selectionChanged() {
        if (::bubbleList.isInitialized) { bubbleList.refresh(); bubbleList.reveal(editOverlay.selected) }
        val b = selectedBubble()
        val editable = b != null && !b.locked
        b?.let { sliders.set(if (it.freeze) it.hold else it.wobbliness, it.extent) }
        sliders.setFreeze(b?.freeze == true)
        sliders.showCurve(if (b?.useCurve == true) b.curve else null, b?.ceiling ?: Falloff.CEIL_OFF, if (b?.freeze == true) 1f else b?.height ?: 1f)
        sliders.isEnabled = editable
        showRow2(editable)
        // Lock reads "Unlock" in amber on a locked bubble: why the sliders are gone, and how to get them back.
        if (b?.locked == true) lockBtn.set(Icon.UNLOCK, "Unlock") else lockBtn.set(Icon.LOCK, "Lock")
        lockBtn.warm = b?.locked == true
        addBtn.isEnabled = !previewing
        copyBtn.isEnabled = b != null && !previewing
        lockBtn.isEnabled = b != null && !previewing
        bubbleBtn.isEnabled = true                          // always: holds "Outline style" even with nothing selected
        bubbleBtn.highlighted = editable
        undoBtn.isEnabled = undo.isNotEmpty(); redoBtn.isEnabled = redo.isNotEmpty()
    }

    /** Row 2 slides up from behind row 1 / slides away. */
    private fun showRow2(on: Boolean) {
        if (!::row2.isInitialized) return
        val visible = row2.visibility == android.view.View.VISIBLE
        if (on == visible) return
        row2.animate().cancel()
        if (on) {
            row2.visibility = android.view.View.VISIBLE
            row2.alpha = 0f; row2.translationY = 24 * resources.displayMetrics.density
            row2.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        } else {
            row2.animate().alpha(0f).translationY(16 * resources.displayMetrics.density).setDuration(110)
                .withEndAction { row2.visibility = android.view.View.GONE; row2.requestLayout() }.start()
        }
    }

    override fun viewChanged() {
        val z = xf.zoom; val px = xf.panX; val py = xf.panY
        glView.queueEvent { renderer.setView(z, px, py) }
        glView.requestRenderAndWait()      // image frame first, so the outlines invalidated next land with it
        editOverlay.invalidate()
    }

    /** Fit → Fill (cover the screen) → Fit. Anything zoomed or panned goes back to Fit. */
    private fun cycleFit() {
        val fit = minOf(xf.viewW.toFloat() / xf.imgW, xf.viewH.toFloat() / xf.imgH)
        val fill = maxOf(xf.viewW.toFloat() / xf.imgW, xf.viewH.toFloat() / xf.imgH)
        val atFit = kotlin.math.abs(xf.zoom - 1f) < 1e-3f && xf.panX == 0f && xf.panY == 0f
        xf.reset()
        if (atFit) xf.zoom = fill / fit
        viewChanged()
    }

    // ---- BubbleList.Host ----

    override val bubbles: List<EditBubble> get() = doc.bubbles
    override val selectedIndex: Int get() = editOverlay.selected

    override fun select(index: Int) {
        editOverlay.selected = index
        glideTo(index)
    }

    override fun reorder(from: Int, to: Int) {
        if (from == to || from !in doc.bubbles.indices || to !in doc.bubbles.indices) return
        val before = doc.copy()
        val sel = doc.bubbles.getOrNull(editOverlay.selected)
        val b = doc.bubbles.removeAt(from)
        doc.bubbles.add(to, b)
        editOverlay.selected = sel?.let { doc.bubbles.indexOf(it) } ?: -1
        commit(before)
    }

    override fun menu(index: Int, anchor: android.view.View) {
        val b = doc.bubbles.getOrNull(index) ?: return
        editOverlay.selected = index
        fun change(f: () -> Unit) { val before = doc.copy(); f(); commit(before) }
        UiMenu.show(anchor, listOf(
            MenuItem("Rename…", Icon.RENAME) {
                UiDialog.show(this, "Rename bubble", input = b.name ?: "", action = "Rename") { t ->
                    change { b.name = t.trim().ifEmpty { null } }
                }
            },
            MenuItem(if (b.locked) "Unlock" else "Lock", if (b.locked) Icon.UNLOCK else Icon.LOCK) { change { b.locked = !b.locked } },
            MenuItem(if (b.visible) "Hide (mute wobble)" else "Show", Icon.EYE) { change { b.visible = !b.visible } },
            MenuItem("Duplicate", Icon.DUPLICATE, enabled = !previewing) { change { editOverlay.duplicate() } },
            MenuItem("Delete", Icon.DELETE, danger = true, enabled = !b.locked && !previewing) { deleteSelected() },
        ))
    }

    override fun openChanged(open: Boolean) {}

    /** Smoothly pans so the bubble's middle sits in the middle of the canvas (zoom unchanged). */
    private var glide: android.animation.ValueAnimator? = null
    private fun glideTo(index: Int) {
        val b = doc.bubbles.getOrNull(index) ?: return
        val sx = xf.toScreenX(b.x * xf.imgW); val sy = xf.toScreenY(b.y * xf.imgH)
        val listW = if (bubbleList.open) bubbleList.width else 0
        val dx = (xf.viewW - listW) / 2f - sx; val dy = xf.viewH / 2f - sy
        if (kotlin.math.abs(dx) < 2 && kotlin.math.abs(dy) < 2) return
        val px0 = xf.panX; val py0 = xf.panY
        glide?.cancel()
        glide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { xf.panX = px0 + dx * (it.animatedValue as Float); xf.panY = py0 + dy * (it.animatedValue as Float); viewChanged() }
            start()
        }
    }

    /** 3 grid cells at fit (the mesh's cell is ~6 screen px at fit). Lev's real files use ~4-cell details. */
    override fun minWidth() = 3 * 6f / xf.base

    override fun undoTap() = undoLast()

    // ---- heat map (Lev): where the picture can move, as colour over it ----

    private val heatPrefs by lazy { getSharedPreferences("editor", MODE_PRIVATE) }
    private var heatOn = false
    private val heatIcon by lazy { android.widget.ImageView(this) }
    private val heatText by lazy { uiText("HEAT", 11f, Ui.mono, Ui.TEXT2).apply { letterSpacing = 0.06f } }
    private val heatChip by lazy {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val dd = resources.displayMetrics.density
            setPadding((10 * dd).toInt(), 0, (11 * dd).toInt(), 0)
            addView(heatIcon, LinearLayout.LayoutParams((16 * dd).toInt(), (16 * dd).toInt()).apply { marginEnd = (5 * dd).toInt() })
            addView(heatText)
            isClickable = true
            setOnClickListener { setHeat(!heatOn); Ui.haptic(this, android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
        }
    }
    private val heatLegend by lazy {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = android.view.View.GONE
            val dd = resources.displayMetrics.density
            background = Ui.box(context, Ui.PANEL_GLASS, 6f, Ui.LINE)
            setPadding((10 * dd).toInt(), (5 * dd).toInt(), (10 * dd).toInt(), (5 * dd).toInt())
            addView(uiText("still", 10f, Ui.mono, Ui.TEXT2))
            addView(android.view.View(context).apply {
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x4028_6EFF.toInt(), 0xFF3CAAFF.toInt(), 0xFFFFBF3C.toInt(), 0xFFFF5A32.toInt(), 0xFFFF2840.toInt())).apply { cornerRadius = 4 * dd }
            }, LinearLayout.LayoutParams(0, (7 * dd).toInt(), 1f).apply { setMargins((8 * dd).toInt(), 0, (8 * dd).toInt(), 0) })
            addView(heatLegendEnd)
        }
    }
    private val heatLegendEnd by lazy { uiText("moves most", 10f, Ui.mono, Ui.TEXT2) }

    private fun setHeat(on: Boolean) {
        heatOn = on
        heatPrefs.edit().putBoolean("heat", on).apply()
        heatChip.background = if (on) Ui.box(this, Ui.ACCENT, 6f) else Ui.pressable(this, Ui.box(this, Ui.PANEL_GLASS, 6f, Ui.LINE), 6f)
        heatText.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT2)
        heatIcon.setImageDrawable(Icon.HEAT.drawable(if (on) Ui.ON_ACCENT else Ui.TEXT2))
        heatLegend.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        heatLegendEnd.text = if (previewing) "moving now" else "moves most"
        editOverlay.heatOn = on
        glView.queueEvent { renderer.heat = on }
        glView.requestRender()
    }

    private var heatPushAt = 0L
    private val heatPush = Runnable { pushDocForHeat() }
    private fun pushDocForHeat() {
        heatPushAt = android.os.SystemClock.uptimeMillis()
        val wbl = doc.toWbl()
        glView.queueEvent { renderer.updateDoc(wbl) }
        glView.requestRender()
    }

    /** Mid-drag with the heat map on: let the map follow, at most ~8 times a second. */
    override fun shapeChanging() {
        if (!heatOn || previewing) return
        glView.queueEvent { renderer.heatHold = true }                // same colour scale until the change is committed
        val wait = 120L - (android.os.SystemClock.uptimeMillis() - heatPushAt)
        glView.removeCallbacks(heatPush)
        if (wait <= 0) pushDocForHeat() else glView.postDelayed(heatPush, wait)
    }

    // ---- Match… (Lev): copy another bubble's settings onto the selected one ----

    private var matchTarget = -1
    private val matchHint by lazy {
        uiText("", 13f, Ui.ui, Ui.TEXT).apply {
            background = Ui.box(this@EditorActivity, Ui.PANEL_GLASS, 10f, Ui.ACCENT)
            val p = Ui.px(this@EditorActivity, 12f); setPadding(p, p * 2 / 3, p, p * 2 / 3)
            gravity = Gravity.CENTER; visibility = android.view.View.GONE
            isClickable = true; setOnClickListener { endMatch() }
        }
    }
    private val matchHidden = ArrayList<android.view.View>()

    private fun startMatch() {
        val i = editOverlay.selected
        val b = doc.bubbles.getOrNull(i) ?: return
        if (b.locked) return
        matchTarget = i
        editOverlay.matching = true
        matchHint.text = if (b.freeze) "Tap a freeze zone to copy its settings  ·  ✕" else "Tap a bubble to copy its settings  ·  ✕"
        matchHint.visibility = android.view.View.VISIBLE
        matchHidden.clear()
        for (v in listOf(bottomBar, row2, bubbleList, heatChip, heatLegend)) if (v.visibility == android.view.View.VISIBLE) { v.visibility = android.view.View.INVISIBLE; matchHidden += v }
    }

    private fun endMatch() {
        if (!editOverlay.matching) return
        editOverlay.matching = false
        matchHint.visibility = android.view.View.GONE
        for (v in matchHidden) v.visibility = android.view.View.VISIBLE
        matchHidden.clear()
        selectionChanged()
    }

    override fun matchPicked(index: Int) {
        val dst = doc.bubbles.getOrNull(matchTarget); val src = doc.bubbles.getOrNull(index)
        if (dst == null || src == null) { endMatch(); return }
        if (src.freeze != dst.freeze) { toast(if (dst.freeze) "A freeze zone can only match another zone" else "Pick a bubble, not a freeze zone"); return }
        val before = doc.copy()
        // Motion only; the outline, position, name and lock stay the target's own.
        dst.wobbliness = src.wobbliness; dst.extent = src.extent; dst.height = src.height
        dst.area = src.area; dst.curve = src.curve?.copyOf(); dst.useCurve = src.useCurve; dst.ceiling = src.ceiling
        dst.hold = src.hold
        Ui.haptic(editOverlay, android.view.HapticFeedbackConstants.CLOCK_TICK)
        endMatch()
        commit(before)                                             // one undo step
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (editOverlay.matching) endMatch() else @Suppress("DEPRECATION") super.onBackPressed() }

    // The bin (Lev): drag a point or a bubble down to the very bottom to remove it.
    private val binText by lazy { uiText("", 13f, Ui.uiSemi, Ui.DANGER) }
    private val binIcon by lazy { android.widget.ImageView(this) }
    private val binView by lazy {
        LinearLayout(this).apply {
            gravity = Gravity.CENTER; alpha = 0f; visibility = android.view.View.GONE
            val dd = resources.displayMetrics.density
            addView(binIcon, LinearLayout.LayoutParams((22 * dd).toInt(), (22 * dd).toInt()).apply { marginEnd = (8 * dd).toInt() })
            addView(binText)
        }
    }
    private var binState = EditorOverlay.BIN_OFF

    override fun bin(state: Int, label: String) {
        if (state == binState && (state == EditorOverlay.BIN_OFF || binText.text == label)) return
        val was = binState; binState = state
        if (state == EditorOverlay.BIN_OFF) {
            binView.animate().alpha(0f).setDuration((120 * Ui.anim).toLong()).withEndAction { if (binState == EditorOverlay.BIN_OFF) binView.visibility = android.view.View.GONE }.start()
            return
        }
        binText.text = label
        val (bg, fg) = when (state) {
            EditorOverlay.BIN_HOT -> Ui.DANGER to android.graphics.Color.WHITE
            EditorOverlay.BIN_NO -> 0xE62A2C31.toInt() to Ui.MUTED
            else -> 0xE61C1D21.toInt() to Ui.DANGER
        }
        binView.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(bg), android.graphics.drawable.ColorDrawable(if (state == EditorOverlay.BIN_HOT) bg else fg))).apply { setLayerHeight(1, (2 * resources.displayMetrics.density).toInt()) }
        binText.setTextColor(fg)
        binIcon.setImageDrawable(Icon.DELETE.drawable(fg))
        if (was == EditorOverlay.BIN_OFF) {
            binView.visibility = android.view.View.VISIBLE
            binView.animate().alpha(1f).setDuration((120 * Ui.anim).toLong()).start()
        }
    }

    override fun previewTouch(e: MotionEvent) {
        val idx = e.actionIndex; val t = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx); glView.queueEvent { renderer.touchDown(id, x, y, t) } }
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) { val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p); glView.queueEvent { renderer.touchMove(id, x, y) } }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { val id = e.getPointerId(idx); val x = e.getX(idx); val y = e.getY(idx); glView.queueEvent { renderer.touchUp(id, x, y, t) } }
            MotionEvent.ACTION_CANCEL -> for (p in 0 until e.pointerCount) { val id = e.getPointerId(p); val x = e.getX(p); val y = e.getY(p); glView.queueEvent { renderer.touchUp(id, x, y, Long.MAX_VALUE) } }
        }
        glView.requestRender()
    }

    // ---- actions ----

    private fun selectedBubble(): EditBubble? = doc.bubbles.getOrNull(editOverlay.selected)

    private fun addBubble() {
        val before = doc.copy()
        // A round bubble at the middle of the screen, a quarter of the visible image wide.
        val cx = xf.toImageX(xf.viewW / 2f) / xf.imgW
        val cy = xf.toImageY(xf.viewH / 2f) / xf.imgH
        val sizePx = minOf(xf.viewW, xf.viewH) / 8f / xf.scale
        doc.bubbles.add(EditBubble.circle(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), sizePx / xf.imgW, sizePx / xf.imgH).apply {
            if (com.levkar.wobble.Settings.newBubbleModern(this@EditorActivity)) {       // Lev's default: Area + Curve
                area = true; useCurve = true; curve = Falloff.seed(extent)
            }
        })
        editOverlay.selected = doc.bubbles.lastIndex
        commit(before)
    }

    // ---- Distance (Point / Area) and Falloff (Extent / Curve): one pop-up, two independent rows ----

    /**
     * The Bubble… pop-up: everything about the selected bubble (Distance, Falloff, Mirror, Recenter, Delete),
     * plus Outline style (all bubbles), which is the only item when nothing is selected.
     */
    private fun showBubblePopup(anchor: android.view.View) {
        val b = selectedBubble()
        val editable = b != null && !b.locked && !previewing
        val c = this
        val d = resources.displayMetrics.density
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL, 12f, Ui.LINE)
            val p = (12 * d).toInt(); setPadding(p, (6 * d).toInt(), p, (6 * d).toInt())
        }
        lateinit var refresh: () -> Unit
        // Segment colour: amber, or blue on a Freeze zone (Lev: only these toggles and the curve turn blue).
        val freezeTint: (Boolean) -> Int = { if (b?.freeze == true) Ui.FREEZE else Ui.ACCENT }
        fun row(title: String, hint: String, a: String, bLabel: String, isB: () -> Boolean, sub: () -> String?,
                tint: (Boolean) -> Int = freezeTint, titleOf: (() -> String)? = null, pick: (Boolean) -> Unit): LinearLayout {
            val titleView = uiText(title.uppercase(), 12f, Ui.ui).apply { letterSpacing = 0.06f }
            // Children of a vertical LinearLayout default to full width, so the text itself must be centered.
            val segA = uiText(a, 13f, Ui.ui).apply { gravity = Gravity.CENTER }; val segB = uiText(bLabel, 13f, Ui.ui).apply { gravity = Gravity.CENTER }
            val subA = uiText("", 9f, Ui.mono, Ui.ACCENT).apply { gravity = Gravity.CENTER }
            val boxA = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(segA); addView(subA) }
            val boxB = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(segB) }
            val segs = LinearLayout(c).apply {
                background = Ui.box(c, Ui.FIELD, 8f, 0xFF2F3136.toInt())
                val p = (3 * d).toInt(); setPadding(p, p, p, p)
                addView(boxA, LinearLayout.LayoutParams(0, (38 * d).toInt(), 1f).apply { marginEnd = p })
                addView(boxB, LinearLayout.LayoutParams(0, (38 * d).toInt(), 1f))
            }
            boxA.setOnClickListener { pick(false); refresh() }
            boxB.setOnClickListener { pick(true); refresh() }
            val r = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                val v = (6 * d).toInt(); setPadding(0, v, 0, v)
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleView)
                    addView(uiText(hint, 10f, Ui.mono, Ui.MUTED).apply { setPadding(0, (3 * d).toInt(), 0, 0) })
                }, LinearLayout.LayoutParams((104 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(segs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            refreshers += {
                val onB = isB()
                titleOf?.let { titleView.text = it().uppercase() }
                for ((box, txt, on) in listOf(Triple(boxA, segA, !onB), Triple(boxB, segB, onB))) {
                    val col = tint(onB)
                    box.background = if (on) Ui.box(c, col, 6f) else Ui.pressable(c, null, 6f)
                    txt.setTextColor(if (on) (if (col == Ui.FREEZE) Ui.ON_FREEZE else Ui.ON_ACCENT) else Ui.TEXT2)
                }
                val s = sub(); subA.text = s ?: ""; subA.visibility = if (s == null) android.view.View.GONE else android.view.View.VISIBLE
                subA.setTextColor(if (!onB) Ui.ON_ACCENT else Ui.ACCENT)
            }
            return r
        }
        refreshers.clear()
        lateinit var popup: android.widget.PopupWindow
        fun line() = card.addView(android.view.View(c).apply { setBackgroundColor(Ui.LINE_SOFT) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        fun act(f: () -> Boolean) { popup.dismiss(); val before = doc.copy(); if (f()) commit(before) }
        if (b != null && b.locked) {
            card.addView(uiText("This bubble is locked. Unlock it to change it.", 12.5f, Ui.ui, Ui.TEXT2).apply {
                val v = (10 * d).toInt(); setPadding(0, v, 0, v) })
            line()
        }
        if (editable && b != null) {
            // Modern options first (Lev uses them most); the original app's Point and Basic (= Extent) second.
            // Type: a wobble bubble, or a Freeze zone that holds still whatever moves under it.
            card.addView(row("Type", "moves, or\nholds still", "Wobble", "Freeze", { b.freeze }, { null },
                tint = { freeze -> if (freeze) Ui.FREEZE else Ui.ACCENT }) { setFreeze(it) })
            line()
            card.addView(row("Distance", "how far from\nthe peak", "Area", "Point", { !b.area }, { null }) { setArea(!it) })
            line()
            card.addView(row("Falloff", "how motion\nfades out", "Curve", "Basic", { !b.useCurve },
                { if (!b.useCurve && b.curve != null) "saved curve" else null }, titleOf = { if (b.freeze) "Edge" else "Falloff" }) { setUseCurve(!it) })
            line()
            // Action tiles: Mirror ↔ · Mirror ↕ · Recenter
            card.addView(LinearLayout(c).apply {
                val v = (8 * d).toInt(); setPadding(0, v, 0, v)
                fun tile(icon: Icon, label: String, f: () -> Boolean) = ToolButton(c, icon, label).apply {
                    background = Ui.pressable(c, Ui.box(c, 0, 8f, Ui.LINE), 8f)
                    setOnClickListener { act(f) }
                }
                val m = (6 * d).toInt()
                addView(tile(Icon.MIRROR_H, "Mirror ↔") { editOverlay.mirror(horizontal = true) }, LinearLayout.LayoutParams(0, (58 * d).toInt(), 1f).apply { marginEnd = m })
                addView(tile(Icon.MIRROR_V, "Mirror ↕") { editOverlay.mirror(horizontal = false) }, LinearLayout.LayoutParams(0, (58 * d).toInt(), 1f).apply { marginEnd = m })
                addView(tile(Icon.RECENTER, "Recenter") { editOverlay.recenter() }, LinearLayout.LayoutParams(0, (58 * d).toInt(), 1f).apply { marginEnd = m })
                addView(ToolButton(c, Icon.PICK, "Match…").apply {
                    background = Ui.pressable(c, Ui.box(c, 0, 8f, Ui.LINE), 8f)
                    setOnClickListener { popup.dismiss(); startMatch() }
                }, LinearLayout.LayoutParams(0, (58 * d).toInt(), 1f))
            })
            line()
        }
        fun item(icon: Icon, label: String, note: String?, danger: Boolean, f: () -> Unit) = card.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pressable(c, null, 8f); isClickable = true
            val s = (18 * d).toInt()
            addView(android.widget.ImageView(c).apply { setImageDrawable(icon.drawable(if (danger) Ui.DANGER else Ui.TEXT2)) },
                LinearLayout.LayoutParams(s, s).apply { marginEnd = (12 * d).toInt() })
            addView(uiText(label, 14f, Ui.ui, if (danger) Ui.DANGER else Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            note?.let { addView(uiText(it, 10f, Ui.mono, Ui.MUTED)) }
            setOnClickListener { popup.dismiss(); f() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()))
        if (b == null) item(Icon.RENAME, "Credits…", com.levkar.wobble.ui.CreditsDialog.summary(doc.author, doc.authorAi, doc.source)?.take(22), false) {
            com.levkar.wobble.ui.CreditsDialog.show(this, doc.author, doc.authorAi, doc.source) { a, ai, src ->
                if (a == doc.author && ai == doc.authorAi && src == doc.source) return@show
                val before = doc.copy()
                doc.author = a; doc.authorAi = ai; doc.source = src
                commit(before)                                    // undoable, autosaved like any edit
            }
        }
        item(Icon.PALETTE, "Outline style…", "all bubbles", false) { AppearanceSheet(this) { editOverlay.reloadStyles() }.show() }
        // Two ways to clear (Lev): only the unlocked ("active") bubbles, or everything.
        if (b == null && !previewing && doc.bubbles.any { !it.locked } && doc.bubbles.any { it.locked }) {
            line(); item(Icon.DELETE, "Clear unlocked…", "keeps locked", true) { confirmClear(all = false) } }
        if (b == null && !previewing && doc.bubbles.isNotEmpty()) { line(); item(Icon.DELETE, "Clear all…", null, true) { confirmClear(all = true) } }
        if (editable) { line(); item(Icon.DELETE, if (b?.freeze == true) "Delete zone" else "Delete bubble", null, true) { deleteSelected() } }
        refresh = { for (f in refreshers) f() }
        refresh()
        popup = UiMenu.showCard(anchor, card, resources.displayMetrics.widthPixels - (24 * d).toInt())
    }
    private val refreshers = ArrayList<() -> Unit>()

    /** Clear: the unlocked bubbles only, or [all] of them (locked too). After a confirm; one undo step brings them back. */
    private fun confirmClear(all: Boolean) {
        val n = if (all) doc.bubbles.size else doc.bubbles.count { !it.locked }
        if (n == 0) return
        val what = if (n == 1) "the bubble" else "$n bubbles"
        val title = if (all) "Clear the canvas?" else "Clear unlocked bubbles?"
        val msg = if (all) "Removes all $what, locked ones too. Undo brings them back."
                  else "Removes $what; locked ones stay. Undo brings them back."
        UiDialog.show(this, title, msg, action = "Clear", danger = true) {
            val before = doc.copy()
            if (all) doc.bubbles.clear() else doc.bubbles.removeAll { !it.locked }
            editOverlay.selected = -1
            commit(before)
        }
    }

    /** Type: Wobble (false) or Freeze zone (true). Keeps everything else, so switching back restores the bubble. */
    private fun setFreeze(on: Boolean) {
        val b = selectedBubble() ?: return
        if (b.freeze == on) return
        val before = doc.copy()
        b.freeze = on
        commit(before)
    }

    /** Distance: Point (false) or Area (true). Back to Point only if the outline is visible from the center dot. */
    private fun setArea(area: Boolean) {
        val b = selectedBubble() ?: return
        if (b.area != area) toggleMode()
    }

    /** Falloff: Basic (= the original's Extent, false) or Curve (true). A curve is never thrown away; the first one starts from the Extent shape. */
    private fun setUseCurve(on: Boolean) {
        val b = selectedBubble() ?: return
        if (b.useCurve == on) return
        val before = doc.copy()
        if (on && b.curve == null) b.curve = Falloff.seed(b.extent)
        b.useCurve = on
        commit(before)
    }

    private var curveBefore: EditDoc? = null

    private fun openCurveSheet() {
        val b = selectedBubble() ?: return
        if (b.locked) return
        val index = editOverlay.selected
        fun live(f: (EditBubble) -> Unit) = doc.bubbles.getOrNull(index)?.let {
            if (curveBefore == null) curveBefore = doc.copy()
            f(it)
            sliders.showCurve(it.curve, it.ceiling, if (it.freeze) 1f else it.height)
            editOverlay.invalidate()
            if (previewing) { val wbl = doc.toWbl(); glView.queueEvent { renderer.updateDoc(wbl) }; glView.requestRender() }
        }
        CurveSheet(this, b.curve ?: Falloff.seed(b.extent), b.extent, b.ceiling, if (b.freeze) 1f else b.height, b.freeze,
            onChange = { pts -> live { it.curve = pts } },
            onCeiling = { v -> live { it.ceiling = v } },
            onScale = { v -> live { it.height = v } },
            onCommit = { curveBefore?.let { commit(it) }; curveBefore = null },
            onReset = {
                doc.bubbles.getOrNull(index)?.let {
                    val before = doc.copy()
                    it.curve = Falloff.seed(it.extent); it.ceiling = Falloff.CEIL_OFF       // Scale stays: Basic uses it too
                    commit(before)
                    it.curve
                }
            }).show()
    }

    /** Point ↔ Area for the selected bubble. Back to Point only if the outline is visible from its center. */
    private fun toggleMode() {
        val b = selectedBubble() ?: return
        if (b.area) {
            val asPoint = b.copy().apply { area = false }
            if (!asPoint.shape(xf.imgW, xf.imgH).isStarShaped()) {
                toast("Can't switch to Point: part of the outline is hidden from the center dot. Move the dot or the corners first.")
                return
            }
        }
        val before = doc.copy()
        b.area = !b.area
        commit(before)
    }

    private fun toggleLock() {
        val b = selectedBubble() ?: return
        val before = doc.copy()
        b.locked = !b.locked
        commit(before)
    }

    private fun deleteSelected() {
        val i = editOverlay.selected
        if (i !in doc.bubbles.indices || doc.bubbles[i].locked) return
        val before = doc.copy()
        doc.bubbles.removeAt(i)
        editOverlay.selected = -1
        commit(before)
    }

    private fun redoLast() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(doc)
        doc = next
        if (editOverlay.selected !in doc.bubbles.indices) editOverlay.selected = -1
        saveAndRefresh()
    }

    private fun undoLast() {
        val prev = undo.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        redo.addLast(doc)
        doc = prev
        if (editOverlay.selected !in doc.bubbles.indices) editOverlay.selected = -1
        saveAndRefresh()
    }

    /** Preview is a toggle: tilt and one-finger pokes wobble the image; editing gestures pause. */
    private fun setPreview(on: Boolean) {
        if (on == previewing) return
        previewing = on
        if (!on) { peekAnim?.cancel(); editOverlay.peek = 0f }
        if (on) previewBtn.set(Icon.STOP, "Stop") else previewBtn.set(Icon.PLAY, "Preview")
        heatLegendEnd.text = if (on) "moving now" else "moves most"
        glView.queueEvent { renderer.live = on }
        glView.requestRender()
        editOverlay.invalidate()
        selectionChanged()
    }

    /** During preview, fade the selected bubble's outline in while a slider is dragged, out after. */
    private fun peek(on: Boolean) {
        peekAnim?.cancel()
        peekAnim = android.animation.ValueAnimator.ofFloat(editOverlay.peek, if (on) 1f else 0f).apply {
            duration = if (on) 150 else 500
            if (!on) startDelay = 300
            addUpdateListener { editOverlay.peek = it.animatedValue as Float; editOverlay.invalidate() }
            start()
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- lifecycle ----

    override fun onSensorChanged(event: SensorEvent) {
        if (previewing && tilt.onSample(event.values[0], event.values[1])) glView.requestRender()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val mode = Settings.ringMode(this)
        glView.queueEvent { renderer.ringMode = mode }; renderer.loadSettings(this)
        editOverlay.reloadStyles()                                   // Outline style may have changed in Settings
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        glView.requestRender()
    }

    override fun onPause() {
        setPreview(false)                                       // never come back to a canvas that ignores editing
        autosave?.flush()
        sensors?.unregisterListener(this)
        handler.removeCallbacks(nextFrame)
        glView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
