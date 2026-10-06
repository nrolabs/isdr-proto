package com.isaklab.isdrproto

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class StreamGeometryTest {
    private fun media(epoch: Long, width: Int, centre: Long, decimation: Int): ByteArray =
        ByteBuffer.allocate(24).putLong(epoch).putInt(width).putLong(centre).putInt(decimation).array()

    @Test fun mediaAndControlCarryTheSameGeometry() {
        val bytes = media(9, 48_000, 14_074_000, 4)
        val expected = StreamGeometry(9, 48_000, 14_074_000, 4)
        val payload = ByteBuffer.wrap(bytes + byteArrayOf(1, 2, 3))
        assertEquals(expected, StreamGeometry.decodeMediaHeader(payload))
        assertEquals(3, payload.remaining())
        val announcement = bytes.copyOfRange(8, 24) + bytes.copyOfRange(0, 8)
        assertEquals(expected, StreamGeometry.decodeAnnouncement(ByteBuffer.wrap(announcement)))
        assertNull(StreamGeometry.decodeAnnouncement(ByteBuffer.wrap(announcement + byteArrayOf(0))))
    }

    @Test fun malformedGeometryCannotReconfigureAConsumer() {
        for (bytes in listOf(media(0, 48_000, 14_074_000, 4), media(1, -1, 14_074_000, 4),
            media(1, 48_000, 0, 4), media(1, 48_000, 14_074_000, 5), media(1, 0, 0, 1))) {
            assertNull(StreamGeometry.decodeMediaHeader(ByteBuffer.wrap(bytes)))
        }
        val good = media(1, 48_000, 14_074_000, 4)
        for (length in 0 until 24) {
            assertNull(StreamGeometry.decodeMediaHeader(ByteBuffer.wrap(good.copyOf(length))))
        }
        val off = media(2, 0, 0, 1)
        val announcement = off.copyOfRange(8, 24) + off.copyOfRange(0, 8)
        assertEquals(0, StreamGeometry.decodeAnnouncement(ByteBuffer.wrap(announcement))!!.widthHz)
    }
}
