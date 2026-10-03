package com.zacaj.posture.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Instantaneous (un-debounced) classification from a sliding accelerometer window. */
class PostureClassifier(private val config: DetectorConfig) {
    private val times = ArrayDeque<Long>()
    private val samples = ArrayDeque<Vec3>()

    /** Mean accel over the window (≈ gravity when not moving much). */
    var gravity: Vec3 = Vec3.ZERO
        private set
    var magnitudeStd: Float = 0f
        private set
    /** Sign-agnostic angle (deg) between gravity and the reference axis. */
    var tiltDeg: Float = Float.NaN
        private set

    /** Returns null while the window is filling or when in the sit/stand dead band. */
    fun add(tMs: Long, accel: Vec3): Posture? {
        times.addLast(tMs)
        samples.addLast(accel)
        while (times.first() < tMs - config.windowMs) {
            times.removeFirst()
            samples.removeFirst()
        }
        if (tMs - times.first() < config.windowMs * 3 / 4) return null

        var sum = Vec3.ZERO
        var magSum = 0.0
        var magSq = 0.0
        for (s in samples) {
            sum += s
            val m = s.norm().toDouble()
            magSum += m
            magSq += m * m
        }
        val n = samples.size
        gravity = sum * (1f / n)
        val mean = magSum / n
        magnitudeStd = sqrt((magSq / n - mean * mean).coerceAtLeast(0.0)).toFloat()

        val ref = config.referenceAxis
        val denom = gravity.norm() * ref.norm()
        tiltDeg = if (denom == 0f) Float.NaN
        else Math.toDegrees(acos((abs(gravity.dot(ref)) / denom).coerceIn(0f, 1f).toDouble())).toFloat()

        return when {
            magnitudeStd >= config.walkStdThreshold -> Posture.WALKING
            tiltDeg.isNaN() -> null
            tiltDeg <= config.standMaxDeg -> Posture.STANDING
            tiltDeg >= config.sitMinDeg -> Posture.SITTING
            else -> null
        }
    }
}
