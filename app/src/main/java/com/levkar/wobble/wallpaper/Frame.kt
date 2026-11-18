package com.levkar.wobble.wallpaper

import android.content.Context
import com.levkar.wobble.render.ViewXform
import kotlin.math.abs

/**
 * How a piece sits on the wallpaper (Lev's framing screen). Stored beside the wallpaper, never in the .wbl,
 * so files stay original-compatible. Remembered per piece and as the current wallpaper's framing.
 *
 * [zoom] is on top of Fit/Fill (1 = just fits / just covers). [panX]/[panY] are fractions of the screen size,
 * so a framing survives small size differences (preview vs. wallpaper surface). [pages]: wide images slide
 * across home-screen pages instead of using [panX].
 */
data class Frame(
    val fill: Boolean = true,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val surround: Int = BLUR,
    val color: Int = 0xFF000000.toInt(),
    val pages: Boolean = false,
) {
    /** The colour behind the picture (also under the blur). */
    val background get() = when (surround) { GRAPHITE -> 0xFF16171A.toInt(); CUSTOM -> color; else -> 0xFF000000.toInt() }

    /**
     * Sets [xf]'s fill/zoom/pan for this framing; [xf] must already hold the image and view sizes.
     * Pan is clamped: a picture bigger than the screen can't show an edge, a smaller one can't leave it.
     * [pageX] 0..1 = home-screen page position (used when [pages] is on), else ignored.
     */
    fun apply(xf: ViewXform, pageX: Float = 0.5f) {
        xf.fill = fill
        xf.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val s = xf.scale
        val rx = abs(xf.imgW * s - xf.viewW) / 2f; val ry = abs(xf.imgH * s - xf.viewH) / 2f
        val wide = xf.imgW * s > xf.viewW + 1f
        xf.panX = if (pages && wide) (0.5f - pageX.coerceIn(0f, 1f)) * 2f * rx else (panX * xf.viewW).coerceIn(-rx, rx)
        xf.panY = (panY * xf.viewH).coerceIn(-ry, ry)
    }

    fun encode() = "$fill,$zoom,$panX,$panY,$surround,$color,$pages"

    companion object {
        const val BLACK = 0; const val GRAPHITE = 1; const val BLUR = 2; const val CUSTOM = 3
        const val MIN_ZOOM = 1f; const val MAX_ZOOM = 6f

        fun decode(s: String?): Frame? = runCatching {
            val p = s!!.split(',')
            Frame(p[0].toBoolean(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toInt(), p[5].toInt(), p[6].toBoolean())
        }.getOrNull()

        private fun prefs(c: Context) = c.getSharedPreferences("wallpaper_frames", Context.MODE_PRIVATE)
        fun forPiece(c: Context, name: String): Frame? = decode(prefs(c).getString("piece:$name", null))
        fun current(c: Context): Frame? = decode(prefs(c).getString("current", null))

        /**
         * Whether the home-screen launcher has ever reported page swipes to the wallpaper. One UI Home never does,
         * so Pages stays hidden there; switch to a launcher that does and it appears on its own.
         */
        fun pagesSupported(c: Context) = prefs(c).getBoolean("pages_supported", false)
        fun markPagesSupported(c: Context) { if (!pagesSupported(c)) prefs(c).edit().putBoolean("pages_supported", true).apply() }
        fun save(c: Context, name: String?, f: Frame) {
            prefs(c).edit().apply { if (name != null) putString("piece:$name", f.encode()) }.putString("current", f.encode()).apply()
        }
    }
}
