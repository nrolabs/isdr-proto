package com.isaklab.isdrproto

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ScopeDataTest {
    @Test fun crossPlatformGoldenPreservesPartialHalfDbFloorAndSignedEdges() {
        // Same normative bytes as Rust scope_data.rs; minimum includes
        // conversion noise and is deliberately not the full radio floor.
        val hex = "00000001000000000000002affffffffffff9e5800000000000124f80000000000000004c21e00003f0000000001024f"
        val golden = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val frame = ScopeData(42, -25_000, 75_000, false, floatArrayOf(-39.500004f, -39f, -38.5f, 0f))
        assertArrayEquals(golden, frame.encode())
        val decoded = requireNotNull(ScopeData.decode(ByteBuffer.wrap(golden)))
        assertEquals(42L, decoded.sequence)
        assertEquals(-25_000L, decoded.lowHz)
        assertEquals(75_000L, decoded.highHz)
        assertArrayEquals(floatArrayOf(-39.5f, -39f, -38.5f, 0f), decoded.bins, 0f)
        assertArrayEquals(golden, decoded.encode())
    }

    @Test fun `signed edges bins and every identical sweep retain sequence`() {
        val input = ScopeData(42, -25000, 75000, false, floatArrayOf(-100f, -50f, 0f))
        val decoded = ScopeData.decode(ByteBuffer.wrap(input.encode()))!!
        assertEquals(42L, decoded.sequence)
        assertEquals(-25000L, decoded.lowHz)
        assertEquals(75000L, decoded.highHz)
        assertArrayEquals(input.bins, decoded.bins, 0.2f)
        assertEquals(43L, ScopeData.decode(ByteBuffer.wrap(input.copy(sequence = 43).encode()))!!.sequence)
    }

    @Test fun `out of range carries valid edges without inventing bins`() {
        val value = ScopeData(0, 7000000, 7100000, true, FloatArray(0))
        assertTrue(ScopeData.decode(ByteBuffer.wrap(value.encode()))!!.outOfRange)
        assertEquals(ScopeData.HEADER_BYTES, value.encode().size)
    }

    @Test fun `rejects invalid flags counts floats spans sequence and trailing bytes`() {
        val valid = ScopeData(0, -100, 100, false, floatArrayOf(-80f)).encode()
        fun invalid(offset: Int, mutate: (ByteBuffer) -> Unit) {
            val bytes = valid.copyOf(); val bb = ByteBuffer.wrap(bytes); bb.position(offset); mutate(bb)
            assertNull(ScopeData.decode(ByteBuffer.wrap(bytes)))
        }
        invalid(0) { it.putInt(2) }
        invalid(4) { it.putLong(-1) }
        invalid(12) { it.putLong(Long.MIN_VALUE) }
        invalid(20) { it.putLong(-100) }
        invalid(28) { it.putInt(1) } // out-of-range never carries old bins
        invalid(28) { it.putInt(2) }
        invalid(32) { it.putInt(-1) }
        invalid(32) { it.putInt(Int.MAX_VALUE) }
        invalid(36) { it.putFloat(Float.NaN) }
        invalid(40) { it.putFloat(-1f) }
        assertNull(ScopeData.decode(ByteBuffer.wrap(valid.copyOf(valid.size - 1))))
        assertNull(ScopeData.decode(ByteBuffer.wrap(valid + byteArrayOf(0))))
    }
    @org.junit.Test fun allNativeIcomLevelsSurviveWithoutRequantizationOrExtraBytes() {
        for (levels in listOf(160, 200)) {
            val floor = -levels / 2f
            val bins = FloatArray(levels + 1) { floor + it.toFloat() / levels * -floor }
            val frame = ScopeData(1, 7_000_000, 7_100_000, false, bins)
            val bytes = frame.encode()
            org.junit.Assert.assertEquals(ScopeData.HEADER_BYTES + 8 + levels + 1, bytes.size)
            val decoded = requireNotNull(ScopeData.decode(java.nio.ByteBuffer.wrap(bytes)))
            org.junit.Assert.assertArrayEquals(FloatArray(levels + 1) { floor + it * 0.5f }, decoded.bins, 0f)
        }
    }

}
