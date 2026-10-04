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
    fun `reports pocket out and back in`() {
        val gen = SyntheticTrace(seed = 6)
            .segment(Posture.SITTING, 20_000)
            .inHand(60_000)
            .segment(Posture.SITTING, 20_000, label = false)
        val r = Replay.run(gen.events)
        assertEquals(
            listOf(false, true),
            r.events.filterIsInstance<DetectorEvent.PocketChanged>().map { it.inPocket },
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

class CalibrationTest {
    @Test
    fun `two-point calibration handles a shallow sitting angle`() {
        // Pocket where sitting only tilts ~40° from standing: fixed thresholds can't see it.
        val gen = SyntheticTrace(seed = 8)
        gen.standingGravity = Vec3(0f, 9.8f, 0f)
        gen.sittingGravity = Vec3(0f, 7.5f, 6.3f)
        val trace = gen.segment(Posture.STANDING, 15_000).segment(Posture.SITTING, 15_000).events
        val uncalibrated = Replay.run(trace).events.filterIsInstance<DetectorEvent.StateChanged>().map { it.to }
        assertEquals(listOf(Posture.STANDING), uncalibrated)

        val config = DetectorConfig(referenceAxis = Vec3(0f, 9.8f, 0f), sittingAxis = Vec3(0f, 7.5f, 6.3f))
        val calibrated = Replay.run(trace, config).events.filterIsInstance<DetectorEvent.StateChanged>().map { it.to }
        assertEquals(listOf(Posture.STANDING, Posture.SITTING), calibrated)
    }

    @Test
    fun `calibration sample rejects movement`() {
        val still = SyntheticTrace(seed = 9).segment(Posture.STANDING, 5_000).events
            .filterIsInstance<TraceEvent.Accel>().map { it.v }
        val moving = SyntheticTrace(seed = 9).segment(Posture.WALKING, 5_000).events
            .filterIsInstance<TraceEvent.Accel>().map { it.v }
        assertTrue(CalibrationSample.of(still).ok)
        assertTrue(!CalibrationSample.of(moving).ok)
    }
}

class PocketFlickerTest {
    @Test
    fun `proximity flicker in pocket does not pause detection`() {
        val gen = SyntheticTrace(seed = 10).segment(Posture.STANDING, 10_000)
        // flicker out/in every ~400ms for 6s, while actually sitting down
        val flickerStart = gen.events.last().tMs
        gen.segment(Posture.SITTING, 20_000, label = true)
        val flicker = (0 until 15).flatMap { i ->
            val t = flickerStart + i * 400
            listOf(TraceEvent.Pocket(t, false), TraceEvent.Pocket(t + 200, true))
        }
        val r = Replay.run(gen.events + flicker)
        assertEquals(
            listOf(Posture.STANDING, Posture.SITTING),
            r.events.filterIsInstance<DetectorEvent.StateChanged>().map { it.to },
        )
        assertTrue(r.outOfPocket.isEmpty())
    }
}

class RestoreTest {
    @Test
    fun `restored state does not re-emit`() {
        val sm = PostureStateMachine(DetectorConfig())
        sm.restore(Posture.SITTING, 1000)
        val ev = (0..100).flatMap { sm.onRaw(2000L + it * 40, Posture.SITTING) }
        assertTrue(ev.none { it is DetectorEvent.StateChanged })
    }
}

class CorrectionTest {
    private fun feed(d: PostureDetector, events: List<TraceEvent>) = events.sortedBy { it.tMs }.forEach {
        when (it) {
            is TraceEvent.Accel -> d.onAccel(it.tMs, it.v)
            is TraceEvent.Pocket -> d.onPocket(it.tMs, it.inPocket)
            else -> {}
        }
    }

    @Test
    fun `correction covers in-pocket stretch and backdates state`() {
        val gen = SyntheticTrace(seed = 11).segment(Posture.STANDING, 30_000)
        val pocketOutAt = gen.events.last().tMs
        gen.inHand(20_000)
        val d = PostureDetector()
        // drop the trailing pocket-in so we're still holding the phone when correcting
        feed(d, gen.events.dropLast(1))
        assertEquals(Posture.STANDING, d.state)
        val now = gen.events.last().tMs
        val (start, end) = d.correct(now, Posture.SITTING)
        assertEquals(d.stateMachine.stateSince, start)
        assertTrue(end in pocketOutAt..pocketOutAt + 2000, "segment should end at debounced pocket-out")
        assertEquals(Posture.SITTING, d.state)
    }

    @Test
    fun `segment starts at pocket-in when state began earlier`() {
        val gen = SyntheticTrace(seed = 12).segment(Posture.SITTING, 20_000).inHand(10_000)
        val pocketInAt = gen.events.last().tMs
        gen.segment(Posture.SITTING, 20_000, label = false)
        val d = PostureDetector()
        feed(d, gen.events)
        val (start, _) = d.lastSegment(gen.events.last().tMs)
        assertEquals(pocketInAt, start)
    }

    @Test
    fun `range labels end at UNKNOWN`() {
        val trace = SyntheticTrace(seed = 13).segment(Posture.STANDING, 20_000, label = false)
            .segment(Posture.SITTING, 20_000, label = false).events.toMutableList()
        val t0 = trace.first().tMs
        trace += TraceEvent.Label(t0, Posture.STANDING)
        trace += TraceEvent.Label(t0 + 20_000, Posture.UNKNOWN)
        val score = Replay.run(trace).score()
        assertTrue(score.confusion.keys.all { it.first == Posture.STANDING }, score.report())
        assertEquals(1, score.latencies.size)
    }
}

class CorrectionHoldTest {
    @Test
    fun `correction is not immediately overridden by the same misreading`() {
        val sm = PostureStateMachine(DetectorConfig())
        var t = 0L
        repeat(200) { sm.onRaw(t, Posture.WALKING); t += 40 }
        assertEquals(Posture.WALKING, sm.state)
        sm.suppress(Posture.WALKING, t + 60_000)
        sm.restore(Posture.SITTING, t)
        repeat(500) { sm.onRaw(t, Posture.WALKING); t += 40 } // 20s of the same misreading
        assertEquals(Posture.SITTING, sm.state)
        // a different state can still take over
        repeat(200) { sm.onRaw(t, Posture.STANDING); t += 40 }
        assertEquals(Posture.STANDING, sm.state)
    }
}
