package com.zacaj.posture.core

/** One stretch of a detected state while in the pocket. [end] is null while ongoing. */
data class Segment(val start: Long, val end: Long?, val detected: Posture, val label: Posture? = null) {
    val shown get() = label ?: detected
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
            is DetectorEvent.TooLong -> {}
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

    fun relabel(start: Long, p: Posture): Segment? {
        val i = list.indexOfFirst { it.start == start }.takeIf { it >= 0 } ?: return null
        list[i] = list[i].copy(label = p)
        return list[i]
    }

    private fun open(t: Long, p: Posture, label: Posture? = null) {
        close(t)
        if (p == Posture.UNKNOWN) return
        list += Segment(t, null, p, label)
        while (list.size > max) list.removeAt(0)
    }

    private fun close(t: Long) {
        val cur = list.lastOrNull() ?: return
        if (cur.end == null) list[list.size - 1] = cur.copy(end = maxOf(t, cur.start))
    }
}
