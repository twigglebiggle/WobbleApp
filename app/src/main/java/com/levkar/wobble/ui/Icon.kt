package com.levkar.wobble.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The app's icon set, drawn in code on a 24-unit grid with round 1.7-unit strokes (no image files, so
 * they stay sharp at any size and take any color). Add an icon by adding a case to [draw].
 */
enum class Icon {
    BACK, NEW, IMPORT, MORE, EDIT, WALLPAPER, RINGS, FIT, ADD, LOCK, UNLOCK, DELETE, POINT, AREA, UNDO,
    PLAY, STOP, DUPLICATE, MIRROR_H, MIRROR_V, RECENTER, PALETTE, FOLDER, RENAME, DIAMOND, CHECK, EYE, REDO, GEAR, EXPORT, SHARE, GUIDES, PAGES, ROTATE, CHEVRON_DOWN, CHEVRON_UP, GIF, CLOSE, RECORD, REPLAY, HEAT, INFO, PICK;

    fun drawable(color: Int, strokeUnits: Float = 1.7f) = IconDrawable(this, color, strokeUnits)
}

class IconDrawable(var icon: Icon, var color: Int, private val sw: Float = 1.7f) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = Path()
    private val r = RectF()
    private var alphaMul = 255

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        c.scale(s, s)
        stroke.color = color; stroke.strokeWidth = sw; stroke.alpha = (android.graphics.Color.alpha(color) * alphaMul) / 255
        fill.color = color; fill.alpha = stroke.alpha
        draw(c, icon)
        c.restore()
    }

    private fun line(c: Canvas, vararg xy: Float) {
        p.reset(); p.moveTo(xy[0], xy[1])
        var i = 2; while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        c.drawPath(p, stroke)
    }
    private fun closed(c: Canvas, paint: Paint, vararg xy: Float) {
        p.reset(); p.moveTo(xy[0], xy[1])
        var i = 2; while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        p.close(); c.drawPath(p, paint)
    }
    private fun rrect(c: Canvas, l: Float, t: Float, rt: Float, bt: Float, rad: Float, paint: Paint = stroke) {
        r.set(l, t, rt, bt); c.drawRoundRect(r, rad, rad, paint)
    }
    private fun ring(c: Canvas, x: Float, y: Float, rad: Float, a: Float = 1f) {
        val o = stroke.alpha; stroke.alpha = (o * a).toInt(); c.drawCircle(x, y, rad, stroke); stroke.alpha = o
    }
    private fun dot(c: Canvas, x: Float, y: Float, rad: Float) = c.drawCircle(x, y, rad, fill)

    private fun draw(c: Canvas, i: Icon) {
        when (i) {
            Icon.BACK -> line(c, 15f, 5f, 8f, 12f, 15f, 19f)
            Icon.NEW -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            Icon.CHEVRON_DOWN -> line(c, 6.5f, 9.5f, 12f, 15f, 17.5f, 9.5f)
            Icon.CHEVRON_UP -> line(c, 6.5f, 14.5f, 12f, 9f, 17.5f, 14.5f)
            Icon.ROTATE -> {                     // a phone in a circle of arrows
                rrect(c, 9f, 7.5f, 15f, 16.5f, 1.2f)
                r.set(3.5f, 3.5f, 20.5f, 20.5f)
                c.drawArc(r, 200f, 115f, false, stroke); c.drawArc(r, 20f, 115f, false, stroke)
                line(c, 16.6f, 2.6f, 18.1f, 5.3f, 15.1f, 5.6f)      // arrowhead, top right
                line(c, 7.4f, 21.4f, 5.9f, 18.7f, 8.9f, 18.4f)      // arrowhead, bottom left
            }
            Icon.GIF -> {                        // a frame with "GIF" in strokes
                rrect(c, 3f, 5f, 21f, 19f, 2.5f)
                p.reset(); p.moveTo(9.3f, 9.6f); p.lineTo(7f, 9.6f); p.quadTo(6.3f, 9.6f, 6.3f, 10.3f); p.lineTo(6.3f, 13.7f)
                p.quadTo(6.3f, 14.4f, 7f, 14.4f); p.lineTo(9.3f, 14.4f); p.lineTo(9.3f, 12.3f); p.lineTo(8.2f, 12.3f); c.drawPath(p, stroke)
                line(c, 12f, 9.6f, 12f, 14.4f)
                line(c, 15f, 14.4f, 15f, 9.6f, 17.8f, 9.6f); line(c, 15f, 12f, 17.2f, 12f)
            }
            Icon.HEAT -> {                       // a flame
                p.reset(); p.moveTo(12f, 3f); p.cubicTo(13f, 6.5f, 17.5f, 8.5f, 17.5f, 13.5f)
                p.cubicTo(17.5f, 17f, 15f, 20.5f, 12f, 20.5f); p.cubicTo(9f, 20.5f, 6.5f, 17f, 6.5f, 14f)
                p.cubicTo(6.5f, 11.5f, 8f, 10f, 9f, 9f); p.cubicTo(9.3f, 11f, 10.3f, 12f, 11.5f, 12.3f)
                p.cubicTo(11f, 9f, 10.8f, 6f, 12f, 3f); p.close(); c.drawPath(p, stroke)
            }
            Icon.INFO -> { ring(c, 12f, 12f, 9f); line(c, 12f, 11f, 12f, 16.5f); dot(c, 12f, 7.6f, 1.2f) }
            Icon.PICK -> {                       // eyedropper
                line(c, 13.5f, 6.5f, 17.5f, 10.5f)
                p.reset(); p.moveTo(15.5f, 8.5f); p.lineTo(6.5f, 17.5f); p.lineTo(5f, 19f); p.lineTo(6.5f, 19f); p.lineTo(15.5f, 10.5f); c.drawPath(p, stroke)
                rrect(c, 15f, 3.5f, 20.5f, 9f, 2.5f)
            }
            Icon.CLOSE -> { line(c, 6.5f, 6.5f, 17.5f, 17.5f); line(c, 17.5f, 6.5f, 6.5f, 17.5f) }
            Icon.RECORD -> { ring(c, 12f, 12f, 7.5f); dot(c, 12f, 12f, 3.6f) }
            Icon.REPLAY -> {                     // one arrow round a circle
                r.set(4.5f, 4.5f, 19.5f, 19.5f); c.drawArc(r, -60f, 300f, false, stroke)
                line(c, 15.2f, 2.9f, 15.8f, 6.1f, 12.6f, 6.8f)
                closed(c, fill, 10.5f, 9f, 10.5f, 15f, 15f, 12f)
            }
            Icon.GUIDES -> { rrect(c, 5f, 3f, 19f, 21f, 2.5f); line(c, 8f, 7.5f, 16f, 7.5f); line(c, 9f, 17f, 15f, 17f) }
            Icon.PAGES -> { rrect(c, 3f, 6f, 8f, 18f, 1f); rrect(c, 9.5f, 5f, 14.5f, 19f, 1f); rrect(c, 16f, 6f, 21f, 18f, 1f) }
            Icon.SHARE -> {
                c.drawCircle(17.5f, 6f, 2.5f, stroke); c.drawCircle(6.5f, 12f, 2.5f, stroke); c.drawCircle(17.5f, 18f, 2.5f, stroke)
                line(c, 8.7f, 10.8f, 15.3f, 7.2f); line(c, 8.7f, 13.2f, 15.3f, 16.8f)
            }
            Icon.EXPORT -> {
                line(c, 12f, 15f, 12f, 4f); line(c, 7.5f, 8.5f, 12f, 4f, 16.5f, 8.5f)
                p.reset(); p.moveTo(5f, 15f); p.lineTo(5f, 18f); p.quadTo(5f, 20f, 7f, 20f); p.lineTo(17f, 20f)
                p.quadTo(19f, 20f, 19f, 18f); p.lineTo(19f, 15f); c.drawPath(p, stroke)
            }
            Icon.IMPORT -> {
                line(c, 12f, 4f, 12f, 15f); line(c, 7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
                p.reset(); p.moveTo(5f, 15f); p.lineTo(5f, 18f); p.quadTo(5f, 20f, 7f, 20f); p.lineTo(17f, 20f)
                p.quadTo(19f, 20f, 19f, 18f); p.lineTo(19f, 15f); c.drawPath(p, stroke)
            }
            Icon.MORE -> { dot(c, 5.5f, 12f, 1.4f); dot(c, 12f, 12f, 1.4f); dot(c, 18.5f, 12f, 1.4f) }
            Icon.EDIT -> { closed(c, stroke, 4f, 20f, 5f, 15.5f, 15.5f, 5f, 19f, 8.5f, 8.5f, 19f); line(c, 13.5f, 7f, 17f, 10.5f) }
            Icon.WALLPAPER -> { rrect(c, 6f, 3f, 18f, 21f, 2.5f); line(c, 8.5f, 16.5f, 11f, 13.5f, 13f, 15.5f, 14.5f, 14f, 16f, 16.5f); ring(c, 10.5f, 8.5f, 1.3f) }
            Icon.RINGS -> { ring(c, 12f, 12f, 2f); ring(c, 12f, 12f, 5.5f, .75f); ring(c, 12f, 12f, 9f, .45f) }
            Icon.FIT -> {
                line(c, 4f, 9f, 4f, 4f, 9f, 4f); line(c, 15f, 4f, 20f, 4f, 20f, 9f)
                line(c, 20f, 15f, 20f, 20f, 15f, 20f); line(c, 9f, 20f, 4f, 20f, 4f, 15f)
                rrect(c, 8f, 8f, 16f, 16f, 1f)
            }
            Icon.ADD -> { r.set(5f, 8f, 17f, 17f); c.drawOval(r, stroke); line(c, 19f, 3f, 19f, 7f); line(c, 17f, 5f, 21f, 5f) }
            Icon.LOCK, Icon.UNLOCK -> {
                rrect(c, 5f, 11f, 19f, 20f, 2f)
                p.reset(); p.moveTo(8f, 11f); p.lineTo(8f, 8f); r.set(8f, 4f, 16f, 12f); p.arcTo(r, 180f, 180f)
                if (i == Icon.LOCK) p.lineTo(16f, 11f)
                c.drawPath(p, stroke)
            }
            Icon.DELETE -> { line(c, 5f, 7f, 19f, 7f); line(c, 10f, 7f, 10f, 5f, 14f, 5f, 14f, 7f); line(c, 7f, 7f, 8f, 20f, 16f, 20f, 17f, 7f) }
            Icon.POINT -> { ring(c, 12f, 12f, 8f, .6f); dot(c, 12f, 12f, 2.3f) }
            Icon.AREA -> {
                p.reset(); p.moveTo(12f, 4f); p.cubicTo(17.5f, 4f, 20f, 8f, 20f, 12f); p.cubicTo(20f, 17f, 16f, 20f, 12f, 20f)
                p.cubicTo(7f, 20f, 4f, 16.5f, 4f, 12f); p.cubicTo(4f, 7.5f, 7f, 4f, 12f, 4f); p.close()
                val o = fill.alpha; fill.alpha = o / 4; c.drawPath(p, fill); fill.alpha = o; c.drawPath(p, stroke)
            }
            Icon.UNDO -> {
                line(c, 9f, 7f, 4.5f, 11.5f, 9f, 16f)
                p.reset(); p.moveTo(5f, 11.5f); p.lineTo(14f, 11.5f); p.quadTo(19.5f, 11.5f, 19.5f, 16f); p.quadTo(19.5f, 20.5f, 14f, 20.5f); p.lineTo(12f, 20.5f)
                c.drawPath(p, stroke)
            }
            Icon.PLAY -> closed(c, fill, 8f, 5.5f, 8f, 18.5f, 18.5f, 12f)
            Icon.STOP -> rrect(c, 7f, 7f, 17f, 17f, 2f, fill)
            Icon.DUPLICATE -> {
                rrect(c, 9f, 9f, 20f, 20f, 2f)
                p.reset(); p.moveTo(15f, 9f); p.lineTo(15f, 5f); p.quadTo(15f, 4f, 14f, 4f); p.lineTo(5f, 4f); p.quadTo(4f, 4f, 4f, 5f)
                p.lineTo(4f, 14f); p.quadTo(4f, 15f, 5f, 15f); p.lineTo(9f, 15f); c.drawPath(p, stroke)
            }
            Icon.MIRROR_H -> { line(c, 12f, 3f, 12f, 5f); line(c, 12f, 8f, 12f, 10f); line(c, 12f, 13f, 12f, 15f); line(c, 12f, 18f, 12f, 21f)
                closed(c, stroke, 9f, 7f, 3.5f, 17f, 9f, 17f); closed(c, fill, 15f, 7f, 20.5f, 17f, 15f, 17f) }
            Icon.MIRROR_V -> { line(c, 3f, 12f, 5f, 12f); line(c, 8f, 12f, 10f, 12f); line(c, 13f, 12f, 15f, 12f); line(c, 18f, 12f, 21f, 12f)
                closed(c, stroke, 7f, 9f, 17f, 3.5f, 17f, 9f); closed(c, fill, 7f, 15f, 17f, 20.5f, 17f, 15f) }
            Icon.RECENTER -> { ring(c, 12f, 12f, 6.5f); line(c, 12f, 2.5f, 12f, 5.5f); line(c, 12f, 18.5f, 12f, 21.5f)
                line(c, 2.5f, 12f, 5.5f, 12f); line(c, 18.5f, 12f, 21.5f, 12f); dot(c, 12f, 12f, 1.8f) }
            Icon.PALETTE -> {
                p.reset(); p.moveTo(12f, 3.5f); p.cubicTo(17f, 3.5f, 20.5f, 7f, 20.5f, 11.5f); p.cubicTo(20.5f, 14.5f, 18.5f, 15.5f, 16.5f, 15.5f)
                p.lineTo(15f, 15.5f); p.cubicTo(13.5f, 15.5f, 13f, 17f, 13.8f, 18.2f); p.cubicTo(14.6f, 19.5f, 13.6f, 20.5f, 12f, 20.5f)
                p.cubicTo(7f, 20.5f, 3.5f, 16.8f, 3.5f, 12f); p.cubicTo(3.5f, 7.2f, 7.2f, 3.5f, 12f, 3.5f); p.close(); c.drawPath(p, stroke)
                dot(c, 8f, 11f, 1.3f); dot(c, 11f, 7.5f, 1.3f); dot(c, 15.5f, 8.5f, 1.3f)
            }
            Icon.FOLDER -> {
                p.reset(); p.moveTo(3f, 7f); p.quadTo(3f, 5f, 5f, 5f); p.lineTo(9.5f, 5f); p.lineTo(11.5f, 7f); p.lineTo(19f, 7f)
                p.quadTo(21f, 7f, 21f, 9f); p.lineTo(21f, 17f); p.quadTo(21f, 19f, 19f, 19f); p.lineTo(5f, 19f); p.quadTo(3f, 19f, 3f, 17f); p.close()
                c.drawPath(p, stroke)
            }
            Icon.RENAME -> { line(c, 5f, 7f, 5f, 5f, 19f, 5f, 19f, 7f); line(c, 12f, 5f, 12f, 19f); line(c, 9f, 19f, 15f, 19f) }
            Icon.DIAMOND -> closed(c, stroke, 12f, 5f, 19f, 12f, 12f, 19f, 5f, 12f)
            Icon.CHECK -> line(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Icon.REDO -> {
                line(c, 15f, 7f, 19.5f, 11.5f, 15f, 16f)
                p.reset(); p.moveTo(19f, 11.5f); p.lineTo(10f, 11.5f); p.quadTo(4.5f, 11.5f, 4.5f, 16f); p.quadTo(4.5f, 20.5f, 10f, 20.5f); p.lineTo(12f, 20.5f)
                c.drawPath(p, stroke)
            }
            Icon.GEAR -> {
                ring(c, 12f, 12f, 3f)
                line(c, 12f, 2.5f, 12f, 5.5f); line(c, 12f, 18.5f, 12f, 21.5f); line(c, 2.5f, 12f, 5.5f, 12f); line(c, 18.5f, 12f, 21.5f, 12f)
                line(c, 5.3f, 5.3f, 7.4f, 7.4f); line(c, 16.6f, 16.6f, 18.7f, 18.7f); line(c, 5.3f, 18.7f, 7.4f, 16.6f); line(c, 16.6f, 7.4f, 18.7f, 5.3f)
                ring(c, 12f, 12f, 6.5f, .55f)
            }
            Icon.EYE -> {
                p.reset(); p.moveTo(2.5f, 12f); p.quadTo(12f, 2.5f, 21.5f, 12f); p.quadTo(12f, 21.5f, 2.5f, 12f); p.close()
                c.drawPath(p, stroke); ring(c, 12f, 12f, 3f)
            }
        }
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
