package com.levkar.wobble.render

import android.opengl.GLES30
import com.levkar.wobble.core.WobbleMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tap feedback: a thin soft-white ring that expands from the tap and fades in ~0.45 s.
 * [MODE_OVERLAY]: drawn flat on top of the image.
 * [MODE_ON_IMAGE]: drawn onto the image, so it rides the wobble (stretches and jiggles with the bubbles under it).
 * Everything here runs on the GL thread. Positions are image pixels, Y up.
 */
class TapRings(private val density: Float) {

    /** Look of the rings (Settings → Tap rings). Defaults = the original feel. */
    data class Style(val color: Int = 0xFFFFFFFF.toInt(), val widthDp: Float = WIDTH_DP, val endRadiusDp: Float = END_RADIUS_DP, val durationMs: Long = DURATION_MS) {
        companion object {
            fun from(c: android.content.Context): Style {
                val o = com.levkar.wobble.settings.Options
                return Style(o.RING_COLOR.get(c), WIDTH_DP * o.RING_WIDTH.get(c) / 35f, END_RADIUS_DP * o.RING_SIZE.get(c) / 50f,
                    (DURATION_MS * o.RING_FADE.get(c) / 40f).toLong().coerceAtLeast(60L))
            }
        }
    }
    @Volatile var style = Style()

    private class Ring(val x: Float, val y: Float, val start: Long)

    private val rings = ArrayList<Ring>()
    private var program = 0
    private var uScale = 0; private var uOffset = 0; private var uColor = 0
    private var vbo = 0
    private val verts = FloatArray((SEGMENTS + 1) * 4)
    private val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val tmp = FloatArray(2)

    val active get() = rings.isNotEmpty()

    fun spawn(x: Float, y: Float, now: Long) { rings += Ring(x, y, now) }

    fun clear() = rings.clear()

    /** Call from onSurfaceCreated: the old GL objects are gone. */
    fun onSurfaceCreated() {
        program = 0; vbo = 0
    }

    /**
     * Draws all live rings and drops finished ones. [fitScale] converts screen px → image px.
     * For [MODE_ON_IMAGE], [mesh] + [deformed] give the current wobble displacement.
     */
    fun draw(mode: Int, now: Long, scaleX: Float, scaleY: Float, offX: Float, offY: Float, fitScale: Float,
             mesh: WobbleMesh?, deformed: FloatArray?) {
        val st = style
        rings.removeAll { now - it.start >= st.durationMs }
        if (rings.isEmpty() || mode == MODE_OFF) return
        if (program == 0) init()

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, offX, offY)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        val dpToImg = density / fitScale
        for (ring in rings) {
            val t = (now - ring.start).toFloat() / st.durationMs
            val ease = 1f - (1f - t).pow(3)                       // fast out, slow settle
            val radius = (START_RADIUS_DP + (st.endRadiusDp - START_RADIUS_DP) * ease) * dpToImg
            val half = st.widthDp * (1f - 0.5f * t) * 0.5f * dpToImg
            val alpha = MAX_ALPHA * (1f - t).pow(1.5f)
            val ride = mode == MODE_ON_IMAGE && mesh != null && deformed != null
            for (k in 0..SEGMENTS) {
                val a = (2.0 * PI * k / SEGMENTS)
                val cx = cos(a).toFloat(); val cy = sin(a).toFloat()
                var ix = ring.x + cx * (radius - half); var iy = ring.y + cy * (radius - half)
                var ox = ring.x + cx * (radius + half); var oy = ring.y + cy * (radius + half)
                if (ride) {
                    mesh!!.displacementAt(deformed!!, ix, iy, tmp); ix += tmp[0]; iy += tmp[1]
                    mesh.displacementAt(deformed, ox, oy, tmp); ox += tmp[0]; oy += tmp[1]
                }
                val o = k * 4
                verts[o] = ix; verts[o + 1] = iy; verts[o + 2] = ox; verts[o + 3] = oy
            }
            buf.clear(); buf.put(verts); buf.flip()
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STREAM_DRAW)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glUniform4f(uColor, android.graphics.Color.red(st.color) / 255f, android.graphics.Color.green(st.color) / 255f, android.graphics.Color.blue(st.color) / 255f, alpha)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, (SEGMENTS + 1) * 2)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun init() {
        program = WobbleRenderer.buildProgram(VS, FS)
        uScale = GLES30.glGetUniformLocation(program, "u_scale")
        uOffset = GLES30.glGetUniformLocation(program, "u_offset")
        uColor = GLES30.glGetUniformLocation(program, "u_color")
        val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
    }

    companion object {
        const val MODE_OFF = 0
        const val MODE_OVERLAY = 1
        const val MODE_ON_IMAGE = 2

        const val DURATION_MS = 450L
        private const val SEGMENTS = 64
        private const val START_RADIUS_DP = 10f
        const val END_RADIUS_DP = 56f
        const val WIDTH_DP = 2.5f
        private const val MAX_ALPHA = 0.55f

        private const val VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform vec2 u_scale;
uniform vec2 u_offset;
void main() { gl_Position = vec4(a_pos * u_scale + u_offset, 0.0, 1.0); }"""
        private const val FS = """#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 color;
void main() { color = u_color; }"""
    }
}
