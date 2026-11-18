package com.levkar.wobble.render

import kotlin.math.max
import kotlin.math.min

/**
 * Image ↔ screen mapping with zoom and pan on top of fit (or fill).
 * Image coordinates: pixels, Y up. Screen coordinates: pixels, Y down (Android views).
 */
class ViewXform {
    var imgW = 1; var imgH = 1
    var viewW = 1; var viewH = 1
    var fill = false
    var zoom = 1f
    var panX = 0f; var panY = 0f      // screen px, added after centering

    val base get() = if (fill) max(viewW.toFloat() / imgW, viewH.toFloat() / imgH) else min(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
    val scale get() = base * zoom
    val left get() = (viewW - imgW * scale) / 2f + panX
    val top get() = (viewH - imgH * scale) / 2f + panY
    /** Bottom edge of the image measured from the bottom of the view (GL convention). */
    val bottomGl get() = viewH - top - imgH * scale

    fun toScreenX(x: Float) = left + x * scale
    fun toScreenY(y: Float) = top + (imgH - y) * scale
    fun toImageX(sx: Float) = (sx - left) / scale
    fun toImageY(sy: Float) = imgH - (sy - top) / scale

    /** Zooms by [factor] keeping the image point under (sx, sy) in place. */
    fun zoomAbout(factor: Float, sx: Float, sy: Float) {
        val ix = toImageX(sx); val iy = toImageY(sy)
        zoom = (zoom * factor).coerceIn(0.5f, 16f)
        panX += sx - toScreenX(ix)
        panY += sy - toScreenY(iy)
    }

    fun reset() { zoom = 1f; panX = 0f; panY = 0f }

    fun copyFrom(o: ViewXform) {
        imgW = o.imgW; imgH = o.imgH; viewW = o.viewW; viewH = o.viewH
        fill = o.fill; zoom = o.zoom; panX = o.panX; panY = o.panY
    }
}
