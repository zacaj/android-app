package com.zacaj.posture.core

import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceIOTest {
    @Test
    fun `round trip`() {
        val events = listOf(
            TraceEvent.Accel(1, Vec3(0.5f, 9.8f, -0.25f)),
            TraceEvent.Gyro(2, Vec3(0.01f, 0f, 0f)),
            TraceEvent.Label(3, Posture.SITTING),
            TraceEvent.State(4, Posture.STANDING),
            TraceEvent.Note(5, "calibrated"),
        )
        val w = StringWriter()
        TraceIO.write(events, w)
        assertEquals(events, TraceIO.read(w.toString().byteInputStream()))
    }
}
