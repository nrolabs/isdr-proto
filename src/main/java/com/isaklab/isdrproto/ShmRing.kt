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

/**
 * Shared-memory IQ ring — the zero-copy fast path for the loopback data
 * plane (FEAT_SHM_RING). The driver host places EV_DATA / EV_DATA_RX
 * payloads in an ashmem ([android.os.SharedMemory]) ring and sends only a
 * tiny EV_SHM_FRAME notification over TCP; the app copies each slot straight
 * into its pooled FloatArrays. One producer (the driver's writer path), one
 * consumer (the app's read loop).
 *
 * Synchronization model — deliberately fence-free:
 *  - The producer fully writes a slot BEFORE enqueueing its EV_SHM_FRAME
 *    notification; the TCP send/receive syscall pair is the happens-before
 *    edge, so a notified slot is always fully visible to the consumer.
 *  - The producer NEVER overwrites an unconsumed slot: when the ring is
 *    full (writeIdx - readIdx >= nSlots) the new frame is dropped and
 *    counted, exactly like the TCP backpressure queue. The consumer can
 *    therefore never observe a torn slot.
 *  - The EV_SHM_FRAME notification is a WATERMARK (the published seq): the
 *    consumer drains every uncommitted slot up to it. Notifications ride the
 *    droppable outbound queue and CAN be dropped under load — draining to
 *    the watermark makes that harmless (the next notify carries a higher
 *    head) and a duplicate/stale watermark re-reads nothing (seq already
 *    past). writeIdx only advances on a real publish, so seq is contiguous.
 *  - The 64-bit header indices are written with plain (non-atomic) stores,
 *    so a CROSS-PROCESS read of the OTHER side's index can be torn on
 *    32-bit CPUs. Every such read is therefore only ever used defensively:
 *    the producer treats an implausible readIdx as "ring full" (drop, never
 *    overwrite), and the consumer never reads writeIdx at all — it anchors
 *    on the first notified head. The per-slot seq stamp is the final guard:
 *    a stamp that doesn't match the expected seq means the slot was
 *    recycled, and the consumer skips it instead of delivering stale data.
 *
 * Layout (big-endian, all offsets absolute):
 * ```
 * header: 0 magic 'iSHR', 4 version, 8 nSlots, 12 slotBytes,
 *         16 writeIdx i64 (producer), 24 readIdx i64 (consumer),
 *         32 drops i64 (producer), 40..63 reserved
 * slot:   0 type i32 (EV_DATA | EV_DATA_RX), 4 a i32 (nFft | rx index),
 *         8 nIq i32, 12 reserved, 16 payload f32[] (fft then iq for EV_DATA)
 * ```
 * All accesses are absolute (index-based) — the two processes share the
 * mapping but never a ByteBuffer position.
 */
class ShmRing private constructor(private val buf: ByteBuffer) {
    companion object {
        const val MAGIC = 0x69534852            // "iSHR"
        const val LAYOUT_VERSION = 1
        const val HDR_BYTES = 64
        const val SLOT_HDR_BYTES = 16

        /** Default geometry: 64 x 512 KiB slots = 32 MiB (~1 s at 16 MSps,
         *  seconds at RTL rates). 512 KiB fits a full high-rate USB block
         *  (256 KiB of IQ) plus the slot header — at exactly 256 KiB every
         *  frame spilled to the TCP fallback. Generous depth so a scheduling
         *  hiccup on the consumer is absorbed, never dropped (field: buzz was
         *  ring starvation, not the copy). */
        const val DEFAULT_SLOTS = 64
        const val DEFAULT_SLOT_BYTES = 512 * 1024

        fun totalBytes(nSlots: Int, slotBytes: Int) = HDR_BYTES + nSlots * slotBytes

        /** Producer side: stamp a fresh mapping and return the ring. */
        fun create(buf: ByteBuffer, nSlots: Int, slotBytes: Int): ShmRing {
            require(buf.capacity() >= totalBytes(nSlots, slotBytes)) { "mapping too small" }
            // Native order: the ring never leaves this machine, and bulk
            // float copies on a direct mapping only compile to memcpy when
            // the buffer order matches the CPU (big-endian views byte-swap
            // per element — measured as a whole CPU point at 2.4 MSps).
            buf.order(java.nio.ByteOrder.nativeOrder())
            buf.putInt(0, MAGIC)
            buf.putInt(4, LAYOUT_VERSION)
            buf.putInt(8, nSlots)
            buf.putInt(12, slotBytes)
            buf.putLong(16, 0L)
            buf.putLong(24, 0L)
            buf.putLong(32, 0L)
            return ShmRing(buf)
        }

        /** Consumer side: validate a received mapping; null if incompatible. */
        fun attach(buf: ByteBuffer): ShmRing? {
            buf.order(java.nio.ByteOrder.nativeOrder())   // see create()
            if (buf.capacity() < HDR_BYTES) return null
            if (buf.getInt(0) != MAGIC || buf.getInt(4) != LAYOUT_VERSION) return null
            val nSlots = buf.getInt(8)
            val slotBytes = buf.getInt(12)
            if (nSlots <= 0 || slotBytes <= SLOT_HDR_BYTES) return null
            if (buf.capacity() < totalBytes(nSlots, slotBytes)) return null
            return ShmRing(buf)
        }
    }

    val nSlots: Int = buf.getInt(8)
    val slotBytes: Int = buf.getInt(12)

    private fun slotOff(seq: Long): Int =
        HDR_BYTES + ((seq % nSlots).toInt()) * slotBytes

    // ---- producer ----

    /** Frames dropped because the consumer lagged (ring full). */
    fun drops(): Long = buf.getLong(32)

    /** Producer side, on CMD_SHM_ATTACH: mark everything read. The ring
     *  persists across app reconnects — if the previous consumer died with
     *  the ring full, readIdx stays lagged and every publish would drop
     *  forever (no publish → no notify → the new consumer never anchors).
     *  Both indices live in the producer process here, so no torn read. */
    fun resetForAttach() {
        buf.putLong(24, buf.getLong(16))
    }

    /**
     * Publish one data frame: [type] EV_DATA (a = fft.size, payload fft++iq)
     * or EV_DATA_RX (a = rx index, fft empty). [tagBits] is the flush-seq
     * tag (FEAT_SEQ_TAG) written as one raw-bits float after the iq — old
     * consumers never read past nFft+nIq, so it is invisible to them.
     * Returns the sequence number to notify, or -1 if the ring is full
     * (frame dropped, counter bumped), or -2 if the frame exceeds the slot
     * payload (caller falls back to TCP).
     */
    fun tryPublish(type: Int, a: Int, fft: FloatArray, nFft: Int, iq: FloatArray, nIq: Int, tagBits: Int = 0): Long {
        val need = SLOT_HDR_BYTES + (nFft + nIq + 1) * 4
        if (need > slotBytes) return -2L
        val w = buf.getLong(16)
        // readIdx is written by the OTHER process with plain stores — the
        // read here can be torn on 32-bit CPUs. Any implausible value
        // (ahead of the writer, or lagging beyond the depth) is treated as
        // "full": we err toward dropping one frame, NEVER toward
        // overwriting a slot the consumer may still be reading.
        val r = buf.getLong(24)
        if (r > w || w - r >= nSlots) {               // lagged or torn: drop-newest
            buf.putLong(32, buf.getLong(32) + 1)
            return -1L
        }
        val off = slotOff(w)
        buf.putInt(off, type)
        buf.putInt(off + 4, a)
        buf.putInt(off + 8, nIq)
        buf.putInt(off + 12, stampFor(w))   // slot seq stamp (see slotStampOk)
        // duplicate() resets byte order — restore native before the view.
        val fb = buf.duplicate().also {
            it.order(java.nio.ByteOrder.nativeOrder())
            it.position(off + SLOT_HDR_BYTES)
        }.asFloatBuffer()
        fb.put(fft, 0, nFft)
        fb.put(iq, 0, nIq)
        buf.putInt(off + SLOT_HDR_BYTES + (nFft + nIq) * 4, tagBits)
        buf.putLong(16, w + 1)
        return w
    }

    // ---- consumer ----
    //
    // NOTE: there is deliberately no writeIndex() accessor. writeIdx is
    // written by the producer process with plain 64-bit stores and a read
    // from the consumer can be torn on 32-bit CPUs — anchoring on it once
    // produced a garbage start seq in the field. The consumer anchors on
    // the first EV_SHM_FRAME head instead, which arrives through a syscall
    // and is therefore always a real published seq.

    fun slotType(seq: Long): Int = buf.getInt(slotOff(seq))
    fun slotA(seq: Long): Int = buf.getInt(slotOff(seq) + 4)
    fun slotNiq(seq: Long): Int = buf.getInt(slotOff(seq) + 8)
    fun slotStamp(seq: Long): Int = buf.getInt(slotOff(seq) + 12)

    private fun stampFor(seq: Long): Int = (seq and 0x7fffffff).toInt()

    /** True when the slot still carries the stamp written for [seq]. False
     *  means the producer has since recycled the slot (or it was never
     *  written) — the consumer must skip it rather than deliver stale data. */
    fun slotStampOk(seq: Long): Boolean = slotStamp(seq) == stampFor(seq)

    /** True when the slot's declared sizes fit the slot — a corrupt header must not over-read. */
    fun slotSane(seq: Long, nFft: Int, nIq: Int): Boolean =
        nFft >= 0 && nIq >= 0 &&
            SLOT_HDR_BYTES + (nFft.toLong() + nIq.toLong()) * 4 <= slotBytes

    /** Flush-seq tag written after the payload (FEAT_SEQ_TAG hosts only). */
    fun slotTag(seq: Long, nFft: Int, nIq: Int): Int =
        buf.getInt(slotOff(seq) + SLOT_HDR_BYTES + (nFft + nIq) * 4)

    /** Copy [n] floats out of the slot payload, skipping [skipFloats] floats first. */
    fun readFloats(seq: Long, skipFloats: Int, dst: FloatArray, n: Int) {
        val fb = buf.duplicate()
            .also {
                it.order(java.nio.ByteOrder.nativeOrder())   // duplicate() resets order
                it.position(slotOff(seq) + SLOT_HDR_BYTES + skipFloats * 4)
            }
            .asFloatBuffer()
        fb.get(dst, 0, n)
    }

    /** Release everything up to and including [seq] back to the producer. */
    fun commitRead(seq: Long) {
        if (seq + 1 > buf.getLong(24)) buf.putLong(24, seq + 1)
    }
}
