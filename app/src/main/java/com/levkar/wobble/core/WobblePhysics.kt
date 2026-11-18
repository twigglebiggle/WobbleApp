package com.levkar.wobble.core

import kotlin.math.sqrt

/**
 * The original AndWobble physics, ported constant-for-constant.
 * Each bubble is ONE 2D damped oscillator; its offset is spread over the image by the bubble's weights.
 * Units: image pixels, Y up. Fixed timestep [DT].
 */
class Body(val cx: Float, val cy: Float, val shape: BubbleShape, wobbliness: Float) {
    // Parameters derived from wobbliness w (original names in comments).
    private val w = wobbliness
    val amp = 0.1f + 0.3f * w          // i: how far it may travel before hitting the wall
    val fri = 0.6f * (1f - w)          // j: damping and bounce
    val sti = 1f - w                   // k: spring stiffness
    val fri2 = fri * fri               // l
    val sti2 = sti * sti               // m
    val acc2 = w * w                   // n: input sensitivity

    var offX = 0f; var offY = 0f       // f, g
    var velX = 0f; var velY = 0f       // e
    var impX = 0f; var impY = 0f       // d (impulse accumulator, consumed each step)
}

class Finger(val ox: Float, val oy: Float, val radius: Float, val downTimeMs: Long) {
    var targetX = 0f; var targetY = 0f     // n: drag vector / radius
    var offX = 0f; var offY = 0f           // c, d
    var velX = 0f; var velY = 0f           // o
    var active = true                      // q
    var grabbed: List<Body>? = null        // bubbles whose center was under the finger
}

/** A Freeze zone: no oscillator; the mesh multiplies movement under it by (1 − hold × weight). */
class FreezeZone(val cx: Float, val cy: Float, val shape: BubbleShape, val hold: Float)

class WobblePhysics(val bodies: List<Body>, val freezes: List<FreezeZone> = emptyList()) {

    val fingers = ArrayList<Finger>()

    /**
     * Motion smaller than this (image px) is invisible, so a bubble below it snaps to exact rest
     * (offset and velocity zeroed). The renderer sets it to ~¼ screen pixel for the current zoom.
     */
    var restEpsilon = 0.05f
    /** Settings → Touch strength: scales drags and tap kicks (1 = the original). */
    var touchGain = 1f

    /** Tilt impulse from the averaged accelerometer change (raw sensor x/y). */
    fun tilt(ax: Float, ay: Float) {
        for (b in bodies) kick(b, ax, ay)
    }

    /** Quick tap: pushes every bubble away from the tap point. */
    fun tap(x: Float, y: Float) {
        for (b in bodies) {
            var dx = x - b.cx; var dy = y - b.cy
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-4f) continue
            dx /= len; dy /= len
            val r = b.shape.radius(dx, dy)
            val mag = (((len / r) - 1f).coerceIn(0f, 3f) + 4f) * touchGain
            kick(b, dx * mag, dy * mag)
        }
    }

    private fun kick(b: Body, fx: Float, fy: Float) {
        val s = b.shape.radius(fx, fy) * (0.1f + 3f * b.acc2)
        var ix = fx * s; var iy = fy * s
        if (ix * b.velX + iy * b.velY < -1f) { ix = -ix; iy = -iy }   // original: reinforce instead of cancel
        b.velX += ix; b.velY += iy
    }

    fun fingerDown(x: Float, y: Float, radius: Float, timeMs: Long): Finger {
        val f = Finger(x, y, radius, timeMs)
        fingers += f
        return f
    }

    fun fingerMove(f: Finger, x: Float, y: Float) {
        f.targetX = (x - f.ox) / f.radius * touchGain
        f.targetY = (y - f.oy) / f.radius * touchGain
    }

    fun fingerUp(f: Finger, x: Float, y: Float, timeMs: Long) {
        f.targetX = 0f; f.targetY = 0f
        f.active = false
        if (timeMs - f.downTimeMs < TAP_MS) tap(x, y)
    }

    private val tmpN = FloatArray(2)

    /** One fixed step. Returns true while anything is still moving. */
    fun step(dt: Float = DT): Boolean {
        var moving = false
        val eps2 = restEpsilon * restEpsilon
        for (b in bodies) {
            val pushed = b.impX != 0f || b.impY != 0f
            val damp = 1f - (0.1f + 8f * b.fri2) * dt
            b.velX *= damp; b.velY *= damp
            val gain = 15f * dt * (0.1f + 3f * b.acc2)
            b.velX += b.impX * gain; b.velY += b.impY * gain
            b.impX = 0f; b.impY = 0f
            val spring = -dt * (1f + 300f * b.sti2)
            b.velX += b.offX * spring; b.velY += b.offY * spring

            val limit = 0.05f + 0.45f * b.amp
            var nx = b.offX + b.velX * dt
            var ny = b.offY + b.velY * dt
            val r = b.shape.radius(nx, ny)
            val rel2 = (nx * nx + ny * ny) / (r * r)
            if (rel2 > limit * limit) {
                val s = sqrt(rel2)
                val k = limit / s
                nx *= k; ny *= k
                val restitution = 0.5f + 0.5f * b.fri
                b.shape.normal(nx / s, ny / s, tmpN)
                val d = tmpN[0] * b.velX + tmpN[1] * b.velY
                if (d > 0f) {
                    b.velX -= tmpN[0] * d * (2f - restitution)
                    b.velY -= tmpN[1] * d * (2f - restitution)
                }
            }
            b.offX = nx; b.offY = ny
            // Snap to exact rest once both the displacement and the per-step travel are invisible.
            // (Checking velocity too keeps a bubble alive while it swings fast through its center.)
            if (!pushed && nx * nx + ny * ny < eps2 && (b.velX * b.velX + b.velY * b.velY) * dt * dt < eps2) {
                b.offX = 0f; b.offY = 0f; b.velX = 0f; b.velY = 0f
            } else moving = true
        }

        val it = fingers.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val damp = 1f - TOUCH_DAMP * dt
            f.velX *= damp; f.velY *= damp
            f.velX += f.targetX * dt * TOUCH_PULL; f.velY += f.targetY * dt * TOUCH_PULL
            f.velX -= f.offX * dt * TOUCH_SPRING; f.velY -= f.offY * dt * TOUCH_SPRING
            f.offX += f.velX * dt; f.offY += f.velY * dt
            if (f.active) {
                val grabbed = f.grabbed ?: bodies.filter {
                    val dx = it.cx - f.ox; val dy = it.cy - f.oy
                    dx * dx + dy * dy < f.radius * f.radius
                }.also { f.grabbed = it }
                for (b in grabbed) {
                    val dx = b.cx - f.ox; val dy = b.cy - f.oy
                    val e = (1f - sqrt(dx * dx + dy * dy) / f.radius) * TOUCH_GRAB * f.radius
                    b.impX += f.targetX * e - b.offX
                    b.impY += f.targetY * e - b.offY
                }
                moving = true
            } else if (f.offX * f.offX + f.offY * f.offY < eps2 && (f.velX * f.velX + f.velY * f.velY) * dt * dt < eps2) {
                it.remove()
                continue
            } else moving = true
        }
        return moving
    }

    companion object {
        const val DT = 0.017f            // 17 ms fixed step
        const val STEP_MS = 17L
        const val MAX_STEPS_PER_FRAME = 10
        /** Rest threshold in screen pixels; converted to image pixels by the renderer. */
        const val REST_SCREEN_PX = 0.25f
        const val TAP_MS = 350L
        const val TOUCH_GRAB = 7f
        const val TOUCH_SPRING = 300f
        const val TOUCH_PULL = 1200f
        const val TOUCH_DAMP = 5f
        const val FINGER_RADIUS_DP = 72f

        fun from(doc: WblDocument, imgW: Int, imgH: Int): WobblePhysics =
            doc.bubbles.filter { it.visible && it.points.size >= 3 }.let { live ->
                WobblePhysics(
                    live.filter { !it.freeze }.map { Body(it.x * imgW, it.y * imgH, BubbleShape.from(it, imgW, imgH), it.wobbliness) },
                    live.filter { it.freeze }.map { FreezeZone(it.x * imgW, it.y * imgH, BubbleShape.from(it.withHeight(1f), imgW, imgH), it.hold) })
            }
    }
}
