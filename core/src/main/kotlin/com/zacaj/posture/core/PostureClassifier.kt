package com.zacaj.posture.core

import kotlin.math.sqrt

/** Instantaneous (un-debounced) classification from sliding accelerometer + gyro windows. */
class PostureClassifier(private val config: DetectorConfig) {
    private val times = ArrayDeque<Long>()
    private val samples = ArrayDeque<Vec3>()
    private val gyroTimes = ArrayDeque<Long>()
    private val gyroMags = ArrayDeque<Float>()

    /** Mean accel over the window (≈ gravity when not moving much). */
    var gravity: Vec3 = Vec3.ZERO
        private set
    var magnitudeStd: Float = 0f
        private set
    /** Sign-agnostic angle (deg) between gravity and the reference axis. */
    var tiltDeg: Float = Float.NaN
        private set
    /** Dominant oscillation rate of |accel| (Hz), from mean crossings. */
    var cadenceHz: Float = 0f
        private set
    /** Mean |gyro| (rad/s) over the window; NaN if no gyro data. */
    var gyroMean: Float = Float.NaN
        private set
    /** Orientation-only classification (ignores motion). */
    var orientation: Posture? = null
        private set

    fun reset() {
        times.clear()
        samples.clear()
        gyroTimes.clear()
        gyroMags.clear()
        lastGaitAt = null
    }

    fun addGyro(tMs: Long, gyro: Vec3) {
        gyroTimes.addLast(tMs)
        gyroMags.addLast(gyro.norm())
        while (gyroTimes.first() < tMs - config.windowMs) {
            gyroTimes.removeFirst()
            gyroMags.removeFirst()
        }
    }

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
        cadenceHz = cadence(mean.toFloat(), magnitudeStd, (tMs - times.first()) / 1000f)
        gyroMean = gyroMags.filterIndexed { i, _ -> gyroTimes[i] >= tMs - config.windowMs }
            .takeIf { it.size >= 5 }?.average()?.toFloat() ?: Float.NaN

        tiltDeg = axisAngleDeg(gravity, config.referenceAxis)
        val sit = config.sittingAxis
        orientation = when {
            tiltDeg.isNaN() -> null
            sit != null -> {
                val sitDeg = axisAngleDeg(gravity, sit)
                when {
                    tiltDeg + config.referenceMarginDeg <= sitDeg -> Posture.STANDING
                    sitDeg + config.referenceMarginDeg <= tiltDeg -> Posture.SITTING
                    else -> null
                }
            }
            tiltDeg <= config.standMaxDeg -> Posture.STANDING
            tiltDeg >= config.sitMinDeg -> Posture.SITTING
            else -> null
        }
        return if (isWalking(tMs)) Posture.WALKING else orientation
    }

    private var lastGaitAt: Long? = null

    /**
     * Walking needs: enough motion, a leg that isn't horizontal, real thigh rotation, and a gait-rate
     * rhythm. Foot-bouncing while seated fails the last three (thigh flat, ~0.3 rad/s, ~4-5 Hz).
     */
    private fun isWalking(tMs: Long): Boolean {
        if (magnitudeStd < config.walkStdThreshold) return false
        if (orientation == Posture.SITTING) return false
        if (!gyroMean.isNaN() && gyroMean < config.walkGyroMin) return false
        if (cadenceHz in config.walkCadenceHz) {
            lastGaitAt = tMs
            return true
        }
        // Cadence estimates wobble mid-walk (turns, uneven steps); keep moving windows shortly after a
        // clear gait reading.
        return lastGaitAt?.let { tMs - it <= config.walkCadenceHoldMs } == true
    }

    /** Mean-crossings of |accel| with hysteresis, as a frequency. */
    private fun cadence(mean: Float, std: Float, spanSec: Float): Float {
        if (spanSec <= 0f || std == 0f) return 0f
        val h = std * 0.3f
        var side = 0
        var crossings = 0
        for (s in samples) {
            val d = s.norm() - mean
            val now = if (d > h) 1 else if (d < -h) -1 else side
            if (side != 0 && now != side) crossings++
            side = now
        }
        return crossings / 2f / spanSec
    }
}
