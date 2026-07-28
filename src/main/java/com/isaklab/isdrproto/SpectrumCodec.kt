/*
 * Spectrum wire encoding (FEAT_SPECTRUM_FORMAT).
 *
 * The panadapter needs about half a decibel of resolution across whatever
 * range a frame actually spans. Float32 per bin gives seven significant
 * digits of a value that is drawn as one pixel, which is four times the bytes
 * for none of the information.
 *
 * Block floating point solves it without a fixed range to get wrong: each
 * frame carries its own floor and step, so a quiet band and a band with a
 * pile-driver signal in it both get the full 256 levels across the range they
 * occupy. A fixed -160..0 dB map would waste most of its codes on empty
 * spectrum and clip anything outside.
 */
package com.isaklab.isdrproto

import java.nio.ByteBuffer

/**
 * Encoder and decoder for the block-floating-point spectrum format used in the iSDR protocol.
 *
 * Why block floating point? A fixed-point representation (e.g. -160 to 0 dB) would waste 
 * most of its resolution on empty spectrum and clip out-of-bounds signals. By transmitting
 * a per-frame `min` and `step`, the codec dynamically utilizes the full 8-bit dynamic range 
 * (256 levels) for the actual signal span in the frame. This reduces bandwidth by 75% 
 * compared to 32-bit floats while maintaining ~0.5 dB visual resolution, which is 
 * optimal for panadapter rendering.
 */
object SpectrumCodec {

    /** Header bytes before the codes: float min, float step. */
    const val HEADER_BYTES = 8

    /**
     * Smallest step to bother with, in dB. Below this the frame is flat
     * enough that the step is dominated by rounding, and a degenerate
     * (zero) step would make decode divide the range by nothing.
     */
    private const val MIN_STEP_DB = 1e-4f

    /** Bytes [n] bins occupy in [format]. */
    fun encodedSize(format: Int, n: Int): Int = when (format) {
        DriverProto.SPECTRUM_FORMAT_U8 -> if (n == 0) 0 else HEADER_BYTES + n
        else -> n * 4
    }

    /**
     * Write [n] dB bins into [dst] at [offset].
     * @return the offset just past the last byte written
     */
    fun encode(bins: FloatArray, n: Int, format: Int, dst: ByteArray, offset: Int): Int {
        if (format != DriverProto.SPECTRUM_FORMAT_U8) {
            val bb = ByteBuffer.wrap(dst, offset, n * 4)
            for (i in 0 until n) bb.putFloat(bins[i])
            return offset + n * 4
        }
        if (n == 0) return offset

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val v = bins[i]
            // NaN would poison min/max and then every code in the frame.
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min > max) {                 // every bin was NaN
            min = 0f
            max = 0f
        }
        val step = ((max - min) / 255f).coerceAtLeast(MIN_STEP_DB)

        val bb = ByteBuffer.wrap(dst, offset, HEADER_BYTES)
        bb.putFloat(min)
        bb.putFloat(step)
        var p = offset + HEADER_BYTES
        for (i in 0 until n) {
            val v = bins[i]
            val code = if (v.isNaN()) 0 else Math.round((v - min) / step)
            dst[p++] = code.coerceIn(0, 255).toByte()
        }
        return p
    }

    /**
     * Read [n] bins from [src] into [out], advancing the buffer's position.
     * [out] must hold at least [n] entries.
     */
    fun decode(src: ByteBuffer, n: Int, format: Int, out: FloatArray) {
        if (format != DriverProto.SPECTRUM_FORMAT_U8) {
            for (i in 0 until n) out[i] = src.float
            return
        }
        if (n == 0) return
        val min = src.float
        val step = src.float
        for (i in 0 until n) {
            out[i] = min + step * (src.get().toInt() and 0xFF)
        }
    }

    /** True for a format this build can encode and decode. */
    fun isKnown(format: Int): Boolean =
        format == DriverProto.SPECTRUM_FORMAT_F32 || format == DriverProto.SPECTRUM_FORMAT_U8
}
