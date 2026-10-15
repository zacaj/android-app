package com.zacaj.posture.core

/**
 * Accumulated "load" for sitting and standing: fills 1:1 while in that posture and drains at
 * [DetectorConfig.drainRates] while doing something else, so a few seconds of another posture
 * barely counts but a proper break clears it. Out-of-pocket / unknown time drains both at
 * [DetectorConfig.unknownDrainRate].
 *
 * Each load is capped at what the other posture can clear without itself hitting its limit
 * (e.g. sitting ≤ standing limit × standing's drain rate on sitting), so a long stretch can't
 * build a debt that would need an unhealthy stretch of the other posture to pay off.
 */
class LoadTracker(private val config: DetectorConfig) {
    val loadMs = mutableMapOf(Posture.SITTING to 0L, Posture.STANDING to 0L)
    private var lastT: Long? = null
    private val lastAlertAt = mutableMapOf<Posture, Long>()
    /** Highest load since it was last zero; decides whether reaching zero is worth announcing. */
    private val peak = mutableMapOf(Posture.SITTING to 0L, Posture.STANDING to 0L)

    fun load(p: Posture) = loadMs[p] ?: 0L

    fun update(tMs: Long, state: Posture, active: Boolean): List<DetectorEvent> {
        val prev = lastT
        lastT = tMs
        if (prev == null) return emptyList()
        val known = active && state != Posture.UNKNOWN
        val dt = (tMs - prev).coerceIn(0, 60_000)
        val out = mutableListOf<DetectorEvent>()
        for (p in loadMs.keys) {
            val before = load(p)
            val rate = if (known) config.drainRates[p to state] ?: 1f else config.unknownDrainRate
            val after = if (known && p == state) minOf(before + dt, maxOf(before, cap(p)))
            else (before - (dt * rate).toLong()).coerceAtLeast(0)
            loadMs[p] = after
            if (after > (peak[p] ?: 0)) peak[p] = after
            if (after == 0L && before > 0) {
                if ((peak[p] ?: 0) >= config.loadClearMinMs) out += DetectorEvent.LoadCleared(tMs, p)
                peak[p] = 0
                lastAlertAt.remove(p)
            }
            val limit = config.tooLongMs[p] ?: continue
            if (!known || p != state || after < limit) continue
            val last = lastAlertAt[p]
            if (last != null && tMs - last < config.tooLongRepeatMs) continue
            lastAlertAt[p] = tMs
            out += DetectorEvent.TooLong(tMs, p, after)
        }
        return out
    }

    /** Max load for [p]: the other posture's limit times its drain rate on [p]; uncapped if that limit is off. */
    fun cap(p: Posture): Long {
        val other = if (p == Posture.SITTING) Posture.STANDING else Posture.SITTING
        val otherLimit = config.tooLongMs[other] ?: return Long.MAX_VALUE
        return (otherLimit * (config.drainRates[p to other] ?: 1f)).toLong()
    }

    /** Overwrite loads as of [tMs] (e.g. after recomputing history for a relabel). */
    fun seed(tMs: Long, sitMs: Long, standMs: Long) {
        loadMs[Posture.SITTING] = sitMs
        loadMs[Posture.STANDING] = standMs
        peak[Posture.SITTING] = maxOf(peak[Posture.SITTING] ?: 0, sitMs)
        peak[Posture.STANDING] = maxOf(peak[Posture.STANDING] ?: 0, standMs)
        lastT = tMs
    }

    fun restore(other: LoadTracker) {
        loadMs.putAll(other.loadMs)
        peak.putAll(other.peak)
        lastAlertAt.putAll(other.lastAlertAt)
        lastT = other.lastT
    }
}
