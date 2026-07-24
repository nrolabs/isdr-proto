/*
 * isdr-proto - iSDR driver wire protocol
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 * Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
 * application; all other uses require a separate license from the copyright
 * holder. See LICENSE at the root of this module.
 */
package com.isaklab.isdrproto

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShmRingTest {

    private fun ring(nSlots: Int = 4, slotBytes: Int = 1024): Pair<ShmRing, ShmRing> {
        // Producer and consumer views over the SAME backing memory, exactly
        // like the two processes sharing one ashmem mapping.
        val mem = ByteBuffer.allocate(ShmRing.totalBytes(nSlots, slotBytes))
        val producer = ShmRing.create(mem, nSlots, slotBytes)
        val consumer = ShmRing.attach(mem.duplicate())!!
        return producer to consumer
    }

    @Test
    fun `publish and consume EV_DATA round-trips fft and iq exactly`() {
        val (p, c) = ring()
        val fft = FloatArray(16) { it * 0.5f }
        val iq = FloatArray(64) { -1f + it / 32f }
        val seq = p.tryPublish(DriverProto.EV_DATA, fft.size, fft, fft.size, iq, iq.size)
        assertEquals(0L, seq)

        assertEquals(DriverProto.EV_DATA, c.slotType(seq))
        val nFft = c.slotA(seq)
        val nIq = c.slotNiq(seq)
        assertEquals(16, nFft)
        assertEquals(64, nIq)
        assertTrue(c.slotSane(seq, nFft, nIq))
        val gotFft = FloatArray(nFft); c.readFloats(seq, 0, gotFft, nFft)
        val gotIq = FloatArray(nIq); c.readFloats(seq, nFft, gotIq, nIq)
        assertTrue(fft.contentEquals(gotFft))
        assertTrue(iq.contentEquals(gotIq))
        // Zero NaN, by construction and by check.
        assertTrue(gotIq.none { it.isNaN() } && gotFft.none { it.isNaN() })
        c.commitRead(seq)
    }

    @Test
    fun `publish EV_DATA_RX carries rx index with empty fft`() {
        val (p, c) = ring()
        val iq = FloatArray(32) { it.toFloat() }
        val seq = p.tryPublish(DriverProto.EV_DATA_RX, 3, FloatArray(0), 0, iq, iq.size)
        assertEquals(0L, seq)
        assertEquals(DriverProto.EV_DATA_RX, c.slotType(seq))
        assertEquals(3, c.slotA(seq))
        val got = FloatArray(c.slotNiq(seq)); c.readFloats(seq, 0, got, got.size)
        assertTrue(iq.contentEquals(got))
    }

    @Test
    fun `full ring drops newest and counts it - never overwrites unread slots`() {
        val (p, c) = ring(nSlots = 2)
        val iq = FloatArray(8)
        assertEquals(0L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 8))
        assertEquals(1L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 8))
        // Consumer idle: third publish must drop, not overwrite slot 0.
        assertEquals(-1L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 8))
        assertEquals(1L, p.drops())
        // Consumer releases one slot; publishing resumes.
        c.commitRead(0L)
        assertEquals(2L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 8))
    }

    @Test
    fun `oversized frame returns -2 so the caller falls back to TCP`() {
        val (p, _) = ring(slotBytes = 64)
        val iq = FloatArray(1000)
        assertEquals(-2L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, iq.size))
        assertEquals(0L, p.drops())   // a fallback is not a drop
    }

    @Test
    fun `commitRead past skipped seqs never wedges the ring`() {
        val (p, c) = ring(nSlots = 2)
        val iq = FloatArray(4)
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)   // seq 0
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)   // seq 1
        // Consumer saw only seq 1 (notify for 0 lost to backpressure):
        // committing 1 releases 0 and 1 both.
        c.commitRead(1L)
        assertEquals(2L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4))
        assertEquals(3L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4))
        // Stale (smaller) commit must not move readIdx backwards.
        c.commitRead(0L)
        assertEquals(-1L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4))
    }

    @Test
    fun `attach rejects wrong magic, version and truncated mappings`() {
        val good = ByteBuffer.allocate(ShmRing.totalBytes(2, 128))
        ShmRing.create(good, 2, 128)
        assertNotNull(ShmRing.attach(good.duplicate()))

        val badMagic = ByteBuffer.allocate(ShmRing.totalBytes(2, 128))
        ShmRing.create(badMagic, 2, 128)
        badMagic.putInt(0, 0xBAD)
        assertNull(ShmRing.attach(badMagic))

        val badVersion = ByteBuffer.allocate(ShmRing.totalBytes(2, 128))
        ShmRing.create(badVersion, 2, 128)
        badVersion.putInt(4, 99)
        assertNull(ShmRing.attach(badVersion))

        assertNull(ShmRing.attach(ByteBuffer.allocate(16)))

        // Header claims more slots than the mapping holds.
        val truncated = ByteBuffer.allocate(ShmRing.totalBytes(2, 128))
        ShmRing.create(truncated, 2, 128)
        truncated.putInt(8, 1000)
        assertNull(ShmRing.attach(truncated))
    }

    @Test
    fun `slotSane rejects corrupt counts`() {
        val (p, c) = ring(slotBytes = 128)
        p.tryPublish(DriverProto.EV_DATA, 4, FloatArray(4), 4, FloatArray(8), 8)
        assertTrue(c.slotSane(0, 4, 8))
        assertTrue(!c.slotSane(0, -1, 8))
        assertTrue(!c.slotSane(0, 4, Int.MAX_VALUE))
    }

    @Test
    fun `slot stamp validates the seq actually written - stale slot detected`() {
        val (p, c) = ring(nSlots = 2)
        val iq = FloatArray(4)
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)   // seq 0
        assertTrue(c.slotStampOk(0L))
        c.commitRead(0L)
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)   // seq 1
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)   // seq 2 recycles slot 0
        c.commitRead(2L)
        // Seq 0's slot now carries seq 2's stamp — reading 0 must be refused.
        assertTrue(!c.slotStampOk(0L))
        assertTrue(c.slotStampOk(2L))
        // A never-written slot (zero-filled mapping) fails for nonzero seqs.
        assertTrue(!c.slotStampOk(3L))
    }

    @Test
    fun `implausible readIdx ahead of writeIdx drops instead of overwriting`() {
        val nSlots = 2; val slotBytes = 1024
        val mem = ByteBuffer.allocate(ShmRing.totalBytes(nSlots, slotBytes))
        val p = ShmRing.create(mem, nSlots, slotBytes)
        // Simulate a torn cross-process read: readIdx appears AHEAD of writeIdx.
        mem.putLong(24, 5L)
        assertEquals(-1L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, FloatArray(4), 4))
        assertEquals(1L, p.drops())
    }

    @Test
    fun `resetForAttach releases a dead consumer's backlog so publishing resumes`() {
        val (p, _) = ring(nSlots = 2)
        val iq = FloatArray(4)
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)
        p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4)
        assertEquals(-1L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4))
        p.resetForAttach()
        assertEquals(2L, p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 4))
    }

    @Test
    fun `sequences wrap slots and preserve order under steady flow`() {
        val (p, c) = ring(nSlots = 3, slotBytes = 256)
        for (i in 0 until 50) {
            val iq = FloatArray(10) { (i * 10 + it).toFloat() }
            val seq = p.tryPublish(DriverProto.EV_DATA, 0, FloatArray(0), 0, iq, 10)
            assertEquals(i.toLong(), seq)
            val got = FloatArray(c.slotNiq(seq)); c.readFloats(seq, 0, got, got.size)
            assertTrue(iq.contentEquals(got))
            c.commitRead(seq)
        }
        assertEquals(0L, p.drops())
    }
}
