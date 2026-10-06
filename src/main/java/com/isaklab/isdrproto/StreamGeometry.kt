package com.isaklab.isdrproto

import java.nio.ByteBuffer

/** Each Station IQ frame identifies its geometry despite cross-channel reordering. */
data class StreamGeometry(
    val epoch: Long,
    val widthHz: Int,
    val centerHz: Long,
    val decimation: Int,
) {
    private fun valid(allowOff: Boolean): Boolean = epoch > 0 && if (widthHz == 0) {
        allowOff && centerHz == 0L && decimation == 1
    } else {
        widthHz > 0 && centerHz > 0 &&
            (decimation == 1 || decimation in SUPPORTED_DECIMATIONS)
    }

    companion object {
        private val SUPPORTED_DECIMATIONS = intArrayOf(2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)
        const val MEDIA_HEADER_BYTES = 24
        const val ANNOUNCEMENT_BYTES = 24

        fun decodeMediaHeader(payload: ByteBuffer): StreamGeometry? {
            if (payload.remaining() < MEDIA_HEADER_BYTES) return null
            val geometry = StreamGeometry(payload.long, payload.int, payload.long, payload.int)
            return geometry.takeIf { it.valid(false) }
        }

        fun decodeAnnouncement(payload: ByteBuffer): StreamGeometry? {
            if (payload.remaining() != ANNOUNCEMENT_BYTES) return null
            val width = payload.int
            val centre = payload.long
            val decimation = payload.int
            return StreamGeometry(payload.long, width, centre, decimation).takeIf { it.valid(true) }
        }
    }
}
