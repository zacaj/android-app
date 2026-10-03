package com.zacaj.posture.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Generates plausible pocket traces for tests until real recordings exist. */
class SyntheticTrace(seed: Long = 1, private val hz: Int = 25, startMs: Long = 1_700_000_000_000) {
    private val rnd = Random(seed)
    private var t = startMs
    val events = ArrayList<TraceEvent>()

    /** Pocket orientation: standing gravity mostly along +/-y, slightly tilted. */
    var standingGravity = Vec3(1.2f, 9.6f, 1.5f)
    var sittingGravity = Vec3(0.8f, 2.0f, 9.5f)

    fun segment(posture: Posture, durationMs: Long, label: Boolean = true): SyntheticTrace {
        if (label) events += TraceEvent.Label(t, posture)
        val dt = 1000L / hz
        val end = t + durationMs
        val phase = rnd.nextDouble() * 2 * PI
        while (t < end) {
            val s = (t - end + durationMs) / 1000.0
            val accel = when (posture) {
                Posture.WALKING -> {
                    // ~1.8 Hz gait, big swings along the thigh axis plus some sideways sway
                    val w = 2 * PI * 1.8 * s + phase
                    standingGravity + Vec3(
                        (1.5 * sin(w * 0.5)).toFloat(),
                        (3.5 * sin(w)).toFloat(),
                        (2.0 * cos(w)).toFloat(),
                    ) + noise(0.4f)
                }
                Posture.SITTING -> sittingGravity + noise(0.08f)
                else -> standingGravity + noise(0.08f)
            }
            events += TraceEvent.Accel(t, accel)
            events += TraceEvent.Gyro(t + 2, noise(if (posture == Posture.WALKING) 1.5f else 0.02f))
            t += dt
        }
        return this
    }

    private fun noise(sd: Float) = Vec3(gauss(sd), gauss(sd), gauss(sd))
    private fun gauss(sd: Float): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
        val u2 = rnd.nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2) * sd).toFloat()
    }
}
