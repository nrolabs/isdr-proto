/*
 * isdr-proto - iSDR driver wire protocol
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 * Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
 * application; all other uses require a separate license from the copyright
 * holder. See LICENSE at the root of this module.
 */
package com.isaklab.isdrproto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every payload shape must round-trip bit-exact through the frame codec. */
class FramesTest {

    private fun writer(sink: ByteArrayOutputStream) =
        Frames(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(sink))

    private fun reader(bytes: ByteArray) =
        Frames(DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream()))

    @Test fun scalarRoundTrip() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.writeI64(DriverProto.CMD_SET_FREQUENCY, 7_100_000L)
        w.writeI32(DriverProto.CMD_SET_SAMPLE_RATE, 384_000)
        w.writeBool(DriverProto.CMD_SET_PTT, true)
        w.write(DriverProto.CMD_HRF_START_RX)

        val r = reader(sink.toByteArray())
        val f1 = r.read()!!
        assertEquals(DriverProto.CMD_SET_FREQUENCY, f1.op)
        assertEquals(7_100_000L, f1.payload.long)
        val f2 = r.read()!!
        assertEquals(384_000, f2.payload.int)
        val f3 = r.read()!!
        assertTrue(f3.payload.getBool())
        val f4 = r.read()!!
        assertEquals(DriverProto.CMD_HRF_START_RX, f4.op)
        assertEquals(0, f4.payload.remaining())
        assertNull(r.read())
    }

    @Test fun dataRoundTrip() {
        val sink = ByteArrayOutputStream()
        val fft = FloatArray(800) { it * 0.5f }
        val iq = FloatArray(2048) { -1f + it * 1e-3f }
        writer(sink).writeData(fft, iq)

        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.EV_DATA, f.op)
        assertArrayEquals(fft, f.payload.getFloats(), 0f)
        assertArrayEquals(iq, f.payload.getFloats(), 0f)
    }

    @Test fun dataRxRoundTrip() {
        val sink = ByteArrayOutputStream()
        val iq = FloatArray(1024) { 1f - it * 2e-3f }
        writer(sink).writeDataRx(3, iq)

        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.EV_DATA_RX, f.op)
        assertEquals(3, f.payload.int)
        assertArrayEquals(iq, f.payload.getFloats(), 0f)
    }

    @Test fun helloFeaturesAreTrailingAndOptional() {
        val sink = ByteArrayOutputStream()
        writer(sink).writeHello(DriverProto.VERSION, DriverProto.FEAT_RX_STREAMS)

        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.EV_HELLO, f.op)
        assertEquals(DriverProto.VERSION, f.payload.int)
        // an old app stops here; a new one reads the trailing features word
        assertTrue(f.payload.remaining() >= 4)
        assertEquals(DriverProto.FEAT_RX_STREAMS, f.payload.int)

        // legacy hello (version only) still parses: features default to 0
        val sink2 = ByteArrayOutputStream()
        writer(sink2).writeI32(DriverProto.EV_HELLO, DriverProto.VERSION)
        val f2 = reader(sink2.toByteArray()).read()!!
        assertEquals(DriverProto.VERSION, f2.payload.int)
        assertEquals(0, f2.payload.remaining())
    }

    @Test fun emptySpectrumData() {
        val sink = ByteArrayOutputStream()
        writer(sink).writeData(FloatArray(0), FloatArray(4) { 0.25f })
        val f = reader(sink.toByteArray()).read()!!
        assertEquals(0, f.payload.getFloats().size)
        assertEquals(4, f.payload.getFloats().size)
    }

    @Test fun statusRoundTrip() {
        val sink = ByteArrayOutputStream()
        writer(sink).writeStatus(true, "Conectado · fw 2026.02.1")
        val f = reader(sink.toByteArray()).read()!!
        assertTrue(f.payload.getBool())
        assertEquals("Conectado · fw 2026.02.1", f.payload.getUtf())
    }

    @Test fun telemetryRoundTrip() {
        val t = RadioTelemetry(
            temperatureC = 41.5, paCurrentA = 1.2,
            forwardPower = 4.8, reversePower = 0.3, supplyVolts = 0.0,
            exciterPower = 12.0, adcOverload = true, pllLocked = true,
            keyPtt = true, keyDot = false, keyDash = true,
            hasTemperature = true, hasCurrent = true,
            hasFwdPower = true, hasRevPower = true, hasSupplyVolts = false,
            hasAdcOverload = true, hasPllLock = false,
            hasExciterPower = true, hasKeyInputs = true,
        )
        val sink = ByteArrayOutputStream()
        writer(sink).writeTelemetry(t)
        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.EV_TELEMETRY, f.op)
        assertEquals(t, f.payload.getTelemetry())
    }

    @Test fun openRoundTrip() {
        val sink = ByteArrayOutputStream()
        writer(sink).writeOpen(
            DriverProto.DEV_HL2, "192.168.1.77", 1024, DriverProto.OPEN_FLAG_CLASSIC_BOARD,
        )
        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.DEV_HL2, f.payload.get().toInt())
        assertEquals("192.168.1.77", f.payload.getUtf())
        assertEquals(1024, f.payload.int)
        assertEquals(DriverProto.OPEN_FLAG_CLASSIC_BOARD, f.payload.int)
    }

    @Test fun sweepBlockRoundTrip() {
        val sink = ByteArrayOutputStream()
        val iq = FloatArray(64) { it.toFloat() }
        writer(sink).writeSweepBlock(144_000_000L, iq)
        val f = reader(sink.toByteArray()).read()!!
        assertEquals(144_000_000L, f.payload.long)
        assertArrayEquals(iq, f.payload.getFloats(), 0f)
    }

    @Test fun telemetryFlagsMask() {
        val none = RadioTelemetry()
        assertEquals(0, none.flags())
        assertFalse(
            RadioTelemetry.fromWire(0, 0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0).hasTemperature,
        )
    }
}
