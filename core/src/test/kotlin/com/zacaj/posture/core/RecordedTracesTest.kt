package com.zacaj.posture.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replays every labeled trace under testdata/traces and asserts a minimum accuracy.
 * Drop recordings from the phone in there (the `traces` branch has them) to tune against.
 */
class RecordedTracesTest {
    private val minAccuracy = 0.80

    @Test
    fun `recorded traces meet accuracy bar`() {
        val dir = File(System.getProperty("testdata.dir") ?: "testdata", "traces")
        val files = dir.walkTopDown().filter { it.isFile && TraceFiles.isTrace(it) }.sorted().toList()
        val failures = mutableListOf<String>()
        for (f in files) {
            val score = Replay.run(TraceIO.read(f)).score()
            if (score.total == 0) continue // unlabeled
            println("${f.name}: ${score.report()}")
            if (score.accuracy < minAccuracy) failures += "${f.name}: %.1f%%".format(score.accuracy * 100)
        }
        assertTrue(failures.isEmpty(), "below ${minAccuracy * 100}%: $failures")
    }
}
