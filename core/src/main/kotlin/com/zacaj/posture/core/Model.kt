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

    data class TooLong(override val tMs: Long, val state: Posture, val durationMs: Long) : DetectorEvent
}

data class DetectorConfig(
    /** Sliding window used for gravity estimate and motion energy. */
    val windowMs: Long = 2000,
    /** Std-dev of accel magnitude (m/s^2) above which we call it walking. */
    val walkStdThreshold: Float = 1.0f,
    /** Angle between gravity and [referenceAxis] (sign-agnostic) below which we're standing. */
    val standMaxDeg: Float = 35f,
    /** ...and above which we're sitting. Between the two is a dead band (no change). */
    val sitMinDeg: Float = 55f,
    /**
     * Phone-frame direction of gravity when standing. Defaults to the phone's long axis;
     * calibrating sets it to the measured gravity vector.
     */
    val referenceAxis: Vec3 = Vec3.Y,
    /** How long a new raw classification must persist before the state changes. */
    val minDwellMs: Map<Posture, Long> = mapOf(
        Posture.SITTING to 4000,
        Posture.STANDING to 4000,
        Posture.WALKING to 3000,
    ),
    /** Alert once a state has lasted this long (absent = never). */
    val tooLongMs: Map<Posture, Long> = mapOf(Posture.SITTING to 45 * 60_000L),
    /** Re-alert interval while still in a too-long state. */
    val tooLongRepeatMs: Long = 15 * 60_000L,
)
