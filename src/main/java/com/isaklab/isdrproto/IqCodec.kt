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

    /** Bytes [count] samples occupy in [format]. */
    fun encodedSize(format: Int, count: Int): Int =
        count * DriverProto.iqSampleBytes(format)

    /**
     * Write [count] samples of [iq] into [out] at [offset] in [format].
     * @return the offset just past the written bytes.
     */
    fun encode(iq: FloatArray, count: Int, format: Int, out: ByteArray, offset: Int): Int {
        // Big-endian, like every other field in DriverProto — the existing
        // float32 payload already ships this way and must keep its meaning.
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
        when (format) {
            DriverProto.IQ_FORMAT_S16 ->
                for (i in 0 until count) out[i] = bb.short / S16_SCALE
            DriverProto.IQ_FORMAT_S8 ->
                for (i in 0 until count) out[i] = bb.get() / S8_SCALE
            else ->
                for (i in 0 until count) out[i] = bb.float
        }
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
