package com.zacaj.posture.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.Writer
import java.util.zip.GZIPInputStream

/**
 * Trace format: CSV, one event per line, `t_ms,kind,x,y,z`.
 *  - `a` accelerometer (m/s^2), `g` gyroscope (rad/s)
 *  - `label` ground truth posture in x (user-entered), `state` detector state in x, `note` free text in x
 * Lines starting with `#` are comments.
 */
sealed interface TraceEvent {
    val tMs: Long
    data class Accel(override val tMs: Long, val v: Vec3) : TraceEvent
    data class Gyro(override val tMs: Long, val v: Vec3) : TraceEvent
    data class Label(override val tMs: Long, val posture: Posture) : TraceEvent
    data class State(override val tMs: Long, val posture: Posture) : TraceEvent
    data class Note(override val tMs: Long, val text: String) : TraceEvent
}

object TraceIO {
    const val HEADER = "# posture-trace v1\nt_ms,kind,x,y,z\n"

    fun read(file: File): List<TraceEvent> =
        open(file).use { read(it) }

    fun open(file: File): InputStream {
        val raw = file.inputStream().buffered()
        return if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw
    }

    fun read(input: InputStream): List<TraceEvent> =
        input.bufferedReader().let { read(it) }

    fun read(reader: BufferedReader): List<TraceEvent> {
        val out = ArrayList<TraceEvent>()
        reader.lineSequence().forEach { line ->
            parseLine(line)?.let(out::add)
        }
        return out
    }

    fun parseLine(line: String): TraceEvent? {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("t_ms")) return null
        val p = line.split(',', limit = 5)
        if (p.size < 3) return null
        val t = p[0].toLongOrNull() ?: return null
        fun v() = Vec3(p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
        return when (p[1]) {
            "a" -> TraceEvent.Accel(t, v())
            "g" -> TraceEvent.Gyro(t, v())
            "label" -> runCatching { Posture.valueOf(p[2]) }.getOrNull()?.let { TraceEvent.Label(t, it) }
            "state" -> runCatching { Posture.valueOf(p[2]) }.getOrNull()?.let { TraceEvent.State(t, it) }
            "note" -> TraceEvent.Note(t, p.drop(2).joinToString(",").trimEnd(','))
            else -> null
        }
    }

    fun format(e: TraceEvent): String = when (e) {
        is TraceEvent.Accel -> "${e.tMs},a,${e.v.x},${e.v.y},${e.v.z}"
        is TraceEvent.Gyro -> "${e.tMs},g,${e.v.x},${e.v.y},${e.v.z}"
        is TraceEvent.Label -> "${e.tMs},label,${e.posture},,"
        is TraceEvent.State -> "${e.tMs},state,${e.posture},,"
        is TraceEvent.Note -> "${e.tMs},note,${e.text.replace('\n', ' ')},,"
    }

    fun write(events: Iterable<TraceEvent>, out: Writer) {
        out.write(HEADER)
        for (e in events) {
            out.write(format(e))
            out.write("\n")
        }
    }
}
