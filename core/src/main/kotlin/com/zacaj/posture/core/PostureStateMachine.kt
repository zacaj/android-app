package com.zacaj.posture.core

/** Debounces raw classifications into a stable state. Too-long alerts live in [LoadTracker]. */
class PostureStateMachine(private val config: DetectorConfig) {
    var state: Posture = Posture.UNKNOWN
        private set
    var stateSince: Long = 0
        private set

    private var candidate: Posture? = null
    private var candidateSince = 0L
    private var candidateLastSeen = 0L

    private var suppressed: Posture? = null
    private var suppressedUntil = 0L

    /** Treat raw readings of [p] as ambiguous until [untilMs] (after a user correction). */
    fun suppress(p: Posture, untilMs: Long) {
        suppressed = p
        suppressedUntil = untilMs
    }

    fun onRaw(tMs: Long, rawIn: Posture?): List<DetectorEvent> {
        val raw = if (rawIn != null && rawIn == suppressed && tMs < suppressedUntil) null else rawIn
        val out = mutableListOf<DetectorEvent>()
        val gapOk = candidate == Posture.WALKING && tMs - candidateLastSeen <= config.walkGapToleranceMs
        if (raw == null || raw == state) {
            // Dead band / ambiguous keeps the pending candidate; agreeing with state cancels it
            // (after a short grace for walking, whose readings dip mid-stride).
            if (raw == state && !gapOk) candidate = null
        } else if (raw != candidate) {
            candidate = raw
            candidateSince = tMs
            candidateLastSeen = tMs
        } else {
            candidateLastSeen = tMs
            if (tMs - candidateSince >= (config.minDwellMs[raw] ?: 0L)) {
                out += DetectorEvent.StateChanged(tMs, state, raw, candidateSince)
                state = raw
                stateSince = candidateSince
                candidate = null
            }
        }
        return out
    }

    /** Carry state over from a previous instance (config reload) without emitting a change. */
    fun restore(state: Posture, since: Long) {
        this.state = state
        stateSince = since
    }

    /** Drop any pending transition (e.g. phone left the pocket). */
    fun clearCandidate() {
        candidate = null
    }

}

private const val STILL_STD = 0.5f

/** Classifier + state machine. Not thread-safe; feed from one thread. */
class PostureDetector(val config: DetectorConfig = DetectorConfig()) {
    val classifier = PostureClassifier(config)
    val stateMachine = PostureStateMachine(config)
    val loads = LoadTracker(config)
    var lastRaw: Posture? = null
        private set

    /**
     * Whether the phone is in a pocket (proximity covered). While out, orientation says nothing
     * about posture: the state and posture loads are frozen, and the window is
     * cleared on re-entry so pull-out/put-back motion isn't classified.
     */
    var inPocket: Boolean = true
        private set

    val state get() = stateMachine.state

    /** Raw proximity reading; out-of-pocket only takes effect after [DetectorConfig.pocketOutDebounceMs]. */
    private var pendingOutSince: Long? = null

    fun onPocket(tMs: Long, inPocket: Boolean): List<DetectorEvent> {
        if (inPocket) {
            pendingOutSince = null
            return setPocket(tMs, true)
        }
        if (this.inPocket && pendingOutSince == null) pendingOutSince = tMs
        return checkPendingOut(tMs)
    }

    private fun checkPendingOut(tMs: Long): List<DetectorEvent> {
        val since = pendingOutSince ?: return emptyList()
        if (tMs - since < config.pocketOutDebounceMs) return emptyList()
        pendingOutSince = null
        return setPocket(tMs, false)
    }

    /** When the phone last (debounced) went into / came out of the pocket. */
    var lastPocketInAt: Long? = null
        private set
    var lastPocketOutAt: Long? = null
        private set

    private fun setPocket(tMs: Long, inPocket: Boolean): List<DetectorEvent> {
        if (inPocket == this.inPocket) return emptyList()
        this.inPocket = inPocket
        if (inPocket) lastPocketInAt = tMs else lastPocketOutAt = tMs
        classifier.reset()
        resetAnchor()
        stateMachine.clearCandidate()
        lastRaw = null
        return listOf(DetectorEvent.PocketChanged(tMs, inPocket))
    }

    /**
     * The latest stretch of in-pocket time the current state covers: from when the state began
     * (or the phone last went into the pocket, if later) until it came out (or now).
     */
    fun lastSegment(now: Long): Pair<Long, Long> {
        val end = if (!inPocket) lastPocketOutAt ?: now else now
        val since = stateMachine.stateSince.takeIf { state != Posture.UNKNOWN }
        val pocketIn = lastPocketInAt?.takeIf { it < end }
        val start = listOfNotNull(since, pocketIn).maxOrNull()?.takeIf { it < end }
            ?: since?.takeIf { it < end }
            ?: (end - 60_000)
        return start to end
    }

    /**
     * User says the last segment was actually [posture]. Adopts it as the current state (backdated
     * to the segment start, so too-long timers count correctly) and returns the segment.
     */
    fun correct(now: Long, posture: Posture): Pair<Long, Long> {
        val seg = lastSegment(now)
        if (posture != state) {
            // The detector was wrong about the old state; don't let it flip straight back.
            stateMachine.suppress(state, now + config.correctionHoldMs)
            stateMachine.restore(posture, seg.first)
        }
        stateMachine.clearCandidate()
        return seg
    }

    fun onAccel(tMs: Long, accel: Vec3): List<DetectorEvent> {
        val pocketEvents = checkPendingOut(tMs)
        if (pocketEvents.isNotEmpty()) return pocketEvents
        if (!inPocket) return loads.update(tMs, state, active = false)
        lastRaw = classifier.add(tMs, accel)
        val changes = stateMachine.onRaw(tMs, lastRaw)
        if (changes.isNotEmpty()) resetAnchor()
        return changes + checkShift(tMs) + loads.update(tMs, state, active = true)
    }

    // Orientation drift tracking for [DetectorEvent.Shifted]: the settled direction of the current
    // stretch, and since when a different one has held.
    private var anchor: Vec3? = null
    private var driftSince: Long? = null
    private var driftDir: Vec3? = null

    private fun resetAnchor() {
        anchor = null
        driftSince = null
        driftDir = null
    }

    private fun checkShift(tMs: Long): List<DetectorEvent> {
        val c = classifier
        if (c.gravity == Vec3.ZERO || c.magnitudeStd > STILL_STD || state == Posture.WALKING) return emptyList()
        val a = anchor ?: run { anchor = c.gravity; return emptyList() }
        val off = axisAngleDeg(c.gravity, a)
        if (off < config.shiftDeg) {
            driftSince = null
            return emptyList()
        }
        val d = driftDir
        // The new orientation itself must be steady, or it's just fidgeting.
        if (driftSince == null || d == null || axisAngleDeg(c.gravity, d) > config.shiftDeg / 2) {
            driftSince = tMs
            driftDir = c.gravity
            return emptyList()
        }
        if (tMs - driftSince!! < config.shiftHoldMs) return emptyList()
        val since = driftSince!!
        anchor = c.gravity
        driftSince = null
        return listOf(DetectorEvent.Shifted(tMs, since, off))
    }

    /** Gyro feeds the walking check (thigh rotation). */
    fun onGyro(tMs: Long, gyro: Vec3): List<DetectorEvent> {
        if (inPocket) classifier.addGyro(tMs, gyro)
        return emptyList()
    }
}
