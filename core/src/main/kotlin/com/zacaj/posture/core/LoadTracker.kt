package com.zacaj.posture.core

/**
 * Accumulated "load" for sitting and standing: fills 1:1 while in that posture and drains at
 * [DetectorConfig.drainRates] while doing something else, so a few seconds of another posture
 * barely counts but a proper break clears it. Out-of-pocket time is ignored (posture unknown).
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
        if (prev == null || !active || state == Posture.UNKNOWN) return emptyList()
        val dt = (tMs - prev).coerceIn(0, 60_000)
        val out = mutableListOf<DetectorEvent>()
        for (p in loadMs.keys) {
            val before = load(p)
            val after = if (p == state) before + dt
            else (before - (dt * (config.drainRates[p to state] ?: 1f)).toLong()).coerceAtLeast(0)
            loadMs[p] = after
            if (after > (peak[p] ?: 0)) peak[p] = after
            if (after == 0L && before > 0) {
                if ((peak[p] ?: 0) >= config.loadClearMinMs) out += DetectorEvent.LoadCleared(tMs, p)
                peak[p] = 0
                lastAlertAt.remove(p)
            }
            val limit = config.tooLongMs[p] ?: continue
            if (p != state || after < limit) continue
            val last = lastAlertAt[p]
            if (last != null && tMs - last < config.tooLongRepeatMs) continue
            lastAlertAt[p] = tMs
            out += DetectorEvent.TooLong(tMs, p, after)
        }
        return out
    }

    fun restore(other: LoadTracker) {
        loadMs.putAll(other.loadMs)
        peak.putAll(other.peak)
        lastAlertAt.putAll(other.lastAlertAt)
        lastT = other.lastT
    }
}
