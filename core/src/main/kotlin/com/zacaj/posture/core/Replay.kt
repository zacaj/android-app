package com.zacaj.posture.core

data class ReplayResult(
    /** Detector state after each accel sample. */
    val timeline: List<Pair<Long, Posture>>,
    /** Accel sample times taken while the phone was out of the pocket (excluded from scoring). */
    val outOfPocket: Set<Long> = emptySet(),
    val events: List<DetectorEvent>,
    val labels: List<TraceEvent.Label>,
) {
    /**
     * Evaluate against ground-truth labels (each label holds until the next one).
     * Samples within [graceMs] after a label are excluded from accuracy, since detection
     * lag is measured separately by the latencies.
     */
    fun score(graceMs: Long = 8000): Score {
        val confusion = mutableMapOf<Pair<Posture, Posture>, Int>()
        if (labels.isEmpty()) return Score(confusion, emptyList())
        var li = -1
        for ((t, s) in timeline) {
            while (li + 1 < labels.size && labels[li + 1].tMs <= t) li++
            // UNKNOWN labels mark the end of a labeled range
            if (li < 0 || labels[li].posture == Posture.UNKNOWN || t - labels[li].tMs < graceMs || t in outOfPocket) continue
            val key = labels[li].posture to s
            confusion[key] = (confusion[key] ?: 0) + 1
        }
        val latencies = labels.mapIndexedNotNull { i, l ->
            if (l.posture == Posture.UNKNOWN) return@mapIndexedNotNull null
            val end = labels.getOrNull(i + 1)?.tMs ?: Long.MAX_VALUE
            val hit = timeline.firstOrNull { (t, s) -> t >= l.tMs && t < end && s == l.posture }
            Latency(l, hit?.let { it.first - l.tMs })
        }
        return Score(confusion, latencies)
    }
}

data class Latency(val label: TraceEvent.Label, /** null = never detected before next label */ val ms: Long?)

data class Score(val confusion: Map<Pair<Posture, Posture>, Int>, val latencies: List<Latency>) {
    val total get() = confusion.values.sum()
    val correct get() = confusion.filterKeys { it.first == it.second }.values.sum()
    val accuracy get() = if (total == 0) Double.NaN else correct.toDouble() / total

    fun report(): String = buildString {
        appendLine("accuracy: %.1f%% (%d/%d samples)".format(accuracy * 100, correct, total))
        appendLine("confusion (label -> detected: samples):")
        for ((k, v) in confusion.entries.sortedBy { it.key.first.ordinal * 10 + it.key.second.ordinal }) {
            appendLine("  ${k.first} -> ${k.second}: $v")
        }
        appendLine("detection latency per label:")
        for (l in latencies) {
            appendLine("  @${l.label.tMs} ${l.label.posture}: ${l.ms?.let { "${it}ms" } ?: "MISSED"}")
        }
    }
}

object Replay {
    fun run(trace: List<TraceEvent>, config: DetectorConfig = DetectorConfig()): ReplayResult {
        val detector = PostureDetector(config)
        val timeline = ArrayList<Pair<Long, Posture>>()
        val events = ArrayList<DetectorEvent>()
        val labels = ArrayList<TraceEvent.Label>()
        val outOfPocket = HashSet<Long>()
        for (e in trace.sortedBy { it.tMs }) {
            when (e) {
                is TraceEvent.Accel -> {
                    events += detector.onAccel(e.tMs, e.v)
                    timeline += e.tMs to detector.state
                    if (!detector.inPocket) outOfPocket += e.tMs
                }
                is TraceEvent.Gyro -> events += detector.onGyro(e.tMs, e.v)
                is TraceEvent.Pocket -> events += detector.onPocket(e.tMs, e.inPocket)
                is TraceEvent.Label -> labels += e
                else -> {}
            }
        }
        return ReplayResult(timeline, outOfPocket, events, labels)
    }
}
