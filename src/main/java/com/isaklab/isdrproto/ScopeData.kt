package com.isaklab.isdrproto

import java.nio.ByteBuffer

/** A complete physical scope sweep. Geometry and freshness never depend on VFO readbacks. */
data class ScopeData(
    val sequence: Long,
    val lowHz: Long,
    val highHz: Long,
    val outOfRange: Boolean,
    val bins: FloatArray,
) {
    fun encode(): ByteArray {
        require(valid(sequence, lowHz, highHz, outOfRange, bins.size))
        require(bins.all { it.isFinite() })
        val bytes = ByteArray(HEADER_BYTES + SpectrumCodec.encodedSize(DriverProto.SPECTRUM_FORMAT_U8, bins.size))
        ByteBuffer.wrap(bytes).apply {
            putInt(VERSION); putLong(sequence); putLong(lowHz); putLong(highHz)
            putInt(if (outOfRange) 1 else 0); putInt(bins.size)
            // Preserve the native CI-V 0.5 dB alphabet in the same byte per
            // bin. Adaptive BFP needlessly perturbs its 161/201 exact levels.
            val floor = kotlin.math.round((bins.minOrNull() ?: 0f) * 2f) * 0.5f
            val halfDb = bins.isNotEmpty() && bins.all {
                val code = (it - floor) * 2f
                kotlin.math.round(code) in 0f..255f && kotlin.math.abs(code - kotlin.math.round(code)) <= 0.0002f
            }
            if (halfDb) {
                putFloat(floor); putFloat(0.5f)
                bins.forEach { put(kotlin.math.round((it - floor) * 2f).toInt().toByte()) }
            } else {
                SpectrumCodec.encode(bins, bins.size, DriverProto.SPECTRUM_FORMAT_U8, bytes, position())
            }
        }
        return bytes
    }

    companion object {
        const val VERSION = 1
        const val HEADER_BYTES = 36
        const val MAX_BINS = 8192

        private fun valid(sequence: Long, low: Long, high: Long, out: Boolean, count: Int): Boolean =
            sequence >= 0 && high > low && (high - low) in 1L..Int.MAX_VALUE.toLong() &&
                count in 0..MAX_BINS && (out == (count == 0))

        /** The entire payload must be one sweep; malformed/trailing data is never plotted. */
        fun decode(src: ByteBuffer): ScopeData? {
            if (src.remaining() < HEADER_BYTES || src.int != VERSION) return null
            val sequence = src.long
            val low = src.long
            val high = src.long
            val flags = src.int
            val count = src.int
            if (flags !in 0..1 || !valid(sequence, low, high, flags == 1, count)) return null
            if (src.remaining() != SpectrumCodec.encodedSize(DriverProto.SPECTRUM_FORMAT_U8, count)) return null
            if (count > 0) {
                val floor = src.getFloat(src.position())
                val step = src.getFloat(src.position() + 4)
                if (!floor.isFinite() || !step.isFinite() || step <= 0 || !(floor + step * 255).isFinite()) return null
            }
            val bins = FloatArray(count)
            SpectrumCodec.decode(src, count, DriverProto.SPECTRUM_FORMAT_U8, bins)
            return ScopeData(sequence, low, high, flags == 1, bins)
        }
    }
}
