package com.isaklab.isdrproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The spectrum is what the operator SEES. A codec fault here does not throw;
 * it draws a wrong band, and a wrong band is trusted.
 */
class SpectrumCodecTest {

    private fun roundTrip(bins: FloatArray, format: Int): FloatArray {
        val dst = ByteArray(SpectrumCodec.encodedSize(format, bins.size))
        val end = SpectrumCodec.encode(bins, bins.size, format, dst, 0)
        assertEquals("encode must fill exactly what it sized", dst.size, end)
        val out = FloatArray(bins.size)
        SpectrumCodec.decode(ByteBuffer.wrap(dst), bins.size, format, out)
        return out
    }

    @Test fun float32_is_exact() {
        val bins = FloatArray(64) { -120f + it * 1.37f }
        val out = roundTrip(bins, DriverProto.SPECTRUM_FORMAT_F32)
        for (i in bins.indices) assertEquals(bins[i], out[i], 0f)
    }

    @Test fun u8_resolves_better_than_the_display_needs() {
        // 100 dB of span across 255 codes is 0.4 dB, inside the half-decibel
        // the panadapter can actually show.
        val bins = FloatArray(800) { -140f + it * (100f / 799f) }
        val out = roundTrip(bins, DriverProto.SPECTRUM_FORMAT_U8)
        var worst = 0f
        for (i in bins.indices) worst = maxOf(worst, kotlin.math.abs(bins[i] - out[i]))
        assertTrue("worst error $worst dB", worst <= 0.5f)
    }

    @Test fun u8_costs_a_quarter_of_float32() {
        val n = 800
        val f32 = SpectrumCodec.encodedSize(DriverProto.SPECTRUM_FORMAT_F32, n)
        val u8 = SpectrumCodec.encodedSize(DriverProto.SPECTRUM_FORMAT_U8, n)
        assertEquals(3200, f32)
        assertEquals(808, u8)
        assertTrue("saving is the whole point", f32.toDouble() / u8 > 3.9)
    }

    @Test fun the_range_follows_the_frame_not_a_fixed_map() {
        // A quiet band and a loud one must BOTH get the full 256 levels. A
        // fixed -160..0 map would spend most codes on empty spectrum and
        // clip whatever fell outside it.
        val quiet = FloatArray(256) { -131f + it * (2f / 255f) }   // 2 dB span
        val out = roundTrip(quiet, DriverProto.SPECTRUM_FORMAT_U8)
        var worst = 0f
        for (i in quiet.indices) worst = maxOf(worst, kotlin.math.abs(quiet[i] - out[i]))
        assertTrue("a 2 dB span must resolve finely, got $worst dB", worst <= 0.02f)
    }

    @Test fun a_flat_frame_does_not_divide_by_a_zero_step() {
        val flat = FloatArray(32) { -95.5f }
        val out = roundTrip(flat, DriverProto.SPECTRUM_FORMAT_U8)
        for (v in out) assertEquals(-95.5f, v, 1e-3f)
    }

    @Test fun a_nan_bin_cannot_poison_the_whole_frame() {
        // One NaN reaching min/max would make every code in the frame NaN,
        // blanking the panadapter rather than losing one bin.
        val bins = FloatArray(16) { -100f + it }
        bins[7] = Float.NaN
        val out = roundTrip(bins, DriverProto.SPECTRUM_FORMAT_U8)
        for (i in bins.indices) {
            assertTrue("bin $i came back NaN", !out[i].isNaN())
            if (i != 7) assertEquals(bins[i], out[i], 0.5f)
        }
    }

    @Test fun order_is_preserved_so_the_band_is_not_mirrored() {
        // Monotonic in must be monotonic out: a codec that reversed or
        // rotated the bins would draw a plausible but wrong band.
        val bins = FloatArray(200) { -150f + it * 0.5f }
        val out = roundTrip(bins, DriverProto.SPECTRUM_FORMAT_U8)
        for (i in 1 until out.size) {
            assertTrue("bin $i went backwards", out[i] >= out[i - 1] - 1e-3f)
        }
    }

    @Test fun an_empty_spectrum_costs_nothing() {
        // "Unchanged since the last frame" is sent as zero bins.
        assertEquals(0, SpectrumCodec.encodedSize(DriverProto.SPECTRUM_FORMAT_U8, 0))
        assertEquals(0, SpectrumCodec.encodedSize(DriverProto.SPECTRUM_FORMAT_F32, 0))
    }

    @Test fun only_known_formats_are_accepted() {
        assertTrue(SpectrumCodec.isKnown(DriverProto.SPECTRUM_FORMAT_F32))
        assertTrue(SpectrumCodec.isKnown(DriverProto.SPECTRUM_FORMAT_U8))
        assertTrue(!SpectrumCodec.isKnown(7))
    }
}
