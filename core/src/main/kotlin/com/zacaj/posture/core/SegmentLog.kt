package com.zacaj.posture.core

/** One stretch of a detected state while in the pocket. [end] is null while ongoing. */
data class Segment(
    val start: Long,
    val end: Long?,
    val detected: Posture,
    val label: Posture? = null,
    /** Sum of still-moment unit gravity vectors (sign-aligned) and their count, for the orientation readout. */
    val gSum: Vec3 = Vec3.ZERO,
    val gN: Int = 0,
) {
    val shown get() = label ?: detected
    /** Mean phone-frame gravity direction while still, or null if never still. */
    val meanGravity: Vec3? get() = if (gN == 0) null else gSum.unitToward(null)
    /** Typical deviation from [meanGravity] (deg); small = held one orientation the whole time. */
    val spreadDeg: Float get() = if (gN == 0) Float.NaN
        else Math.toDegrees(kotlin.math.acos((gSum.norm() / gN).coerceIn(0f, 1f).toDouble())).toFloat()
}

/** Recent detected stretches, newest last, split at state changes and pocket in/out. Not thread-safe. */
class SegmentLog(private val max: Int = 30) {
    private val list = ArrayList<Segment>()
    val segments: List<Segment> get() = list.toList()

    private var state = Posture.UNKNOWN
    private var inPocket = true

    fun onEvent(ev: DetectorEvent) {
        when (ev) {
            is DetectorEvent.StateChanged -> {
                state = ev.to
                if (inPocket) open(ev.since, ev.to)
            }
            is DetectorEvent.PocketChanged -> {
                inPocket = ev.inPocket
                if (ev.inPocket) open(ev.tMs, state) else close(ev.tMs)
            }
            is DetectorEvent.Shifted -> if (inPocket) open(ev.since, state)
            is DetectorEvent.TooLong, is DetectorEvent.LoadCleared -> {}
        }
    }

    /** User corrected the ongoing stretch (detector adopted [p] backdated to [start]). */
    fun correctCurrent(start: Long, p: Posture) {
        state = p
        val cur = list.lastOrNull()
        if (cur != null && cur.end == null) {
            close(start)
            if (list.lastOrNull()?.let { it.end!! <= it.start } == true) list.removeAt(list.size - 1)
        }
        if (inPocket) open(start, p, label = p)
    }

    /** Feed a still-moment gravity reading into the ongoing stretch. */
    fun onGravity(g: Vec3) {
        val cur = list.lastOrNull()?.takeIf { it.end == null } ?: return
        val u = g.unitToward(cur.meanGravity)
        list[list.size - 1] = cur.copy(gSum = cur.gSum + u, gN = cur.gN + 1)
    }

    fun relabel(start: Long, p: Posture): Segment? {
        val i = list.indexOfFirst { it.start == start }.takeIf { it >= 0 } ?: return null
        list[i] = list[i].copy(label = p)
        return list[i]
    }

    private fun open(t: Long, p: Posture, label: Posture? = null) {
        close(t)
        list += Segment(t, null, p, label)
        while (list.size > max) list.removeAt(0)
    }

    private fun close(t: Long) {
        val cur = list.lastOrNull() ?: return
        if (cur.end == null) list[list.size - 1] = cur.copy(end = maxOf(t, cur.start))
    }
}
