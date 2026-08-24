/*
 * isdr-proto - iSDR driver wire protocol
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 */
package com.isaklab.isdrproto

/**
 * Exact value grammar for the four receiver-control wire commands.
 *
 * The active DDC is always delivered by EV_DATA.  [streamMask] therefore
 * names only additional coherent streams (diversity members or an explicit
 * PureSignal feedback claim) and must never contain the active/reference DDC.
 * Returning an error instead of normalising a value lets both ends refuse the
 * same request rather than silently applying a different topology.
 */
object ReceiverWireContract {
    /** Protocol-2 exposes seven DDCs; individual radio profiles may expose fewer. */
    const val MAX_RECEIVERS = 7

    fun stateError(count: Int, activeReceiver: Int, streamMask: Int): String? =
        countError(activeReceiver, streamMask, count)

    fun countError(activeReceiver: Int, streamMask: Int, requestedCount: Int): String? {
        if (requestedCount !in 1..MAX_RECEIVERS) {
            return "receiver count $requestedCount is outside 1..$MAX_RECEIVERS"
        }
        if (activeReceiver !in 0 until requestedCount) {
            return "active receiver $activeReceiver is outside count $requestedCount"
        }
        return streamMaskError(requestedCount, activeReceiver, streamMask)
    }

    fun activeReceiverError(count: Int, streamMask: Int, requestedReceiver: Int): String? {
        receiverIndexError(count, requestedReceiver)?.let { return it }
        if (streamMask and (1 shl requestedReceiver) != 0) {
            return "active receiver $requestedReceiver is already an additional stream"
        }
        return null
    }

    fun receiverIndexError(count: Int, receiver: Int): String? {
        if (count !in 1..MAX_RECEIVERS) {
            return "current receiver count $count is outside 1..$MAX_RECEIVERS"
        }
        return if (receiver in 0 until count) null
        else "receiver $receiver is outside configured count $count"
    }

    fun streamMaskError(count: Int, activeReceiver: Int, streamMask: Int): String? {
        receiverIndexError(count, activeReceiver)?.let { return it }
        val allowed = (1 shl count) - 1
        if (streamMask < 0 || streamMask and allowed.inv() != 0) {
            return "receiver stream mask 0x${streamMask.toString(16)} exceeds 0x${allowed.toString(16)}"
        }
        if (streamMask and (1 shl activeReceiver) != 0) {
            return "receiver stream mask contains active/reference receiver $activeReceiver"
        }
        return null
    }
}
