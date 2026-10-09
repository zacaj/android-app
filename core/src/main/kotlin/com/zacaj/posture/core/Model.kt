package com.zacaj.posture.core

import kotlin.math.sqrt

enum class Posture { UNKNOWN, SITTING, STANDING, WALKING }

data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun norm(): Float = sqrt(x * x + y * y + z * z)
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val Y = Vec3(0f, 1f, 0f)
    }
}

/** Output of the detector. Timestamps are epoch millis. */
sealed interface DetectorEvent {
    val tMs: Long

    /** [since] is when the new state is judged to have started (before debounce). */
    data class StateChanged(override val tMs: Long, val from: Posture, val to: Posture, val since: Long) : DetectorEvent

    /** [durationMs] is the accumulated load (see [LoadTracker]), not time in the current stretch. */
    data class TooLong(override val tMs: Long, val state: Posture, val durationMs: Long) : DetectorEvent

    /** A posture's load drained back to zero after a meaningful build-up: a real break. */
    data class LoadCleared(override val tMs: Long, val posture: Posture) : DetectorEvent

    /** Debounced pocket change; detection is paused while [inPocket] is false. */
    data class PocketChanged(override val tMs: Long, val inPocket: Boolean) : DetectorEvent
}

/** Sign-agnostic angle in degrees between two vectors; NaN if either is zero. */
fun axisAngleDeg(a: Vec3, b: Vec3): Float {
    val denom = a.norm() * b.norm()
    if (denom == 0f) return Float.NaN
    val c = (kotlin.math.abs(a.dot(b)) / denom).coerceIn(0f, 1f)
    return Math.toDegrees(kotlin.math.acos(c.toDouble())).toFloat()
}

/** Result of averaging a still calibration sample. */
data class CalibrationSample(val gravity: Vec3, val magnitudeStd: Float, val count: Int) {
    /** Usable if enough samples, roughly 1g, and not moving. */
    val ok get() = count >= 20 && gravity.norm() in 7f..12.5f && magnitudeStd < 0.6f

    companion object {
        fun of(samples: List<Vec3>): CalibrationSample {
            if (samples.isEmpty()) return CalibrationSample(Vec3.ZERO, Float.NaN, 0)
            var sum = Vec3.ZERO
            for (s in samples) sum += s
            val mags = samples.map { it.norm() }
            val mean = mags.average()
            val std = kotlin.math.sqrt(mags.sumOf { (it - mean) * (it - mean) } / mags.size).toFloat()
            return CalibrationSample(sum * (1f / samples.size), std, samples.size)
        }
    }
}

data class DetectorConfig(
    /** Sliding window used for gravity estimate and motion energy. */
    val windowMs: Long = 2000,
    /** Std-dev of accel magnitude (m/s^2) above which we call it walking. */
    val walkStdThreshold: Float = 1.0f,
    /** Mean thigh rotation (rad/s) required for walking. Seated foot-bounce measured ~0.3, walking 1.4+. */
    val walkGyroMin: Float = 0.8f,
    /** Gait rhythm range. Seated foot-bounce measured ~4.7 Hz, walking 1.8–2.4 Hz. */
    val walkCadenceHz: ClosedFloatingPointRange<Float> = 0.8f..3.5f,
    /** Motion that passes everything but cadence still counts as walking this soon after a gait reading. */
    val walkCadenceHoldMs: Long = 5000,
    /** After a user correction, ignore raw readings of the corrected-away state for this long. */
    val correctionHoldMs: Long = 5 * 60_000L,
    /** Angle between gravity and [referenceAxis] (sign-agnostic) below which we're standing. */
    val standMaxDeg: Float = 35f,
    /** ...and above which we're sitting. Between the two is a dead band (no change). */
    val sitMinDeg: Float = 55f,
    /**
     * Phone-frame direction of gravity when standing. Defaults to the phone's long axis;
     * calibrating sets it to the measured gravity vector.
     */
    val referenceAxis: Vec3 = Vec3.Y,
    /**
     * Calibrated phone-frame gravity when sitting. When set, sit vs stand is decided by which
     * reference gravity is closer, with [referenceMarginDeg] of dead band, instead of fixed angles.
     */
    val sittingAxis: Vec3? = null,
    val referenceMarginDeg: Float = 10f,
    /** Proximity "uncovered" must last this long to count as out of pocket (it flickers in pockets). */
    val pocketOutDebounceMs: Long = 1500,
    /** How long a new raw classification must persist before the state changes. */
    val minDwellMs: Map<Posture, Long> = mapOf(
        Posture.SITTING to 4000,
        Posture.STANDING to 4000,
        // Long enough that a few steps or shuffling in place don't count as a walk.
        Posture.WALKING to 15_000,
    ),
    /**
     * Gait readings dip between windows (turns, pauses at a door); a pending WALKING candidate
     * survives non-walking readings for this long instead of restarting its dwell.
     */
    val walkGapToleranceMs: Long = 3000,
    /** Alert once a state has lasted this long (absent = never). */
    val tooLongMs: Map<Posture, Long> = mapOf(Posture.SITTING to 45 * 60_000L, Posture.STANDING to 45 * 60_000L),
    /** (load, current posture) -> drain multiplier. 5 min standing clears 10 min of sitting, etc. */
    val drainRates: Map<Pair<Posture, Posture>, Float> = mapOf(
        (Posture.SITTING to Posture.STANDING) to 2f,
        (Posture.SITTING to Posture.WALKING) to 5f,
        (Posture.STANDING to Posture.SITTING) to 3f,
        (Posture.STANDING to Posture.WALKING) to 3f,
    ),
    /** Only announce a load reaching zero if it had built up at least this much. */
    val loadClearMinMs: Long = 10 * 60_000L,
    /** Re-alert interval while still in a too-long state. */
    val tooLongRepeatMs: Long = 15 * 60_000L,
)
