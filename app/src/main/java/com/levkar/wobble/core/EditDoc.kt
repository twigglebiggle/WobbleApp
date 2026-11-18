package com.levkar.wobble.core

/**
 * Editable bubble. Same units as the file: normalized to the image (0..1), Y up;
 * corners relative to the center. Mutable so gestures can update it in place.
 */
class EditBubble(
    var x: Float, var y: Float,
    val pts: MutableList<FloatArray>,          // corners relative to (x, y)
    val curv: MutableList<Float>,              // one per corner
    var wobbliness: Float = 0.5f,
    var extent: Float = 0.5f,
    var height: Float = 1f,
    var locked: Boolean = false,
    var visible: Boolean = true,
    var name: String? = null,
    var area: Boolean = false,
    var curve: FloatArray? = null,     // falloff curve points, kept even while unused
    var useCurve: Boolean = false,
    var ceiling: Float = Falloff.CEIL_OFF,   // absolute motion cap (CEIL_OFF = none); height doubles as the curve's Scale
    var freeze: Boolean = false,             // Freeze zone: calms what moves under it instead of moving
    var hold: Float = 1f,                    // Freeze zone strength 0..1
) {
    fun copy() = EditBubble(x, y, pts.map { it.copyOf() }.toMutableList(), curv.toMutableList(),
        wobbliness, extent, height, locked, visible, name, area, curve?.copyOf(), useCurve, ceiling, freeze, hold)

    fun toData() = BubbleData(x, y, pts.map { it.copyOf() }, curv.toList(), wobbliness, height, extent, name, locked, visible, area,
        curve?.copyOf(), useCurve && curve != null, ceiling, freeze, hold)

    /** Outline/weights in image pixels, corners relative to the center. */
    fun shape(imgW: Int, imgH: Int) = BubbleShape.from(toData(), imgW, imgH)

    companion object {
        fun from(b: BubbleData): EditBubble {
            val n = b.points.size
            val curv = MutableList(n) { i ->
                when {
                    b.curvature.size > 1 -> b.curvature.getOrElse(i) { 0f }
                    b.curvature.size == 1 -> b.curvature[0]
                    else -> 0f
                }
            }
            return EditBubble(b.x, b.y, b.points.map { it.copyOf() }.toMutableList(), curv,
                b.wobbliness, b.extent, b.height, b.locked, b.visible, b.name, b.area, b.curve?.copyOf(), b.useCurve, b.ceiling, b.freeze, b.hold)
        }

        /** A new round bubble (4 fully rounded corners, like the original's "add"), [r] = half-size, normalized. */
        fun circle(x: Float, y: Float, rx: Float, ry: Float) = EditBubble(
            x, y,
            mutableListOf(floatArrayOf(rx, ry), floatArrayOf(-rx, ry), floatArrayOf(-rx, -ry), floatArrayOf(rx, -ry)),
            MutableList(4) { 0f })                     // 0 = round, 1 = sharp
    }
}

class EditDoc(var name: String?, val bubbles: MutableList<EditBubble>,
              var author: String? = null, var authorAi: Boolean = false, var source: String? = null, val created: Long? = null) {
    fun copy() = EditDoc(name, bubbles.map { it.copy() }.toMutableList(), author, authorAi, source, created)
    fun toWbl() = WblDocument(name, bubbles.map { it.toData() }, author, authorAi, source, created)

    companion object {
        fun from(d: WblDocument) = EditDoc(d.name, d.bubbles.map { EditBubble.from(it) }.toMutableList(), d.author, d.authorAi, d.source, d.created)
    }
}
