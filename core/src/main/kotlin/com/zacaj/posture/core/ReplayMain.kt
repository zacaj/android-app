package com.zacaj.posture.core

import java.io.File

/** Replays trace files through the detector and prints a score report. */
fun main(args: Array<String>) {
    val files = args.map(::File).ifEmpty {
        File("testdata/traces").walkTopDown().filter { it.isFile && TraceFiles.isTrace(it) }.toList()
    }
    if (files.isEmpty()) {
        println("usage: replay <trace.csv[.gz]>...  (default: testdata/traces/**)")
        return
    }
    for (f in files.sorted()) {
        val result = Replay.run(TraceIO.read(f))
        println("== ${f.path}")
        result.events.filterIsInstance<DetectorEvent.StateChanged>().forEach {
            println("  ${it.tMs} ${it.from} -> ${it.to}")
        }
        println(result.score().report().prependIndent("  "))
    }
}

object TraceFiles {
    fun isTrace(f: File) = f.name.endsWith(".csv") || f.name.endsWith(".csv.gz")
}
