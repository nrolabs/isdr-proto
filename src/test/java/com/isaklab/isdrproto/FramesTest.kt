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
import org.junit.Assert.assertThrows
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

    @Test fun v2HelloCarriesTheMandatoryFeatureWord() {
        val sink = ByteArrayOutputStream()
        writer(sink).writeHello(DriverProto.VERSION, DriverProto.FEAT_RX_STREAMS)

        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.EV_HELLO, f.op)
        assertEquals(DriverProto.VERSION, f.payload.int)
        assertEquals(4, f.payload.remaining())
        assertEquals(DriverProto.FEAT_RX_STREAMS, f.payload.int)
        assertEquals(0, f.payload.remaining())
    }

    @Test fun commandResultRoundTrip() {
        val sink = ByteArrayOutputStream()
        writer(sink).writeCommandResult(
            DriverProto.CMD_SET_PTT,
            DriverProto.COMMAND_REJECTED,
            "PTT was not confirmed",
        )
        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.EV_COMMAND_RESULT, f.op)
        assertEquals(DriverProto.CMD_SET_PTT, f.payload.get().toInt() and 0xFF)
        assertEquals(DriverProto.COMMAND_REJECTED, f.payload.get().toInt() and 0xFF)
        assertEquals("PTT was not confirmed", f.payload.getUtf())
        assertEquals(0, f.payload.remaining())
    }

    @Test fun rejectedCatResultsCarryTerminalRequestedAndActualState() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.writeCatModeResult(requested = 6, actual = 1, applied = false)
        w.writeCatControlResult(id = 12, requested = 3_200, applied = false, superseded = false)

        val r = reader(sink.toByteArray())
        val mode = r.read()!!
        assertEquals(DriverProto.EV_CAT_MODE_RESULT, mode.op)
        assertEquals(6, mode.payload.int)
        assertEquals(1, mode.payload.int)
        assertFalse(mode.payload.getBool())
        assertEquals(0, mode.payload.remaining())

        val control = r.read()!!
        assertEquals(DriverProto.EV_CAT_CONTROL_RESULT, control.op)
        assertEquals(12, control.payload.int)
        assertEquals(3_200, control.payload.int)
        assertFalse(control.payload.getBool())
        assertFalse(control.payload.getBool())
        assertEquals(0, control.payload.remaining())
    }

    @Test fun booleanReaderRejectsNonCanonicalWireValues() {
        assertFalse(java.nio.ByteBuffer.wrap(byteArrayOf(0)).getBool())
        assertTrue(java.nio.ByteBuffer.wrap(byteArrayOf(1)).getBool())
        assertThrows(java.io.IOException::class.java) {
            java.nio.ByteBuffer.wrap(byteArrayOf(2)).getBool()
        }
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
            DriverProto.DEV_HPSDR_P1,
            "192.168.1.77",
            1024,
            DriverProto.OPEN_FLAG_CLASSIC_BOARD,
        )
        val f = reader(sink.toByteArray()).read()!!
        assertEquals(DriverProto.DEV_HPSDR_P1, f.payload.get().toInt())
        assertEquals("192.168.1.77", f.payload.getUtf())
        assertEquals(1024, f.payload.int)
        assertEquals(DriverProto.OPEN_FLAG_CLASSIC_BOARD, f.payload.int)
    }

    @Test fun classicAnanProfileFlagsMatchTheCrossPlatformContract() {
        assertEquals(8, DriverProto.FEAT_HPSDR_EXACT_PROFILE)
        val exact = listOf(
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN10,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN100,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN10E,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN100B,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN100D,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN200D,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN7000,
            DriverProto.OPEN_HPSDR_CHASSIS_ANAN8000,
        ).map(DriverProto::hpsdrClassicOpenFlags)
        assertEquals(listOf(3, 5, 7, 9, 11, 13, 15, 17), exact)
        exact.forEachIndexed { index, flags ->
            assertEquals(DriverProto.OPEN_FLAG_CLASSIC_BOARD, flags and 1)
            assertEquals(index + 1, DriverProto.hpsdrChassisProfile(flags))
        }
        assertEquals(0, DriverProto.hpsdrChassisProfile(DriverProto.OPEN_FLAG_CLASSIC_BOARD))
        assertFalse(DriverProto.isExactHpsdrP1OpenFlags(DriverProto.OPEN_FLAG_CLASSIC_BOARD))
        assertTrue(DriverProto.isExactHpsdrP1OpenFlags(0))
        assertTrue(exact.all(DriverProto::isExactHpsdrP1OpenFlags))
    }

    @Suppress("DEPRECATION")
    @Test fun typedDiversityWireConstantsAreStableAndDistinctFromStreams() {
        assertEquals(3, DriverProto.DEV_HPSDR_P1)
        assertEquals(DriverProto.DEV_HPSDR_P1, DriverProto.DEV_HL2)
        assertEquals(1024, DriverProto.FEAT_RX_ADC_ROUTING)
        assertEquals(0x2D, DriverProto.CMD_SET_DIVERSITY)
        assertTrue(DriverProto.FEAT_RX_ADC_ROUTING != DriverProto.FEAT_RX_STREAMS)
        assertTrue(DriverProto.CMD_SET_DIVERSITY != DriverProto.CMD_SET_RX_STREAM_MASK)
    }

    @Test fun hackRfInfoQueryFailureBitsMatchTheCrossPlatformContract() {
        assertEquals(1, DriverProto.HRF_INFO_QUERY_FAILED_CLKIN)
        assertEquals(2, DriverProto.HRF_INFO_QUERY_FAILED_OPERACAKE_BOARDS)
        assertEquals(4, DriverProto.HRF_INFO_QUERY_FAILED_CPLD_CHECKSUM)
        assertEquals(8, DriverProto.HRF_INFO_QUERY_FAILED_OPERACAKE_MODE)
        assertEquals(16, DriverProto.HRF_INFO_QUERY_FAILED_FIRMWARE)
        assertEquals(32, DriverProto.HRF_INFO_QUERY_FAILED_BOARD_ID)
        assertEquals(64, DriverProto.HRF_INFO_QUERY_FAILED_SERIAL)
        assertEquals(128, DriverProto.HRF_INFO_QUERY_FAILED_BOARD_REVISION)
        assertEquals(256, DriverProto.HRF_INFO_QUERY_FAILED_PLATFORM)
    }

    @Test fun rtlGainMetadataWireIdsAreStable() {
        assertEquals(2048, DriverProto.FEAT_RTL_GAIN_TABLE)
        assertEquals(0x54, DriverProto.CMD_RTL_QUERY_INFO)
        assertEquals(0x99, DriverProto.EV_RTL_INFO)
        assertEquals(256, DriverProto.RTL_INFO_MAX_GAIN_STEPS)
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
