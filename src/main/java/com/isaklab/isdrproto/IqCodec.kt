/*
 * IQ wire encoding (FEAT_IQ_FORMAT).
 *
 * Both sides work in float [-1,1); this only narrows the transport. The
 * driver already converted the radio's native integers UP to float, so
 * encoding back down at or above the native depth returns the original
 * bytes — it undoes an expansion rather than discarding signal.
 */
package com.isaklab.isdrproto

import java.nio.ByteBuffer

/**
 * Encoder and decoder for in-phase and quadrature (IQ) streams over the iSDR wire protocol.
 *
 * Why block floating point for IQ? The driver natively scales radio integers to floats in [-1, 1).
 * Passing 32-bit floats over the wire wastes bandwidth since typical SDR ADCs are 8 to 24 bits.
 * Fixed integer representations waste bits on unused headroom (e.g., a signal 12 dB below full scale 
 * loses 2 bits of resolution). Block floating point dynamically captures the peak of the block, 
 * using all available 8 bits purely for the active signal. This yields significant bandwidth savings
 * without perceptible degradation, especially after channelization removes strong adjacent signals.
 */
object IqCodec {

    /**
     * Full-scale for the signed integer formats: 2^(n-1), matching how the
     * drivers convert their native codes UP to float — e.g. an RTL sample
     * becomes `(code - 128) / 128`.
     *
     * Using 127 / 32767 instead would be a DIFFERENT scale from the driver's,
     * and the round trip would drift by one code: 64 comes back as 65. The
     * whole claim of this format is that it is lossless at the native depth,
     * so the scale has to be the same one on both sides.
     *
     * The positive end is clamped rather than scaled down, because a float
     * derived from an n-bit code never reaches +1.0 anyway (its maximum is
     * (2^(n-1) - 1) / 2^(n-1)); only a driver overshooting its own range
     * would hit the clamp.
     */
    private const val S16_SCALE = 32768f
    private const val S8_SCALE = 128f

    /**
     * Block-floating-point header: one float32 scale for the whole block.
     *
     * The fixed formats waste whatever headroom the signal is not using —
     * a stream sitting 12 dB below full scale spends two of its eight bits
     * on nothing. Carrying the block's own peak instead spends all of them
     * on the signal that is actually there, which is what makes eight bits
     * enough after channelisation has removed the strong neighbours.
     */
    const val BFP_HEADER_BYTES = 4

    /**
     * Smallest scale worth encoding. A digitally silent block would
     * otherwise divide every sample by zero.
     */
    private const val MIN_BFP_SCALE = 1e-9f

    /** Bytes [count] samples occupy in [format]. */
    fun encodedSize(format: Int, count: Int): Int = when (format) {
        DriverProto.IQ_FORMAT_BFP8 ->
            if (count == 0) 0 else BFP_HEADER_BYTES + count
        else -> count * DriverProto.iqSampleBytes(format)
    }

    /**
     * Write [count] samples of [iq] into [out] at [offset] in [format].
     * @return the offset just past the written bytes.
     */
    fun encode(iq: FloatArray, count: Int, format: Int, out: ByteArray, offset: Int): Int {
        // Big-endian, like every other field in DriverProto — the existing
        // float32 payload already ships this way and must keep its meaning.
        if (format == DriverProto.IQ_FORMAT_BFP8) return encodeBfp8(iq, count, out, offset)
        val bb = ByteBuffer.wrap(out, offset, encodedSize(format, count))
        when (format) {
            DriverProto.IQ_FORMAT_S16 ->
                for (i in 0 until count) bb.putShort(toS16(iq[i]))
            DriverProto.IQ_FORMAT_S8 ->
                for (i in 0 until count) bb.put(toS8(iq[i]))
            else ->
                for (i in 0 until count) bb.putFloat(iq[i])
        }
        return offset + encodedSize(format, count)
    }

    /**
     * Read [count] samples in [format] from [src] at [offset] into [out].
     * @return the offset just past the consumed bytes.
     */
    fun decode(src: ByteArray, offset: Int, count: Int, format: Int, out: FloatArray): Int {
        if (format == DriverProto.IQ_FORMAT_BFP8) {
            decode(ByteBuffer.wrap(src, offset, encodedSize(format, count)), count, format, out)
            return offset + encodedSize(format, count)
        }
        val bb = ByteBuffer.wrap(src, offset, encodedSize(format, count))
        when (format) {
            DriverProto.IQ_FORMAT_S16 ->
                for (i in 0 until count) out[i] = bb.short / S16_SCALE
            DriverProto.IQ_FORMAT_S8 ->
                for (i in 0 until count) out[i] = bb.get() / S8_SCALE
            else ->
                for (i in 0 until count) out[i] = bb.float
        }
        return offset + encodedSize(format, count)
    }

    /** Decode straight from a positioned buffer (the app's read path). */
    fun decode(bb: ByteBuffer, count: Int, format: Int, out: FloatArray) {
        if (format == DriverProto.IQ_FORMAT_BFP8) {
            if (count == 0) return
            val scale = bb.float
            for (i in 0 until count) out[i] = bb.get() * scale / S8_SCALE
            return
        }
        when (format) {
            DriverProto.IQ_FORMAT_S16 ->
                for (i in 0 until count) out[i] = bb.short / S16_SCALE
            DriverProto.IQ_FORMAT_S8 ->
                for (i in 0 until count) out[i] = bb.get() / S8_SCALE
            else ->
                for (i in 0 until count) out[i] = bb.float
        }
    }

    /**
     * Block floating point: normalise by the block's own peak, then spend all
     * eight bits on it.
     *
     * The scale goes on the wire so the decoder can undo it exactly. Peaks
     * are found before anything is written, which is why this cannot be a
     * streaming encoder — but a block is a few thousand samples and the pass
     * is a compare.
     */
    private fun encodeBfp8(iq: FloatArray, count: Int, out: ByteArray, offset: Int): Int {
        if (count == 0) return offset
        var peak = 0f
        for (i in 0 until count) {
            val a = kotlin.math.abs(iq[i])
            // NaN would make every comparison false and leave peak at 0,
            // turning the whole block into silence rather than one bad sample.
            if (a.isNaN()) continue
            if (a > peak) peak = a
        }
        val scale = if (peak < MIN_BFP_SCALE) MIN_BFP_SCALE else peak
        ByteBuffer.wrap(out, offset, BFP_HEADER_BYTES).putFloat(scale)
        var p = offset + BFP_HEADER_BYTES
        val inv = S8_SCALE / scale
        for (i in 0 until count) {
            val v = iq[i]
            val scaled = if (v.isNaN()) 0 else Math.round(v * inv)
            out[p++] = scaled.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
        }
        return p
    }

    // Clamp, never wrap: a driver overshooting [-1,1] by a hair would
    // otherwise flip the sign of the loudest sample in the block, which reads
    // as a click on peaks rather than as clipping.
    private fun toS16(v: Float): Short {
        val scaled = Math.round(v * S16_SCALE)
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun toS8(v: Float): Byte {
        val scaled = Math.round(v * S8_SCALE)
        return scaled.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
    }
}
