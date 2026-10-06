package com.isaklab.isdrproto

import java.nio.ByteBuffer

/** Active hardware context for station channelisation, independent of RX0 tuning. */
data class RxContext(
    val active: Int,
    val streamMask: Int,
    val sampleRateHz: Int,
    val frequenciesHz: List<Long>,
) {
    companion object {
        fun decode(payload: ByteBuffer): RxContext? {
            if (payload.remaining() < 16) return null
            val active = payload.int
            val mask = payload.int
            val rate = payload.int
            val count = payload.int
            if (count !in 1..8 || active !in 0 until count || rate <= 0 || mask < 0 ||
                mask and ((1 shl count) - 1).inv() != 0 || mask and (1 shl active) != 0 ||
                payload.remaining() != count * 8
            ) return null
            val frequencies = List(count) { payload.long }
            if (frequencies.any { it <= 0L }) return null
            return RxContext(active, mask, rate, frequencies)
        }
    }
}
