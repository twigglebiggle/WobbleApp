package com.levkar.wobble.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.levkar.wobble.core.EditBubble
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.uiText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The bubble list (Lev's idea): a floating column of squares on the right of the canvas, one per bubble,
 * each showing the actual piece of the picture inside that bubble, cut along its outline.
 * Newest on top. The ◇ count at the top opens and closes it.
 *
 * Tap a square = select (the host glides the view to it). Long-press lifts it: drag to reorder,
 * or let go without moving for its menu. Order is organisation only: overlaps add up, so it never
 * changes the motion; it is the bubbles' order in the file.
 */
class BubbleList(c: Context) : LinearLayout(c) {

    interface Host {
        val bubbles: List<EditBubble>
        val selectedIndex: Int
        fun select(index: Int)
        /** Move bubble [from] to position [to] (indices in the file order). */
        fun reorder(from: Int, to: Int)
        fun menu(index: Int, anchor: View)
        fun openChanged(open: Boolean)
    }

    lateinit var host: Host
    private val d = resources.displayMetrics.density
    private val count = c.uiText("", 13f, Ui.monoMedium)
    private val scroll = ScrollView(c).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val column = LinearLayout(c).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val p = (8 * d).toInt(); setPadding(0, p, 0, p)
    }
    private val thumbs = Thumbs()
    var open = false; private set

    /** The picture the thumbnails are cut from (downscaled copy). */
    fun setImage(bmp: Bitmap) { thumbs.setSource(bmp); refresh() }

    init {
        orientation = VERTICAL
        background = Ui.box(c, Ui.PANEL_GLASS, 12f, 0x17FFFFFF)
        elevation = 10 * d
        // Header = the bubble count; tapping it opens/closes the list.
        val header = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            val s = (14 * d).toInt()
            addView(ImageView(c).apply { setImageDrawable(Icon.DIAMOND.drawable(Ui.ACCENT, 2.2f)) }, LayoutParams(s, s).apply { marginEnd = (6 * d).toInt() })
            addView(count)
            isClickable = true
            background = Ui.pressable(c, null, 12f)
            val hp = (10 * d).toInt(); setPadding(hp, 0, hp, 0)      // same box whether open or closed
            setOnClickListener { setOpen(!open) }
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, (38 * d).toInt()))
        scroll.addView(column)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.visibility = GONE
    }

    fun setOpen(on: Boolean, animate: Boolean = true) {
        if (on == open) return
        open = on
        scroll.visibility = if (on) VISIBLE else GONE
        (layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.width = if (on) (70 * d).toInt() else LayoutParams.WRAP_CONTENT
            it.height = if (on) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            layoutParams = it
        }
        if (on) { refresh(); if (animate) unroll() }
        host.openChanged(on)
    }

    private fun unroll() {
        val ease = android.view.animation.DecelerateInterpolator(2f)
        for (i in 0 until column.childCount) {
            val v = column.getChildAt(i)
            v.alpha = 0f; v.translationY = -8 * d
            v.animate().alpha(1f).translationY(0f).setStartDelay(30L + i.coerceAtMost(12) * 24L).setDuration(160).setInterpolator(ease).start()
        }
    }

    /** Rebuild from the host's bubbles (cheap: thumbnails are cached by shape). */
    fun refresh() {
        if (!::host.isInitialized) return
        val bs = host.bubbles
        count.text = bs.size.toString()
        if (!open) return
        thumbs.retain(bs)
        val n = bs.size
        while (column.childCount > n) column.removeViewAt(column.childCount - 1)
        while (column.childCount < n) column.addView(Tile(context), LayoutParams((52 * d).toInt(), (52 * d).toInt()).apply { bottomMargin = (7 * d).toInt() })
        for (row in 0 until n) {
            val index = n - 1 - row                       // newest on top
            (column.getChildAt(row) as Tile).bind(index, bs[index], index == host.selectedIndex)
        }
    }

    /** Keeps the selected square in view. */
    fun reveal(index: Int) {
        if (!open) return
        val row = host.bubbles.size - 1 - index
        val v = column.getChildAt(row) ?: return
        val top = v.top; val bottom = v.bottom
        if (top < scroll.scrollY) scroll.smoothScrollTo(0, top - (8 * d).toInt())
        else if (bottom > scroll.scrollY + scroll.height) scroll.smoothScrollTo(0, bottom - scroll.height + (8 * d).toInt())
    }

    // ---- one square ----

    private inner class Tile(c: Context) : View(c) {
        var index = -1
        private var bubble: EditBubble? = null
        private var selected = false
        private val r = RectF()
        private val clip = Path()
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.accentA(0x2E); strokeWidth = 6 * d }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val lock = Icon.LOCK.drawable(Ui.TEXT, 2.2f)
        private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BG }
        private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3A3C41.toInt(); strokeWidth = 1 * d }
        private val hiddenText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textSize = 7.5f * d; runCatching { typeface = Ui.mono } }

        fun bind(i: Int, b: EditBubble, sel: Boolean) { index = i; bubble = b; selected = sel; invalidate() }

        override fun onDraw(cv: Canvas) {
            val rad = 7 * d
            r.set(1 * d, 1 * d, width - 1 * d, height - 1 * d)
            clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
            cv.save(); cv.clipPath(clip)
            cv.drawPaint(checker)                                     // faint checker: dark cut-outs stay readable
            bubble?.let { b -> thumbs.get(b)?.let { t ->
                bmpPaint.alpha = if (b.visible) 255 else 75
                cv.drawBitmap(t, null, r, bmpPaint)
            } }
            cv.restore()
            border.strokeWidth = (if (selected) 2f else 1f) * d
            border.color = when { bubble?.freeze == true -> Ui.FREEZE; selected -> Ui.ACCENT; else -> 0xFF33353A.toInt() }
            if (selected) cv.drawRoundRect(r, rad, rad, glow)
            cv.drawRoundRect(r, rad, rad, border)
            val b = bubble ?: return
            if (b.locked) {
                val s = 16 * d; val cx = width - s / 2 - 1 * d; val cy = height - s / 2 - 1 * d
                cv.drawCircle(cx, cy, s / 2, badgeBg); cv.drawCircle(cx, cy, s / 2, badgeRing)
                lock.setBounds((cx - 5.5f * d).toInt(), (cy - 5.5f * d).toInt(), (cx + 5.5f * d).toInt(), (cy + 5.5f * d).toInt()); lock.draw(cv)
            }
            if (!b.visible) cv.drawText("hidden", 4 * d, height - 4 * d, hiddenText)
        }

        // ---- touch: tap = select; long-press = lift → drag to reorder, or release for the menu ----
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downY = 0f; private var downRawY = 0f
        private var lifted = false; private var dragged = false
        private val lift = Runnable {
            lifted = true
            Ui.haptic(this)
            parent?.requestDisallowInterceptTouchEvent(true)
            animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
            elevation = 12 * d
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.y; downRawY = e.rawY; lifted = false; dragged = false
                    postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!lifted) { if (abs(e.y - downY) > slop) removeCallbacks(lift); return true }
                    val dy = e.rawY - downRawY
                    if (!dragged && abs(dy) > slop) dragged = true
                    if (dragged) dragTo(this, dy, e.rawY)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(lift)
                    if (!lifted) { if (abs(e.y - downY) <= slop) host.select(index) }
                    else if (dragged) dropAt(this)
                    else { settle(this); host.menu(index, this) }
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(lift); if (lifted) { if (dragged) dropAt(this) else settle(this) } }
            }
            return true
        }
    }

    // ---- reordering ----

    private val pitch get() = (52 + 7) * d
    private var targetRow = -1

    private fun dragTo(t: Tile, dy: Float, rawY: Float) {
        t.translationY = dy
        val from = column.indexOfChild(t)
        targetRow = (from + (dy / pitch).roundToInt()).coerceIn(0, column.childCount - 1)
        for (i in 0 until column.childCount) {
            val v = column.getChildAt(i); if (v === t) continue
            val shift = when {
                targetRow > from && i in (from + 1)..targetRow -> -pitch
                targetRow < from && i in targetRow until from -> pitch
                else -> 0f
            }
            if (v.translationY != shift) v.animate().translationY(shift).setDuration(120).start()
        }
        // Auto-scroll when the finger nears the column's ends.
        val loc = IntArray(2); scroll.getLocationOnScreen(loc)
        val edge = 40 * d
        if (rawY < loc[1] + edge) scroll.scrollBy(0, -(10 * d).toInt())
        else if (rawY > loc[1] + scroll.height - edge) scroll.scrollBy(0, (10 * d).toInt())
    }

    private fun dropAt(t: Tile) {
        val n = column.childCount
        val fromRow = column.indexOfChild(t)
        val toRow = if (targetRow < 0) fromRow else targetRow
        for (i in 0 until n) { val v = column.getChildAt(i); v.animate().cancel(); v.translationY = 0f }
        settle(t)
        targetRow = -1
        if (toRow != fromRow) host.reorder(n - 1 - fromRow, n - 1 - toRow)     // rows are newest-first
    }

    private fun settle(t: Tile) {
        t.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(120).start()
        t.elevation = 0f
    }

    private val checker = Paint().apply {
        val s = (5 * d).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); val p = Paint()
        p.color = 0xFF2C2E33.toInt(); cv.drawRect(0f, 0f, s * 2f, s * 2f, p)
        p.color = 0xFF25272B.toInt(); cv.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); cv.drawRect(s.toFloat(), s.toFloat(), s * 2f, s * 2f, p)
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // ---- cut-out thumbnails, cached by shape ----

    private inner class Thumbs {
        private var src: Bitmap? = null
        private var imgW = 1; private var imgH = 1
        private val cache = HashMap<String, Bitmap>()
        private val size = (52 * d * 1.5f).toInt().coerceAtMost(160)

        fun setSource(full: Bitmap) {
            imgW = full.width; imgH = full.height
            val k = 1024f / maxOf(full.width, full.height)
            src = if (k < 1f) Bitmap.createScaledBitmap(full, (full.width * k).toInt(), (full.height * k).toInt(), true) else full
            cache.clear()
        }

        private fun key(b: EditBubble) = "${b.x},${b.y}|${b.curv}|" + b.pts.joinToString(";") { "${it[0]},${it[1]}" }

        fun retain(bs: List<EditBubble>) {
            val keep = bs.map { key(it) }.toHashSet()
            cache.keys.retainAll(keep)
        }

        fun get(b: EditBubble): Bitmap? {
            val s = src ?: return null
            val k = key(b)
            cache[k]?.let { return it }
            // Outline in image px (Y down), framed in a square around its bounds with a small margin.
            val o = b.shape(imgW, imgH).outline(16)
            val cx = b.x * imgW; val cy = imgH - b.y * imgH
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until o.size / 2) {
                val x = cx + o[2 * i]; val y = cy - o[2 * i + 1]
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
            }
            val side = maxOf(x1 - x0, y1 - y0, 1f) * 1.12f
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            val sc = size / side
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            cv.translate(size / 2f, size / 2f); cv.scale(sc, sc); cv.translate(-mx, -my)
            val path = Path()
            for (i in 0 until o.size / 2) { val x = cx + o[2 * i]; val y = cy - o[2 * i + 1]; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            path.close()
            cv.clipPath(path)
            val k2 = s.width.toFloat() / imgW
            cv.drawBitmap(s, Rect(0, 0, s.width, s.height), RectF(0f, 0f, s.width / k2, s.height / k2), Paint(Paint.FILTER_BITMAP_FLAG))
            cache[k] = out
            return out
        }
    }
}
