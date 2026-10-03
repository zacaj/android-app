package com.zacaj.posture.core

/** Debounces raw classifications into a stable state and fires too-long alerts. */
class PostureStateMachine(private val config: DetectorConfig) {
    var state: Posture = Posture.UNKNOWN
        private set
    var stateSince: Long = 0
        private set

    private var candidate: Posture? = null
    private var candidateSince = 0L
    private var lastTooLongAt: Long? = null

    fun onRaw(tMs: Long, raw: Posture?): List<DetectorEvent> {
        val out = mutableListOf<DetectorEvent>()
        if (raw == null || raw == state) {
            // Dead band / ambiguous keeps the pending candidate; agreeing with state cancels it.
            if (raw == state) candidate = null
        } else if (raw != candidate) {
            candidate = raw
            candidateSince = tMs
        } else if (tMs - candidateSince >= (config.minDwellMs[raw] ?: 0L)) {
            out += DetectorEvent.StateChanged(tMs, state, raw, candidateSince)
            state = raw
            stateSince = candidateSince
            candidate = null
            lastTooLongAt = null
        }
        out += tick(tMs)
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

    fun tick(tMs: Long): List<DetectorEvent> {
        val limit = config.tooLongMs[state] ?: return emptyList()
        val dur = tMs - stateSince
        if (dur < limit) return emptyList()
        val last = lastTooLongAt
        if (last != null && tMs - last < config.tooLongRepeatMs) return emptyList()
        lastTooLongAt = tMs
        return listOf(DetectorEvent.TooLong(tMs, state, dur))
    }
}

/** Classifier + state machine. Not thread-safe; feed from one thread. */
class PostureDetector(val config: DetectorConfig = DetectorConfig()) {
    val classifier = PostureClassifier(config)
    val stateMachine = PostureStateMachine(config)
    var lastRaw: Posture? = null
        private set

    /**
     * Whether the phone is in a pocket (proximity covered). While out, orientation says nothing
     * about posture: the state is frozen (too-long timers keep running) and the window is
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

    private fun setPocket(tMs: Long, inPocket: Boolean): List<DetectorEvent> {
        if (inPocket == this.inPocket) return emptyList()
        this.inPocket = inPocket
        classifier.reset()
        stateMachine.clearCandidate()
        lastRaw = null
        return stateMachine.tick(tMs)
    }

    fun onAccel(tMs: Long, accel: Vec3): List<DetectorEvent> {
        val pocketEvents = checkPendingOut(tMs)
        if (pocketEvents.isNotEmpty()) return pocketEvents
        if (!inPocket) return stateMachine.tick(tMs)
        lastRaw = classifier.add(tMs, accel)
        return stateMachine.onRaw(tMs, lastRaw)
    }

    /** Gyro isn't used for classification yet; it's recorded for offline tuning. */
    @Suppress("UNUSED_PARAMETER")
    fun onGyro(tMs: Long, gyro: Vec3): List<DetectorEvent> = emptyList()
}
