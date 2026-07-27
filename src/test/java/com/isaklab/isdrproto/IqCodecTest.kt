package com.isaklab.isdrproto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random

class IqCodecTest {

    private fun roundTrip(format: Int, iq: FloatArray): FloatArray {
        val buf = ByteArray(IqCodec.encodedSize(format, iq.size))
        IqCodec.encode(iq, iq.size, format, buf, 0)
        val out = FloatArray(iq.size)
        IqCodec.decode(buf, 0, iq.size, format, out)
        return out
    }

    // ---- the reason this exists ------------------------------------------

    @Test fun s8_is_lossless_for_an_eight_bit_front_end() {
        // An RTL dongle emits 8-bit codes; the driver scales them to float.
        // Encoding back to s8 must return the ORIGINAL codes, or the claim
        // "4x smaller and lossless" is false and nobody would notice by ear.
        val codes = (0..255).map { it }
        val asFloat = FloatArray(codes.size) { (codes[it] - 128) / 128f }
        val decoded = roundTrip(DriverProto.IQ_FORMAT_S8, asFloat)
        for (i in codes.indices) {
            val back = Math.round(decoded[i] * 128f) + 128
            assertEquals("code ${codes[i]} did not survive", codes[i], back)
        }
    }

    @Test fun s16_is_lossless_for_a_sixteen_bit_front_end() {
        val codes = intArrayOf(-32768, -32767, -1000, -1, 0, 1, 1000, 32766, 32767)
        val asFloat = FloatArray(codes.size) { codes[it] / 32768f }
        val decoded = roundTrip(DriverProto.IQ_FORMAT_S16, asFloat)
        for (i in codes.indices) {
            assertEquals(codes[i], Math.round(decoded[i] * 32768f))
        }
    }

    @Test fun sizes_are_what_the_bandwidth_claim_depends_on() {
        assertEquals(4000, IqCodec.encodedSize(DriverProto.IQ_FORMAT_F32, 1000))
        assertEquals(2000, IqCodec.encodedSize(DriverProto.IQ_FORMAT_S16, 1000))
        assertEquals(1000, IqCodec.encodedSize(DriverProto.IQ_FORMAT_S8, 1000))
    }

    // ---- clipping --------------------------------------------------------

    @Test fun full_scale_does_not_wrap_to_the_opposite_sign() {
        // Scaling by 32768 instead of 32767 would send +1.0 to -32768 — a
        // sign flip on the loudest sample, heard as a click on peaks.
        val extremes = floatArrayOf(1f, -1f, 0.99999f, -0.99999f)
        for (fmt in intArrayOf(DriverProto.IQ_FORMAT_S16, DriverProto.IQ_FORMAT_S8)) {
            val out = roundTrip(fmt, extremes)
            for (i in extremes.indices) {
                assertTrue(
                    "format $fmt flipped sign at ${extremes[i]} -> ${out[i]}",
                    extremes[i] * out[i] > 0f,
                )
            }
        }
    }

    @Test fun overshoot_clamps_instead_of_wrapping() {
        val hot = floatArrayOf(1.5f, -1.5f, 3f, -3f)
        for (fmt in intArrayOf(DriverProto.IQ_FORMAT_S16, DriverProto.IQ_FORMAT_S8)) {
            val out = roundTrip(fmt, hot)
            for (i in hot.indices) {
                assertTrue("format $fmt wrapped at ${hot[i]}", hot[i] * out[i] > 0f)
                assertTrue("format $fmt exceeded full scale", abs(out[i]) <= 1.01f)
            }
        }
    }

    // ---- accuracy --------------------------------------------------------

    @Test fun s16_error_stays_below_one_lsb() {
        val rnd = Random(7)
        val iq = FloatArray(4096) { rnd.nextFloat() * 2f - 1f }
        val out = roundTrip(DriverProto.IQ_FORMAT_S16, iq)
        val worst = iq.indices.maxOf { abs(iq[it] - out[it]) }
        assertTrue("worst error $worst", worst <= 1f / 32767f)
    }

    @Test fun f32_is_bit_exact() {
        val rnd = Random(11)
        val iq = FloatArray(1024) { rnd.nextFloat() * 2f - 1f }
        assertArrayEquals(iq, roundTrip(DriverProto.IQ_FORMAT_F32, iq), 0f)
    }

    // ---- framing ---------------------------------------------------------

    @Test fun encode_and_decode_agree_on_where_the_payload_ends() {
        // The reader advances by the same count the writer produced, or every
        // field after the IQ block is read from the wrong offset.
        val iq = FloatArray(300) { it / 300f }
        for (fmt in intArrayOf(0, 1, 2)) {
            val buf = ByteArray(8 + IqCodec.encodedSize(fmt, iq.size))
            val end = IqCodec.encode(iq, iq.size, fmt, buf, 4)
            assertEquals(4 + IqCodec.encodedSize(fmt, iq.size), end)
            val out = FloatArray(iq.size)
            assertEquals(end, IqCodec.decode(buf, 4, iq.size, fmt, out))
        }
    }

    @Test fun buffer_decode_matches_array_decode() {
        val iq = FloatArray(256) { (it - 128) / 128f }
        for (fmt in intArrayOf(0, 1, 2)) {
            val buf = ByteArray(IqCodec.encodedSize(fmt, iq.size))
            IqCodec.encode(iq, iq.size, fmt, buf, 0)
            val viaArray = FloatArray(iq.size)
            IqCodec.decode(buf, 0, iq.size, fmt, viaArray)
            val bb = ByteBuffer.wrap(buf)   // protocol order
            val viaBuffer = FloatArray(iq.size)
            IqCodec.decode(bb, iq.size, fmt, viaBuffer)
            assertArrayEquals("format $fmt", viaArray, viaBuffer, 0f)
            assertEquals("format $fmt left the buffer misaligned",
                IqCodec.encodedSize(fmt, iq.size), bb.position())
        }
    }

    @Test fun unknown_formats_are_rejected_rather_than_guessed() {
        assertTrue(DriverProto.isKnownIqFormat(DriverProto.IQ_FORMAT_F32))
        assertTrue(DriverProto.isKnownIqFormat(DriverProto.IQ_FORMAT_S16))
        assertTrue(DriverProto.isKnownIqFormat(DriverProto.IQ_FORMAT_S8))
        assertTrue(!DriverProto.isKnownIqFormat(3))
        assertTrue(!DriverProto.isKnownIqFormat(-1))
    }
}
