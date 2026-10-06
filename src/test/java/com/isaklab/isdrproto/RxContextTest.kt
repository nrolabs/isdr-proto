package com.isaklab.isdrproto

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RxContextTest {
    private fun payload(active: Int, mask: Int, hz: Long) =
        ByteBuffer.allocate(32).putInt(active).putInt(mask).putInt(192_000).putInt(2).putLong(7_100_000).putLong(hz).apply { flip() }

    @Test fun activeContextDoesNotAliasRx0Frequency() {
        assertEquals(RxContext(1, 1, 192_000, listOf(7_100_000L, 14_074_000L)), RxContext.decode(payload(1, 1, 14_074_000)))
    }

    @Test fun malformedContextCannotReconfigureTheStation() {
        for ((active, mask, hz) in listOf(
            Triple(-1, 0, 1L), Triple(8, 0, 1L), Triple(0, -1, 1L),
            Triple(0, 256, 1L), Triple(1, 2, 1L), Triple(0, 0, 0L),
        )) assertNull(RxContext.decode(payload(active, mask, hz)))
        for (size in 0..33) {
            if (size != 32) assertNull(RxContext.decode(ByteBuffer.allocate(size)))
        }
    }
}
