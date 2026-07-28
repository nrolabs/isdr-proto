/*
 * isdr-proto - iSDR driver wire protocol
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 * Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
 * application; all other uses require a separate license from the copyright
 * holder. See LICENSE at the root of this module.
 */
package com.isaklab.isdrproto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Represents a single decoded wire frame within the iSDR protocol.
 * 
 * Frames are the fundamental unit of communication between the application and the driver host.
 * Each frame consists of an operational code ([op]) identifying the command or event, 
 * and a memory-mapped [payload] buffer positioned at offset 0 for immediate reading.
 */
class Frame(val op: Int, val payload: ByteBuffer)

/**
 * Handles blocking, serialized I/O for iSDR protocol frames over a socket stream pair.
 *
 * Why manual buffering and serialization? The radio streams megabytes of data per second. 
 * Naive frame allocations would trigger constant GC pauses, manifesting as audio stutter. 
 * [Frames] mitigates this by maintaining reusable scratch buffers (`scratch`, `readBuf`) 
 * and protecting socket writes with a lock (`writeLock`). This allows safe concurrent use 
 * from DSP callbacks and UI command threads without allocating excessive garbage or locking the read path.
 */
class Frames(
    private val input: DataInputStream,
    private val output: DataOutputStream,
) {
    companion object {
        /** Upper bound sanity check: no legal frame carries more than this. */
        const val MAX_PAYLOAD = 64 * 1024 * 1024
    }

    private val writeLock = Any()

    /**
     * Scratch buffer reused for the hot EV_DATA path so a 2+ MSps stream does
     * not allocate megabytes of garbage per second (GC pauses read as audio
     * stutter). Guarded by [writeLock]; grown on demand, never shrunk.
     */
    private var scratch = ByteBuffer.allocate(1024 * 1024)

    private fun scratch(cap: Int): ByteBuffer {
        if (scratch.capacity() < cap) scratch = ByteBuffer.allocate(cap)
        scratch.clear()
        return scratch
    }

    /** Reused backing store for [read]; grown on demand, never shrunk. */
    private var readBuf = ByteArray(1024 * 1024)

    /**
     * Read the next frame; null on orderly EOF. The returned payload is backed
     * by a buffer REUSED by the next [read] call — consume (or copy out of) it
     * before reading again. Every consumer is a single sequential read loop,
     * so this drops the per-frame megabyte-scale allocations that had the GC
     * running once a second at MSps rates.
     */
    @Throws(IOException::class)
    /**
     * Read the next frame; null on orderly EOF. [maxLen] caps this read's
     * payload (default [MAX_PAYLOAD]) — a session uses a tight bound before
     * authentication so an unauthenticated peer cannot force a huge
     * allocation, then relaxes it once trusted.
     */
    fun read(maxLen: Int = MAX_PAYLOAD): Frame? {
        val op = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val len = input.readInt()
        if (len < 0 || len > maxLen) throw IOException("bad frame length $len (max $maxLen)")
        if (readBuf.size < len) readBuf = ByteArray(len)
        input.readFully(readBuf, 0, len)
        return Frame(op, ByteBuffer.wrap(readBuf, 0, len))
    }

    @Throws(IOException::class)
    fun write(op: Int, payload: ByteBuffer) {
        synchronized(writeLock) { writeLocked(op, payload) }
    }

    private fun writeLocked(op: Int, payload: ByteBuffer) {
        output.writeByte(op)
        output.writeInt(payload.remaining())
        output.write(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining())
        output.flush()
    }

    fun write(op: Int) = write(op, ByteBuffer.allocate(0))

    /**
     * Write a frame whose payload was pre-encoded into [payload]`[0..len)`.
     * Lets a host queue fully-built data frames off the socket thread
     * (per-session backpressure) and drain them here without re-encoding.
     */
    fun writeRaw(op: Int, payload: ByteArray, len: Int) {
        synchronized(writeLock) {
            output.writeByte(op)
            output.writeInt(len)
            output.write(payload, 0, len)
            output.flush()
        }
    }

    fun writeBool(op: Int, v: Boolean) {
        val bb = ByteBuffer.allocate(1)
        bb.put(if (v) 1.toByte() else 0.toByte())
        bb.flip()
        write(op, bb)
    }

    fun writeI32(op: Int, v: Int) {
        val bb = ByteBuffer.allocate(4)
        bb.putInt(v)
        bb.flip()
        write(op, bb)
    }

    fun writeI64(op: Int, v: Long) {
        val bb = ByteBuffer.allocate(8)
        bb.putLong(v)
        bb.flip()
        write(op, bb)
    }

    fun writeFloats(op: Int, v: FloatArray) {
        val bb = ByteBuffer.allocate(4 + v.size * 4)
        bb.putInt(v.size)
        for (f in v) bb.putFloat(f)
        bb.flip()
        write(op, bb)
    }

    /** EV_DATA: spectrum + interleaved IQ in one frame. */
    fun writeData(fft: FloatArray, iq: FloatArray) {
        synchronized(writeLock) {
            val bb = scratch(8 + (fft.size + iq.size) * 4)
            bb.putInt(fft.size)
            bb.asFloatBuffer().put(fft)          // bulk copy, no per-sample loop
            bb.position(bb.position() + fft.size * 4)
            bb.putInt(iq.size)
            bb.asFloatBuffer().put(iq)
            bb.position(bb.position() + iq.size * 4)
            bb.flip()
            writeLocked(DriverProto.EV_DATA, bb)
        }
    }

    /** EV_DATA_RX: one receiver's IQ block, tagged with its index. */
    fun writeDataRx(rx: Int, iq: FloatArray) {
        synchronized(writeLock) {
            val bb = scratch(8 + iq.size * 4)
            bb.putInt(rx)
            bb.putInt(iq.size)
            bb.asFloatBuffer().put(iq)
            bb.position(bb.position() + iq.size * 4)
            bb.flip()
            writeLocked(DriverProto.EV_DATA_RX, bb)
        }
    }

    /** EV_HELLO with the trailing host feature bits (additive; old apps read only the version). */
    fun writeHello(version: Int, features: Int) {
        val bb = ByteBuffer.allocate(8)
        bb.putInt(version)
        bb.putInt(features)
        bb.flip()
        write(DriverProto.EV_HELLO, bb)
    }

    /** EV_SWEEP_BLOCK: frequency-tagged IQ block. */
    fun writeSweepBlock(lowerEdgeHz: Long, iq: FloatArray) {
        synchronized(writeLock) {
            val bb = scratch(12 + iq.size * 4)
            bb.putLong(lowerEdgeHz)
            bb.putInt(iq.size)
            bb.asFloatBuffer().put(iq)
            bb.position(bb.position() + iq.size * 4)
            bb.flip()
            writeLocked(DriverProto.EV_SWEEP_BLOCK, bb)
        }
    }

    /** EV_STATUS: connection flag + human-readable status line. */
    fun writeStatus(connected: Boolean, status: String) {
        val utf = status.toByteArray(Charsets.UTF_8)
        val bb = ByteBuffer.allocate(3 + utf.size)
        bb.put(if (connected) 1.toByte() else 0.toByte())
        bb.putShort(utf.size.toShort())
        bb.put(utf)
        bb.flip()
        write(DriverProto.EV_STATUS, bb)
    }

    fun writeTelemetry(t: RadioTelemetry) {
        // u16 flags + u8 state bits + six f64 channels + i64 rxGaps +
        // i64 linkDrops. Grown append-only: a reader stops at what it knows —
        // the frame is length-prefixed, so trailing bytes are skipped and
        // unknown flags stay meaningless to it.
        val bb = ByteBuffer.allocate(2 + 1 + 6 * 8 + 2 * 8)
        bb.putShort(t.flags().toShort())
        bb.put(t.stateBits().toByte())
        bb.putDouble(t.temperatureC)
        bb.putDouble(t.paCurrentA)
        bb.putDouble(t.forwardPower)
        bb.putDouble(t.reversePower)
        bb.putDouble(t.supplyVolts)
        bb.putDouble(t.exciterPower)
        bb.putLong(t.rxGaps)
        bb.putLong(t.linkDrops)
        bb.flip()
        write(DriverProto.EV_TELEMETRY, bb)
    }

    /** CMD_OPEN payload. */
    fun writeOpen(kind: Int, host: String, port: Int, flags: Int) {
        val utf = host.toByteArray(Charsets.UTF_8)
        val bb = ByteBuffer.allocate(1 + 2 + utf.size + 8)
        bb.put(kind.toByte())
        bb.putShort(utf.size.toShort())
        bb.put(utf)
        bb.putInt(port)
        bb.putInt(flags)
        bb.flip()
        write(DriverProto.CMD_OPEN, bb)
    }
}

// ---- payload readers (shared by both sides) ----

fun ByteBuffer.getBool(): Boolean = get().toInt() != 0

fun ByteBuffer.getUtf(): String {
    val len = short.toInt() and 0xFFFF
    // A forged length must not over-read: clamp to what the frame actually holds.
    if (len > remaining()) throw java.io.IOException("utf length $len > ${remaining()}")
    val bytes = ByteArray(len)
    get(bytes)
    return String(bytes, Charsets.UTF_8)
}

fun ByteBuffer.getFloats(): FloatArray {
    val n = int
    // Reject negative (NegativeArraySizeException) and oversized (OOM) counts
    // from a corrupt or hostile peer — n*4 bytes must be present in the frame.
    if (n < 0 || n.toLong() * 4 > remaining()) {
        throw java.io.IOException("float count $n invalid for ${remaining()} bytes")
    }
    val out = FloatArray(n)
    asFloatBuffer().get(out)                 // bulk copy, no per-sample loop
    position(position() + n * 4)
    return out
}

fun ByteBuffer.getTelemetry(): RadioTelemetry {
    val flags = short.toInt() and 0xFFFF
    val state = get().toInt() and 0xFF
    return RadioTelemetry.fromWire(
        flags, state,
        tempC = double, paA = double, fwd = double, rev = double, volts = double,
        exciter = double,
        // append-only tail — absent when the writer predates these fields.
        rxGaps = if (remaining() >= 8) long else 0L,
        linkDrops = if (remaining() >= 8) long else 0L,
    )
}
