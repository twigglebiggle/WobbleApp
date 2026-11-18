package com.levkar.wobble.core

/**
 * The original's accelerometer handling: it reacts to CHANGES in acceleration, not to gravity.
 * Each sample's change from the previous one is kept only if it is big enough (|Δ|² > 0.5),
 * and the kept changes are averaged once per frame into one tilt impulse.
 * Thread-safe: samples arrive on the sensor thread, [drain] runs on the render thread.
 */
class TiltInput {
    private var lastX = 0f; private var lastY = 0f
    private var sumX = 0f; private var sumY = 0f; private var count = 0

    /** Returns true if this sample produced an impulse (so an idle renderer should wake up). */
    @Synchronized fun onSample(x: Float, y: Float): Boolean {
        if (lastX == 0f && lastY == 0f) { lastX = x; lastY = y }
        val dx = x - lastX; val dy = y - lastY
        val kept = dx * dx + dy * dy > 0.5f
        if (kept) { sumX += dx; sumY += dy; count++ }
        lastX = x; lastY = y
        return kept
    }

    /** Forget the last sample (after the screen rotates, so the axis swap isn't read as a jolt). */
    @Synchronized fun reset() { lastX = 0f; lastY = 0f; sumX = 0f; sumY = 0f; count = 0 }

    /** Returns the averaged impulse for this frame, or null if nothing happened. */
    @Synchronized fun drain(out: FloatArray): Boolean {
        if (count == 0) return false
        out[0] = sumX / count; out[1] = sumY / count
        sumX = 0f; sumY = 0f; count = 0
        return true
    }
}
