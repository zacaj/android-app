package com.zacaj.posture.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorTest {
    private fun transitions(r: ReplayResult) =
        r.events.filterIsInstance<DetectorEvent.StateChanged>().map { it.to }

    @Test
    fun `detects stand sit walk sequence`() {
        val trace = SyntheticTrace(seed = 1)
            .segment(Posture.STANDING, 20_000)
            .segment(Posture.SITTING, 30_000)
            .segment(Posture.WALKING, 20_000)
            .segment(Posture.STANDING, 20_000)
            .events
        val r = Replay.run(trace)
        assertEquals(
            listOf(Posture.STANDING, Posture.SITTING, Posture.WALKING, Posture.STANDING),
            transitions(r),
        )
        val score = r.score()
        println(score.report())
        assertTrue(score.accuracy > 0.85, "accuracy ${score.accuracy}")
        score.latencies.forEach { assertTrue(it.ms != null && it.ms!! < 8000, "latency $it") }
    }

    @Test
    fun `sign agnostic - phone upside down in pocket`() {
        val gen = SyntheticTrace(seed = 2)
        gen.standingGravity = Vec3(-1f, -9.6f, 1f)
        gen.sittingGravity = Vec3(0.5f, -1.5f, -9.6f)
        val r = Replay.run(gen.segment(Posture.STANDING, 15_000).segment(Posture.SITTING, 15_000).events)
        assertEquals(listOf(Posture.STANDING, Posture.SITTING), transitions(r))
    }

    @Test
    fun `brief fidget does not change state`() {
        val trace = SyntheticTrace(seed = 3)
            .segment(Posture.SITTING, 20_000)
            .segment(Posture.WALKING, 1_500, label = false)
            .segment(Posture.SITTING, 20_000, label = false)
            .events
        assertEquals(listOf(Posture.SITTING), transitions(Replay.run(trace)))
    }

    @Test
    fun `too long fires and repeats`() {
        val config = DetectorConfig(tooLongMs = mapOf(Posture.SITTING to 60_000), tooLongRepeatMs = 30_000)
        val trace = SyntheticTrace(seed = 4).segment(Posture.SITTING, 125_000).events
        val alerts = Replay.run(trace, config).events.filterIsInstance<DetectorEvent.TooLong>()
        // ~60s, ~90s, ~120s
        assertEquals(3, alerts.size, alerts.toString())
        assertTrue(alerts.all { it.state == Posture.SITTING })
    }

    @Test
    fun `calibrated reference axis`() {
        val gen = SyntheticTrace(seed = 5)
        gen.standingGravity = Vec3(6.9f, 6.9f, 0f) // phone sits diagonally in pocket
        gen.sittingGravity = Vec3(0f, 0f, 9.8f)
        val trace = gen.segment(Posture.STANDING, 15_000).segment(Posture.SITTING, 15_000).events
        val config = DetectorConfig(referenceAxis = Vec3(0.7f, 0.7f, 0f))
        assertEquals(listOf(Posture.STANDING, Posture.SITTING), transitions(Replay.run(trace, config)))
    }
}

class PocketTest {
    @Test
    fun `using the phone while sitting keeps sitting`() {
        val gen = SyntheticTrace(seed = 6)
            .segment(Posture.SITTING, 20_000)
            .inHand(60_000)
            .segment(Posture.SITTING, 20_000, label = false)
        val r = Replay.run(gen.events)
        assertEquals(
            listOf(Posture.SITTING),
            r.events.filterIsInstance<DetectorEvent.StateChanged>().map { it.to },
        )
    }

    @Test
    fun `without pocket events the same data would flip state`() {
        val gen = SyntheticTrace(seed = 6)
            .segment(Posture.SITTING, 20_000)
            .inHand(60_000)
        val trace = gen.events.filter { it !is TraceEvent.Pocket }
        val r = Replay.run(trace)
        assertTrue(r.events.filterIsInstance<DetectorEvent.StateChanged>().size > 1)
    }

    @Test
    fun `too long timer keeps running while out of pocket`() {
        val config = DetectorConfig(tooLongMs = mapOf(Posture.SITTING to 60_000))
        val gen = SyntheticTrace(seed = 7).segment(Posture.SITTING, 20_000).inHand(60_000)
        val alerts = Replay.run(gen.events, config).events.filterIsInstance<DetectorEvent.TooLong>()
        assertEquals(1, alerts.size)
    }
}
