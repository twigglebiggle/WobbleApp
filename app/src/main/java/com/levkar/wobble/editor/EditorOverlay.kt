package com.levkar.wobble.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.levkar.wobble.core.EditBubble
import com.levkar.wobble.core.EditDoc
import com.levkar.wobble.core.Kernel
import com.levkar.wobble.render.ViewXform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws bubble outlines and handles over the image, and turns touches into edits. No modes:
 * what a gesture starts on decides what it does (see design-spec "Editor interaction").
 */
class EditorOverlay(context: Context, private val xf: ViewXform) : View(context) {

    interface Host {
        val doc: EditDoc
        /** Called before a change, with the state to return to on Undo. */
        fun commit(before: EditDoc)
        fun selectionChanged()
        fun viewChanged()
        /** Minimum bubble width in image px. */
        fun minWidth(): Float
        /** While previewing, canvas touches poke the wobble instead of editing. */
        val previewing: Boolean
        fun previewTouch(e: MotionEvent)
        /** Two-finger tap. */
        fun undoTap()
        /** While the heat map is on: the shape is changing mid-gesture (the map can follow it). */
        fun shapeChanging()
        /** Match mode: the bubble tapped to copy settings from (-1 = tapped nothing → cancel). */
        fun matchPicked(index: Int)
        /** The bin at the bottom: [BIN_OFF], [BIN_SHOWN] (with [label]), [BIN_HOT] (finger over it), [BIN_NO] (can't take this). */
        fun bin(state: Int, label: String)
    }

    lateinit var host: Host
    /** Match… (Lev): the next tap picks a bubble to copy settings from; everything else is ignored. */
    var matching = false
        set(v) { field = v; invalidate() }
    private var mDownX = 0f; private var mDownY = 0f; private var mMulti = false

    /** Heat map on: other bubbles' outlines fade so the colours read. */
    var heatOn = false
        set(v) { field = v; invalidate() }
    var selected = -1
        set(v) { field = v; invalidate(); host.selectionChanged() }

    private val d = resources.displayMetrics.density
    private val handleR get() = when (com.levkar.wobble.settings.Options.HANDLES.get(context)) { 0 -> 5f; 2 -> 9.5f; else -> 7f } * d
    private val showKernel get() = com.levkar.wobble.settings.Options.SHOW_KERNEL.get(context)
    private val dimOthers get() = com.levkar.wobble.settings.Options.DIM_OTHERS.get(context)
    private val hitR = 24 * d
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    // ---- appearance (BubbleStyle, editable in "Bubble appearance…") ----
    private val styles = HashMap<BubbleStyle.Role, BubbleStyle>()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
    private val dash = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    private val tiles = HashMap<Int, android.graphics.Bitmap>()
    private val domes = LinkedHashMap<String, android.graphics.Bitmap>()

    fun reloadStyles() {
        for (r in BubbleStyle.Role.values()) styles[r] = BubbleStyle.load(context, r)
        invalidate()
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF000000.toInt() }
    private val centerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.levkar.wobble.ui.Ui.ACCENT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 12 * d; setShadowLayer(3 * d, 0f, 0f, 0xFF000000.toInt())
        runCatching { typeface = com.levkar.wobble.ui.Ui.mono }
    }
    private val path = Path()
    private val kernelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.levkar.wobble.ui.Ui.accentA(0x30) }
    private val kernelLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = com.levkar.wobble.ui.Ui.accentA(0xCC)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    }
    /** While dragging a Point bubble's center: its kernel in image px (absolute), else null. */
    private var kernel: FloatArray? = null
    private val tmp2 = FloatArray(2)

    // ---- coordinates: bubble data is normalized (0..1, Y up) ----
    private val w get() = xf.imgW.toFloat()
    private val h get() = xf.imgH.toFloat()
    private fun sx(nx: Float) = xf.toScreenX(nx * w)
    private fun sy(ny: Float) = xf.toScreenY(ny * h)
    private fun nx(screenX: Float) = xf.toImageX(screenX) / w
    private fun ny(screenY: Float) = xf.toImageY(screenY) / h

    // ---- drawing ----

    /** 0..1: during preview, how visible the selected bubble's outline is (faded in while a slider is dragged). */
    var peek = 0f

    override fun onDraw(c: Canvas) {
        val bubbles = host.doc.bubbles
        if (styles.isEmpty()) reloadStyles()
        if (host.previewing) {
            val b = bubbles.getOrNull(selected) ?: return
            if (peek <= 0f) return
            val layer = c.saveLayerAlpha(null, (peek * 255).toInt())
            drawBubble(c, b, if (b.freeze) BubbleStyle.Role.FREEZE_SELECTED else BubbleStyle.Role.SELECTED, selected = true)
            c.restoreToCount(layer)
            return
        }
        for ((i, b) in bubbles.withIndex()) if (i != selected) {
            // Hidden (muted) bubbles: drawn faintly, they don't wobble.
            val layer = if (!b.visible) c.saveLayerAlpha(null, 70) else if (heatOn) c.saveLayerAlpha(null, 60)    // let the heat colours read
                        else if (dimOthers && selected >= 0) c.saveLayerAlpha(null, 90) else -1
            drawBubble(c, b, when { b.freeze && b.locked -> BubbleStyle.Role.FREEZE_LOCKED; b.freeze -> BubbleStyle.Role.FREEZE
                                    b.locked -> BubbleStyle.Role.LOCKED; else -> BubbleStyle.Role.NORMAL })
            if (layer >= 0) c.restoreToCount(layer)
        }
        val b = bubbles.getOrNull(selected) ?: return
        val binFade = if (binHot && target == Target.BODY) c.saveLayerAlpha(null, 80) else -1      // about to be deleted
        drawBubble(c, b, when { b.freeze && b.locked -> BubbleStyle.Role.FREEZE_SEL_LOCKED; b.freeze -> BubbleStyle.Role.FREEZE_SELECTED
                                b.locked -> BubbleStyle.Role.SELECTED_LOCKED; else -> BubbleStyle.Role.SELECTED }, selected = true)
        if (binFade >= 0) c.restoreToCount(binFade)
        if (showKernel) kernel?.let { k ->                                   // where the peak may go (Point mode), while dragging it
            path.reset()
            for (i in 0 until k.size / 2) {
                val x = xf.toScreenX(k[2 * i]); val y = xf.toScreenY(k[2 * i + 1])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, kernelFill); c.drawPath(path, kernelLine)
        }
        if (b.locked) { c.drawText("locked", sx(b.x) + 10 * d, sy(b.y) - 10 * d, label); return }
        for ((k, p) in b.pts.withIndex()) {
            val x = sx(b.x + p[0]); val y = sy(b.y + p[1])
            c.drawCircle(x, y, handleR, handleFill); c.drawCircle(x, y, handleR, handleRing)
        }
        c.drawCircle(sx(b.x), sy(b.y), handleR * 0.8f, centerFill)
        c.drawCircle(sx(b.x), sy(b.y), handleR * 0.8f, handleRing)
        if (roundCorner in b.curv.indices) drawCornerChip(c, b.curv[roundCorner])
    }

    // Corner value chip: fixed at the top center while a corner's roundness is dragged, so the finger never hides it.
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB1C1D21.toInt() }
    private val chipLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * d; color = com.levkar.wobble.ui.Ui.ACCENT }
    private val chipName = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.levkar.wobble.ui.Ui.TEXT2; textSize = 10 * d; letterSpacing = 0.08f; runCatching { typeface = com.levkar.wobble.ui.Ui.mono } }
    private val chipNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.levkar.wobble.ui.Ui.ACCENT; textSize = 15 * d; textAlign = Paint.Align.RIGHT; runCatching { typeface = com.levkar.wobble.ui.Ui.monoMedium } }
    private val chipTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.levkar.wobble.ui.Ui.LINE }
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.levkar.wobble.ui.Ui.ACCENT }
    private val chipRect = android.graphics.RectF()

    private fun drawCornerChip(c: Canvas, v: Float) {
        val pad = 12 * d; val gap = 8 * d; val barW = 44 * d
        val nameW = chipName.measureText("CORNER"); val numW = chipNum.measureText("100")
        val w = pad + nameW + gap + numW + gap + barW + pad
        val h = 34 * d; val top = 22 * d; val left = (width - w) / 2f
        chipRect.set(left, top, left + w, top + h)
        c.drawRoundRect(chipRect, 8 * d, 8 * d, chipBg); c.drawRoundRect(chipRect, 8 * d, 8 * d, chipLine)
        val mid = top + h / 2f
        var x = left + pad
        c.drawText("CORNER", x, mid + 3.5f * d, chipName); x += nameW + gap + numW
        c.drawText(Math.round(v * 100).toString(), x, mid + 5.5f * d, chipNum); x += gap
        chipRect.set(x, mid - 2 * d, x + barW, mid + 2 * d); c.drawRoundRect(chipRect, 2 * d, 2 * d, chipTrack)
        chipRect.set(x, mid - 2 * d, x + barW * v.coerceIn(0f, 1f), mid + 2 * d); c.drawRoundRect(chipRect, 2 * d, 2 * d, chipFill)
    }

    private val freezeDash = android.graphics.DashPathEffect(floatArrayOf(9 * d, 6 * d), 0f)

    private fun drawBubble(c: Canvas, b: EditBubble, role: BubbleStyle.Role, selected: Boolean = false) {
        val st = styles.getValue(role)
        // A Freeze zone's Dome shows its hold, which ignores the bubble's Scale (height).
        val shape = (if (b.freeze && b.height != 1f) b.copy().apply { height = 1f } else b).shape(xf.imgW, xf.imgH)
        val o = shape.outline(12)
        path.reset()
        for (i in 0 until o.size / 2) {
            val x = xf.toScreenX(b.x * w + o[2 * i]); val y = xf.toScreenY(b.y * h + o[2 * i + 1])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Fill
        if (st.fillAlpha > 0f) {
            fill.shader = null
            fill.color = 0xFF000000.toInt() or st.fillColor
            fill.alpha = (st.fillAlpha * 255).toInt()
            when (st.pattern) {
                BubbleStyle.NONE -> c.drawPath(path, fill)
                BubbleStyle.DOME -> {
                    // Weight map drawn as an alpha mask in the fill color: bright where the bubble moves most.
                    val bmp = dome(b, shape)
                    val l = xf.toScreenX(b.x * w + shape.minX); val r = xf.toScreenX(b.x * w + shape.maxX)
                    val t = xf.toScreenY(b.y * h + shape.maxY); val btm = xf.toScreenY(b.y * h + shape.minY)
                    c.save(); c.clipPath(path)
                    c.drawBitmap(bmp, null, android.graphics.RectF(l, t, r, btm), fill)
                    c.restore()
                }
                else -> {
                    fill.shader = android.graphics.BitmapShader(tile(st.pattern), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                    c.drawPath(path, fill)
                    fill.shader = null
                }
            }
        }
        // Outline (locked ones dashed, so they read as locked whatever the colors)
        if (st.outlineAlpha > 0f && st.outlineWidthDp > 0f) {
            stroke.color = 0xFF000000.toInt() or st.outlineColor
            stroke.alpha = (st.outlineAlpha * 255).toInt()
            stroke.strokeWidth = st.outlineWidthDp * d
            stroke.pathEffect = when {
                role.freeze -> freezeDash                                      // zones always dashed: never read as wobble bubbles
                role == BubbleStyle.Role.LOCKED || role == BubbleStyle.Role.SELECTED_LOCKED -> dash
                else -> null
            }
            c.drawPath(path, stroke)
        }
    }

    /** White-on-transparent pattern tile; the fill paint's color and alpha tint it. */
    private fun tile(pattern: Int): android.graphics.Bitmap = tiles.getOrPut(pattern) {
        val n = (10 * d).toInt().coerceAtLeast(6)
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ALPHA_8)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 1.3f * d }
        val f = n.toFloat()
        when (pattern) {
            BubbleStyle.HATCH -> { cv.drawLine(0f, f, f, 0f, p); cv.drawLine(-f / 2, f / 2, f / 2, -f / 2, p); cv.drawLine(f / 2, f * 1.5f, f * 1.5f, f / 2, p) }
            BubbleStyle.CROSS -> { cv.drawLine(0f, f, f, 0f, p); cv.drawLine(0f, 0f, f, f, p) }
            BubbleStyle.DOTS -> cv.drawCircle(f / 2, f / 2, 1.4f * d, p)
        }
        bmp
    }

    /** 64×64 alpha map of the bubble's weights over its bounding box (cached per bubble state). */
    private fun dome(b: EditBubble, shape: com.levkar.wobble.core.BubbleShape): android.graphics.Bitmap {
        val key = "${b.area}|${b.extent}|${b.height}|${b.curv}|${b.useCurve}|${b.ceiling}|${b.curve?.contentToString()}|" + b.pts.joinToString { "${it[0]},${it[1]}" } + "|${xf.imgW}x${xf.imgH}"
        domes[key]?.let { return it }
        val n = 64
        val px = ByteArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val x = shape.minX + (shape.maxX - shape.minX) * (i + 0.5f) / n
            val y = shape.maxY - (shape.maxY - shape.minY) * (j + 0.5f) / n      // bitmap row 0 = top
            val wgt = shape.weightIfInside(x, y).coerceIn(0f, 1f)
            px[j * n + i] = (wgt * 255).toInt().toByte()
        }
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        if (domes.size > 200) domes.remove(domes.keys.first())
        domes[key] = bmp
        return bmp
    }

    // ---- hit testing ----

    private fun cornerAt(b: EditBubble, x: Float, y: Float): Int {
        var best = -1; var bestD = hitR
        for ((k, p) in b.pts.withIndex()) {
            val dd = hypot(sx(b.x + p[0]) - x, sy(b.y + p[1]) - y)
            if (dd < bestD) { bestD = dd; best = k }
        }
        return best
    }

    private fun onCenter(b: EditBubble, x: Float, y: Float) = hypot(sx(b.x) - x, sy(b.y) - y) < hitR * 0.8f

    private fun inside(b: EditBubble, x: Float, y: Float): Boolean =
        b.shape(xf.imgW, xf.imgH).contains((nx(x) - b.x) * w, (ny(y) - b.y) * h)

    /** Bubbles under the point, smallest first; locked ones only if [includeLocked]. */
    private fun under(x: Float, y: Float, includeLocked: Boolean): List<Int> =
        host.doc.bubbles.indices
            .filter { (includeLocked || !host.doc.bubbles[it].locked) && inside(host.doc.bubbles[it], x, y) }
            .sortedBy { host.doc.bubbles[it].shape(xf.imgW, xf.imgH).area() }

    /** Index of the edge (corner k → k+1) of the selected bubble the point lies on, or -1. */
    private fun edgeAt(b: EditBubble, x: Float, y: Float): Int {
        val o = b.shape(xf.imgW, xf.imgH).outline(16)
        val m = o.size / 2
        var bestI = -1; var bestD = hitR * 0.6f
        for (i in 0 until m) {
            val dd = hypot(xf.toScreenX(b.x * w + o[2 * i]) - x, xf.toScreenY(b.y * h + o[2 * i + 1]) - y)
            if (dd < bestD) { bestD = dd; bestI = i }
        }
        if (bestI < 0) return -1
        // Outline samples are grouped per corner (16 each): the first half of corner j's curve lies on
        // edge j-1→j, the second half on edge j→j+1.
        val n = b.pts.size
        val j = bestI / 16; val t = bestI % 16
        return if (t < 8) (j + n - 1) % n else j
    }

    // ---- validation: Point mode needs a star-shaped outline; nothing narrower than the minimum ----

    private fun acceptable(candidate: EditBubble, before: EditBubble): Boolean {
        val s = candidate.shape(xf.imgW, xf.imgH)
        val s0 = before.shape(xf.imgW, xf.imgH)
        // Point needs the whole outline visible from the center; Area takes any outline.
        if (!candidate.area && !s.isStarShaped() && s0.isStarShaped()) return false
        val mw = if (candidate.area) 2 * s.depth() else s.minWidth()
        val mw0 = if (before.area) 2 * s0.depth() else s0.minWidth()
        return mw >= host.minWidth() || mw >= mw0 - 0.5f     // already-too-small old bubbles may still be edited, not shrunk
    }

    // ---- gestures ----

    private enum class Target { NONE, CORNER, CENTER, BODY, CANVAS, ROUND, TWO_BUBBLE, TWO_CANVAS }

    private var target = Target.NONE
    private var corner = -1
    private var roundCorner = -1
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var before: EditDoc? = null
    private var startBubble: EditBubble? = null
    private var twoDist = 1f; private var twoAngle = 0f; private var twoMidX = 0f; private var twoMidY = 0f
    // Two-finger tap = undo: both fingers down and up quickly without moving. Nothing changes until the
    // fingers really move (past the slop), so a tap never leaves a tiny zoom or bubble edit behind.
    private var twoArmed = false; private var twoStartDist = 1f; private var twoStartMidX = 0f; private var twoStartMidY = 0f
    private var twoDownTime = 0L; private var twoTapped = false
    private var lastTapX = -1e9f; private var lastTapY = -1e9f; private var lastTapTime = 0L
    private var longPressed = false
    private val longPress = Runnable { onLongPress() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (host.previewing) { host.previewTouch(e); return true }
        if (matching) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { mDownX = e.x; mDownY = e.y; mMulti = false }
                MotionEvent.ACTION_POINTER_DOWN -> mMulti = true
                MotionEvent.ACTION_UP -> if (!mMulti && hypot(e.x - mDownX, e.y - mDownY) < 3 * slop) {
                    // Locked bubbles count too (finished bubbles are usually locked); never the selected one itself.
                    host.matchPicked(under(e.x, e.y, includeLocked = true).firstOrNull { it != selected } ?: -1)
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) twoDown(e)
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2 && (target == Target.TWO_BUBBLE || target == Target.TWO_CANVAS)) twoMove(e) else move(e.x, e.y)
            MotionEvent.ACTION_POINTER_UP -> if (target == Target.TWO_BUBBLE || target == Target.TWO_CANVAS) {
                val tap = !twoArmed && e.eventTime - twoDownTime < TWO_TAP_MS && !twoTapped
                finish()
                if (tap) { twoTapped = true; com.levkar.wobble.ui.Ui.haptic(this, android.view.HapticFeedbackConstants.VIRTUAL_KEY); host.undoTap() }
            }
            MotionEvent.ACTION_UP -> { if (!moved && !longPressed) tap(e.x, e.y, e.eventTime); dropInBin(); finish() }
            MotionEvent.ACTION_CANCEL -> { leaveBin(); finish() }
        }
        return true
    }

    private fun down(x: Float, y: Float) {
        twoTapped = false
        downX = x; downY = y; lastX = x; lastY = y; moved = false; roundCorner = -1; longPressed = false
        before = host.doc.copy()
        val b = host.doc.bubbles.getOrNull(selected)
        target = Target.CANVAS
        if (b != null && !b.locked) {
            corner = cornerAt(b, x, y)
            target = when {
                corner >= 0 -> Target.CORNER
                onCenter(b, x, y) -> Target.CENTER
                inside(b, x, y) -> Target.BODY
                else -> Target.CANVAS
            }
            startBubble = b.copy()
            if (target == Target.CENTER && !b.area) {
                val sh = b.shape(xf.imgW, xf.imgH)
                kernel = if (sh.isStarShaped()) Kernel.of(sh.outline(24))?.also { k ->
                    for (i in 0 until k.size / 2) { k[2 * i] += b.x * w; k[2 * i + 1] += b.y * h }
                } else null
            }
        }
        postDelayed(longPress, longPressMs)
    }

    private fun move(x: Float, y: Float) {
        if (!moved && hypot(x - downX, y - downY) < slop) return
        if (!moved) { moved = true; removeCallbacks(longPress) }
        val b = host.doc.bubbles.getOrNull(selected)
        // The bin (Lev): dragging a point or a bubble shows it at the bottom; the finger decides, not the shape.
        if (b != null && !b.locked && (target == Target.CORNER || target == Target.BODY)) {
            val canTake = target == Target.BODY || b.pts.size > 3 || binRemoved != null
            val over = y >= height - binH
            if (over && canTake && !binHot) enterBin(b) else if (!over && binHot) leaveBin()
            host.bin(when { binHot -> BIN_HOT; over && !canTake -> BIN_NO; else -> BIN_SHOWN }, binLabel(b, canTake))
            if (binHot) { lastX = x; lastY = y; invalidate(); return }        // it waits in the bin
        }
        val dnx = (x - lastX) / (xf.scale * w); val dny = -(y - lastY) / (xf.scale * h)
        when (target) {
            // Corners and the center follow the finger absolutely (no drift). A position that isn't allowed
            // is replaced by the nearest allowed one, so the dot slides along the limit instead of sticking.
            Target.CORNER -> if (b != null) {
                val s0 = startBubble!!
                val tx = s0.pts[corner][0] + (x - downX) / (xf.scale * w)
                val ty = s0.pts[corner][1] - (y - downY) / (xf.scale * h)
                moveCornerToward(b, tx, ty)
            }
            Target.CENTER -> if (b != null) {
                val s0 = startBubble!!
                var tx = s0.x * w + (x - downX) / xf.scale       // image px
                var ty = s0.y * h - (y - downY) / xf.scale
                kernel?.let { Kernel.nearest(it, tx, ty, tmp2); tx = tmp2[0]; ty = tmp2[1] }
                moveCenterToward(b, tx / w, ty / h)
            }
            Target.BODY -> if (b != null) { b.x += dnx; b.y += dny }
            Target.ROUND -> if (b != null) {
                b.curv[roundCorner] = (startBubble!!.curv[roundCorner] - (y - downY) / (150 * d)).coerceIn(0f, 1f)
            }
            Target.CANVAS -> { xf.panX += x - lastX; xf.panY += y - lastY; host.viewChanged() }
            else -> {}
        }
        lastX = x; lastY = y
        invalidate()
        if (heatOn && target != Target.CANVAS) host.shapeChanging()
    }

    /** Moves the peak, keeping the outline in place (corners shift the opposite way). */
    private fun centerCandidate(b: EditBubble, nx: Float, ny: Float): EditBubble {
        val c = b.copy(); val dx = nx - b.x; val dy = ny - b.y
        c.x = nx; c.y = ny
        for (p in c.pts) { p[0] -= dx; p[1] -= dy }
        return c
    }

    private fun apply(b: EditBubble, c: EditBubble) {
        b.x = c.x; b.y = c.y
        for (k in b.pts.indices) { b.pts[k][0] = c.pts[k][0]; b.pts[k][1] = c.pts[k][1] }
        for (k in b.curv.indices) b.curv[k] = c.curv[k]
    }

    /** Goes to (tx, ty) if allowed, else as far toward it as allowed (bisection from the current spot). */
    private fun moveCenterToward(b: EditBubble, tx: Float, ty: Float) {
        val full = centerCandidate(b, tx, ty)
        if (acceptable(full, b)) { apply(b, full); return }
        val x0 = b.x; val y0 = b.y
        var lo = 0f; var hi = 1f; var best: EditBubble? = null
        repeat(7) {
            val t = (lo + hi) / 2
            val c = centerCandidate(b, x0 + (tx - x0) * t, y0 + (ty - y0) * t)
            if (acceptable(c, b)) { lo = t; best = c } else hi = t
        }
        best?.let { apply(b, it) }
    }

    private fun cornerCandidate(b: EditBubble, tx: Float, ty: Float) =
        b.copy().also { it.pts[corner][0] = tx; it.pts[corner][1] = ty }

    /** Full move if allowed; else slide along one axis; else as far toward the target as allowed. */
    private fun moveCornerToward(b: EditBubble, tx: Float, ty: Float) {
        val cx = b.pts[corner][0]; val cy = b.pts[corner][1]
        for ((ax, ay) in listOf(tx to ty, tx to cy, cx to ty)) {
            val c = cornerCandidate(b, ax, ay)
            if (acceptable(c, b)) { apply(b, c); if (ax == tx && ay == ty) return; break }
        }
        val x0 = b.pts[corner][0]; val y0 = b.pts[corner][1]
        var lo = 0f; var hi = 1f; var best: EditBubble? = null
        repeat(7) {
            val t = (lo + hi) / 2
            val c = cornerCandidate(b, x0 + (tx - x0) * t, y0 + (ty - y0) * t)
            if (acceptable(c, b)) { lo = t; best = c } else hi = t
        }
        best?.let { apply(b, it) }
    }

    // ---- the bin ----

    private val binH = 72 * d
    private var binHot = false
    private var binRemoved: FloatArray? = null; private var binRemovedCurv = 0f

    private fun binLabel(b: EditBubble, canTake: Boolean) = when {
        target == Target.BODY -> if (b.freeze) "Delete zone" else "Delete bubble"
        canTake -> "Remove point"
        else -> "A bubble keeps 3 points"
    }

    /** Finger reached the bin: a point is taken out right away (the outline shows the result); a bubble fades. */
    private fun enterBin(b: EditBubble) {
        if (target == Target.CORNER) {
            val s0 = startBubble ?: return
            val cand = s0.copy(); cand.pts.removeAt(corner); cand.curv.removeAt(corner)
            if (!acceptable(cand, s0)) return                   // would fold the shape: not taken
            binRemoved = s0.pts[corner].copyOf(); binRemovedCurv = s0.curv[corner]
            b.pts.removeAt(corner); b.curv.removeAt(corner)      // only this corner was being dragged: the rest = s0
        }
        binHot = true
        com.levkar.wobble.ui.Ui.haptic(this, android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    /** Finger left the bin (or the gesture was cancelled): the point comes back where it started. */
    private fun leaveBin() {
        val b = host.doc.bubbles.getOrNull(selected)
        val pt = binRemoved
        if (b != null && pt != null) { b.pts.add(corner, pt); b.curv.add(corner, binRemovedCurv) }
        binRemoved = null; binHot = false
        invalidate()
    }

    /** Let go over the bin: the point is already out; a bubble is deleted. One undo step (the gesture's). */
    private fun dropInBin() {
        if (binHot && target == Target.BODY && selected in host.doc.bubbles.indices) {
            host.doc.bubbles.removeAt(selected)
            selected = -1
            com.levkar.wobble.ui.Ui.haptic(this)
        }
        binRemoved = null; binHot = false
    }

    private fun twoDown(e: MotionEvent) {
        leaveBin()
        removeCallbacks(longPress)
        moved = true
        kernel = null
        val b = host.doc.bubbles.getOrNull(selected)
        target = if (b != null && !b.locked && (target == Target.BODY || target == Target.CENTER || target == Target.CORNER)) Target.TWO_BUBBLE else Target.TWO_CANVAS
        startBubble = b?.copy()
        twoDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
        twoAngle = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
        twoMidX = (e.getX(0) + e.getX(1)) / 2; twoMidY = (e.getY(0) + e.getY(1)) / 2
        twoArmed = false; twoStartDist = twoDist; twoStartMidX = twoMidX; twoStartMidY = twoMidY; twoDownTime = e.eventTime
    }

    private fun twoMove(e: MotionEvent) {
        val dist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
        val ang = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
        val midX = (e.getX(0) + e.getX(1)) / 2; val midY = (e.getY(0) + e.getY(1)) / 2
        if (!twoArmed) {
            if (abs(dist - twoStartDist) < slop && hypot(midX - twoStartMidX, midY - twoStartMidY) < slop) return
            twoArmed = true
        }
        if (target == Target.TWO_CANVAS) {
            xf.zoomAbout(dist / twoDist, midX, midY)
            xf.panX += midX - twoMidX; xf.panY += midY - twoMidY
            twoDist = dist; twoMidX = midX; twoMidY = midY
            host.viewChanged(); invalidate(); return
        }
        val b = host.doc.bubbles.getOrNull(selected) ?: return
        val s0 = startBubble ?: return
        // Scale + rotate the corners about the bubble's center, in pixel space (so rotation isn't skewed by aspect).
        val k = dist / twoDist
        val rot = -(ang - twoAngle)                    // screen Y is down, image Y is up
        val c = cos(rot); val sn = sin(rot)
        val cand = s0.copy()
        for (i in cand.pts.indices) {
            val px = s0.pts[i][0] * w; val py = s0.pts[i][1] * h
            cand.pts[i][0] = (px * c - py * sn) * k / w
            cand.pts[i][1] = (px * sn + py * c) * k / h
        }
        if (acceptable(cand, b)) for (i in b.pts.indices) { b.pts[i][0] = cand.pts[i][0]; b.pts[i][1] = cand.pts[i][1] }
        invalidate()
    }

    private fun onLongPress() {
        if (moved) return
        longPressed = true                                  // the gesture is used up: lifting the finger does nothing more
        val b = host.doc.bubbles.getOrNull(selected)
        if (target == Target.CORNER && b != null) {
            target = Target.ROUND; roundCorner = corner     // then drag up/down to change roundness
        } else {
            // Long-press elsewhere also reaches locked bubbles (taps pass through them), so they can be unlocked.
            under(downX, downY, includeLocked = true).firstOrNull()?.let { selected = it }
            target = Target.NONE
        }
        com.levkar.wobble.ui.Ui.haptic(this)
        invalidate()
    }

    /** Copy of the selected bubble, offset down-right on screen, unlocked, selected. Returns false if none. */
    fun duplicate(): Boolean {
        val b = host.doc.bubbles.getOrNull(selected) ?: return false
        val c = b.copy()
        c.x += 24 * d / (xf.scale * w); c.y -= 24 * d / (xf.scale * h)
        c.locked = false
        c.name = b.name?.let { "$it copy" }
        host.doc.bubbles.add(c)
        selected = host.doc.bubbles.lastIndex
        return true
    }

    /** Flips the selected bubble in place across its outline's center. Returns false if none or locked. */
    fun mirror(horizontal: Boolean): Boolean {
        val b = host.doc.bubbles.getOrNull(selected) ?: return false
        if (b.locked) return false
        val o = b.shape(xf.imgW, xf.imgH).outline(16)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val off = if (horizontal) 0 else 1
        for (i in 0 until o.size / 2) { lo = minOf(lo, o[2 * i + off]); hi = maxOf(hi, o[2 * i + off]) }
        val mid = (lo + hi) / 2                                    // px, relative to the peak
        if (horizontal) b.x += 2 * mid / w else b.y += 2 * mid / h // peak mirrored across the outline's middle
        for (p in b.pts) p[off] = -p[off]
        b.pts.reverse(); b.curv.reverse()                          // keep the corners' winding order
        invalidate()
        return true
    }

    private var lastCenterTap = 0L
    private var lastCornerTap = 0L
    private var lastCornerTapIndex = -1

    /**
     * Puts the selected bubble's center dot (the motion peak) back at the middle of its outline,
     * leaving the outline where it is. Returns false if nothing changed or a Point bubble would break.
     */
    fun recenter(): Boolean {
        val b = host.doc.bubbles.getOrNull(selected) ?: return false
        if (b.locked) return false
        val o = b.shape(xf.imgW, xf.imgH).outline(16)
        val m = o.size / 2
        var a = 0f; var cx = 0f; var cy = 0f
        for (i in 0 until m) {                                   // polygon centroid
            val i1 = (i + 1) % m
            val cr = o[2 * i] * o[2 * i1 + 1] - o[2 * i1] * o[2 * i + 1]
            a += cr; cx += (o[2 * i] + o[2 * i1]) * cr; cy += (o[2 * i + 1] + o[2 * i1 + 1]) * cr
        }
        if (kotlin.math.abs(a) < 1e-6f) return false
        cx /= 3 * a; cy /= 3 * a                                 // pixels, relative to the current center
        val dnx = cx / w; val dny = cy / h
        if (kotlin.math.abs(dnx) < 1e-6f && kotlin.math.abs(dny) < 1e-6f) return false
        val cand = b.copy(); cand.x += dnx; cand.y += dny
        for (p in cand.pts) { p[0] -= dnx; p[1] -= dny }
        if (!b.area && !cand.shape(xf.imgW, xf.imgH).isStarShaped()) return false
        b.x = cand.x; b.y = cand.y
        for (k in b.pts.indices) { b.pts[k][0] = cand.pts[k][0]; b.pts[k][1] = cand.pts[k][1] }
        invalidate()
        return true
    }

    private fun tap(x: Float, y: Float, time: Long) {
        val b = host.doc.bubbles.getOrNull(selected)
        // Double-tap the center dot: recenter the peak.
        if (target == Target.CENTER) {
            if (time - lastCenterTap < DOUBLE_TAP_MS) { recenter(); lastCenterTap = 0L } else lastCenterTap = time
            return
        }
        // Tap on the selected bubble's edge: insert a corner there.
        if (b != null && !b.locked && target != Target.CORNER && target != Target.CENTER) {
            val edge = edgeAt(b, x, y)
            if (edge >= 0) {
                val n = b.pts.size
                val cand = b.copy()
                cand.pts.add(edge + 1, floatArrayOf(nx(x) - b.x, ny(y) - b.y))
                cand.curv.add(edge + 1, (b.curv[edge] + b.curv[(edge + 1) % n]) / 2f)
                if (acceptable(cand, b)) {
                    b.pts.add(edge + 1, cand.pts[edge + 1]); b.curv.add(edge + 1, cand.curv[edge + 1])
                    invalidate(); return
                }
            }
        }
        // Double-tap a corner: sharp ↔ round (any in-between value goes to sharp first).
        if (target == Target.CORNER && b != null) {
            if (corner == lastCornerTapIndex && time - lastCornerTap < DOUBLE_TAP_MS) {
                val c = b.copy()
                c.curv[corner] = if (b.curv[corner] >= 0.999f) 0f else 1f
                if (acceptable(c, b)) { b.curv[corner] = c.curv[corner]; invalidate() }
                lastCornerTap = 0L
            } else { lastCornerTap = time; lastCornerTapIndex = corner }
            return
        }
        if (target == Target.CORNER || target == Target.CENTER) return
        // Selection: smallest unlocked bubble under the finger. Tapping the selected bubble keeps it;
        // a quick double-tap goes one bubble deeper (for small bubbles hidden under big ones).
        // Unlocked bubbles win; where only locked ones are under the finger, those are selectable too
        // (still protected: a locked bubble can't be moved or edited until unlocked).
        val hits = under(x, y, includeLocked = false).ifEmpty { under(x, y, includeLocked = true) }
        val doubleTap = time - lastTapTime < DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) < hitR
        lastTapX = x; lastTapY = y; lastTapTime = time
        selected = when {
            hits.isEmpty() -> -1
            doubleTap && selected in hits -> hits[(hits.indexOf(selected) + 1) % hits.size]
            selected in hits -> selected
            else -> hits.first()
        }
    }

    companion object {
        private const val DOUBLE_TAP_MS = 300L; private const val TWO_TAP_MS = 300L
        const val BIN_OFF = 0; const val BIN_SHOWN = 1; const val BIN_HOT = 2; const val BIN_NO = 3
    }

    private fun finish() {
        removeCallbacks(longPress)
        host.bin(BIN_OFF, "")
        val b0 = before
        before = null
        if (b0 != null && changed(b0, host.doc)) host.commit(b0)
        target = Target.NONE; roundCorner = -1; kernel = null
        invalidate()
    }

    private fun changed(a: EditDoc, b: EditDoc): Boolean {
        if (a.bubbles.size != b.bubbles.size) return true
        for ((x, y) in a.bubbles.zip(b.bubbles)) {
            if (x.x != y.x || x.y != y.y || x.pts.size != y.pts.size || x.curv != y.curv) return true
            for (k in x.pts.indices) if (!x.pts[k].contentEquals(y.pts[k])) return true
        }
        return false
    }

}
