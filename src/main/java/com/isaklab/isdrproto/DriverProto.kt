/*
 * isdr-proto - iSDR driver wire protocol
 *
 * Copyright (C) 2026 Isak Ruas <isakruas@gmail.com>. All rights reserved.
 * Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
 * application; all other uses require a separate license from the copyright
 * holder. See LICENSE at the root of this module.
 */
package com.isaklab.isdrproto

/**
 * Protocol constants. Every frame on the wire is
 * `[u8 opcode][i32 payloadLength][payload…]`, big-endian, written/read with
 * [Frames]. Commands flow app → driver host; events flow driver host → app. The host is
 * deliberately dumb: it moves raw IQ and hardware control only — no
 * demodulation, no decoding, no DSP beyond the display FFT the drivers
 * already compute.
 */
object DriverProto {
    /** Bump on ANY wire-visible change; both sides refuse a mismatch. */
    const val VERSION = 1

    // ---- host feature bits (EV_HELLO trailing i32; absent = 0) ----
    // Additive capability discovery: EV_HELLO grew a trailing features word
    // (old apps read only the version and skip the rest — frames are
    // length-prefixed). New opcodes are gated on these bits so a version
    // bump — which both sides refuse on mismatch — is never needed for
    // additive growth.
    /** Host can stream per-receiver IQ (CMD_SET_RX_STREAM_MASK / EV_DATA_RX). */
    const val FEAT_RX_STREAMS = 1

    /**
     * EV_DATA / EV_DATA_RX carry a trailing i32 FLUSH SEQUENCE (and shm ring
     * slots one trailing tag float, raw i32 bits, after the iq payload): all
     * blocks of one driver flush share the seq of their EV_DATA. Consumers
     * pair per-receiver blocks with the main block BY SEQ instead of
     * "arrived just before" — an EV_DATA_RX individually dropped by
     * backpressure can no longer pair a STALE rx block with a fresh main
     * block (diversity/PureSignal combined the signal with a one-block-old
     * copy of itself = literal echo). Additive: the tail is invisible to
     * old readers (frames are length-prefixed; slot reads are count-bound).
     */
    const val FEAT_SEQ_TAG = 4

    /**
     * Host can send IQ in a NARROWER wire format than float32
     * (CMD_SET_IQ_FORMAT). The sample count in EV_DATA / EV_DATA_RX is
     * unchanged; only the bytes per sample change.
     *
     * float32 was chosen so one format could carry every radio, from the
     * RTL's 8 bits to the HPSDR boards' 24. That is right for the DSP and
     * wrong for the wire: an 8-bit dongle ships 4 bytes to carry 1 byte of
     * information, and at 2.048 MS/s that is 131 Mbit/s instead of 33 —
     * the difference between a link that works over mobile data and one
     * that does not.
     *
     * Additive: a host that never sends CMD_SET_IQ_FORMAT keeps float32.
     */
    const val FEAT_IQ_FORMAT = 8

    /**
     * Host can ask for a NARROWBAND stream: IQ shifted to a chosen centre and
     * decimated to a chosen width, plus a quantised display spectrum
     * (CMD_SET_NARROWBAND).
     *
     * This is what makes remote operation possible on mobile data. The full
     * stream is ~160 Mbit/s in both directions combined; a 12 kHz window in
     * s16 is under 900 kbit/s, and the client keeps doing ALL of its own
     * demodulation, filtering and transmit processing — nothing moves except
     * the channelisation, which the client would have done anyway.
     *
     * The cost is that retuning OUTSIDE the window becomes a round trip
     * instead of a local operation. Inside it, everything stays instant.
     */
    const val FEAT_NARROWBAND = 16

    // ---- IQ wire formats (FEAT_IQ_FORMAT) ----
    //
    // Samples stay interleaved i0,q0,i1,q1,... and normalised to [-1,1) on
    // both sides; only the transport encoding narrows. Quantisation is a
    // plain scale-and-round, so a format at or above the radio's native
    // depth is LOSSLESS — the driver is undoing an expansion it performed
    // itself, not discarding signal.

    /** 4 bytes/sample. The default, and what a host gets if it stays silent. */
    const val IQ_FORMAT_F32 = 0

    /**
     * 2 bytes/sample, signed 16-bit little-endian. ~96 dB of range: lossless
     * for 8- and 16-bit front ends, and transparent in practice for the
     * 24-bit HPSDR boards outside deliberate wide-dynamic-range work.
     */
    const val IQ_FORMAT_S16 = 1

    /**
     * 1 byte/sample, signed 8-bit. LOSSLESS for RTL-SDR and HackRF, whose
     * ADCs are 8-bit; destructive on anything deeper, so a host must only
     * request it for a radio it knows is 8-bit.
     */
    const val IQ_FORMAT_S8 = 2

    /** Bytes one sample occupies on the wire in [format]. */
    fun iqSampleBytes(format: Int): Int = when (format) {
        IQ_FORMAT_S16 -> 2
        IQ_FORMAT_S8 -> 1
        else -> 4
    }

    /** True when [format] is one this build understands. */
    fun isKnownIqFormat(format: Int): Boolean =
        format == IQ_FORMAT_F32 || format == IQ_FORMAT_S16 || format == IQ_FORMAT_S8

    /** Loopback TCP port the driver service listens on. */
    const val PORT = 45733

    const val DRIVERS_PACKAGE = "com.isaklab.isdrdrivers"
    const val DRIVERS_SERVICE = "com.isaklab.isdrdrivers.DriverService"

    // ---- device kinds (CMD_OPEN payload) ----
    const val DEV_RTL_TCP = 0
    const val DEV_RTL_USB = 1
    const val DEV_HACKRF = 2
    const val DEV_HL2 = 3
    const val DEV_G2 = 4
    const val DEV_FLEX = 5   // FlexRadio 6000 (SmartSDR network API)

    /** CMD_OPEN flag bit: HL2 codec drives a classic P1 ANAN board. */
    const val OPEN_FLAG_CLASSIC_BOARD = 1

    // ---- commands (app -> driver host) ----
    const val CMD_HELLO = 0x01                 // i32 protocol version
    const val CMD_AUTH = 0x04                  // str token (LAN sessions only)
    const val CMD_OPEN = 0x02                  // u8 kind, str host, i32 port, i32 flags
    const val CMD_CLOSE = 0x03                 // (empty)
    const val CMD_SET_FREQUENCY = 0x10         // i64 hz
    const val CMD_SET_SAMPLE_RATE = 0x11       // i32 hz
    const val CMD_SET_SPECTRUM_INTEREST = 0x12 // u8 bool
    const val CMD_SET_TX_FREQUENCY = 0x20      // i64 hz
    const val CMD_SET_PTT = 0x21               // u8 bool
    const val CMD_SET_TX_DRIVE = 0x22          // i32 level
    const val CMD_SET_PA_ENABLED = 0x23        // u8 bool
    const val CMD_TX_IQ = 0x24                 // f32[] interleaved 48 kSps IQ
    const val CMD_SET_RECEIVER_COUNT = 0x28    // i32 n
    const val CMD_SET_IQ_FORMAT = 0x29         // i32 IQ_FORMAT_*
    const val CMD_SET_NARROWBAND = 0x2A        // i32 widthHz (0 = off), i64 centerHz
    const val CMD_SET_ACTIVE_RECEIVER = 0x29   // i32 index
    const val CMD_SET_FREQUENCY2 = 0x2A        // i64 hz
    // Generalized per-receiver NCO frequency (index 0..N-1). Extensible to any
    // multi-RX radio; supersedes the RX2-only CMD_SET_FREQUENCY2.
    const val CMD_SET_RX_FREQUENCY = 0x2C      // i32 index, i64 hz
    // Per-receiver IQ streaming, one bit per hardware receiver (bit n =
    // stream receiver n via EV_DATA_RX in addition to the active receiver's
    // EV_DATA). 0 = off (default). Generic N-RX foundation: diversity
    // combining today; TX-monitor receivers and multi-RX audio later.
    const val CMD_SET_RX_STREAM_MASK = 0x2B    // i32 mask
    const val CMD_HL2_SET_LNA = 0x30           // i32 dB
    const val CMD_HL2_SET_VNA_MODE = 0x31      // u8 bool
    const val CMD_HL2_SET_TR_DISABLE = 0x32    // u8 bool
    const val CMD_HL2_SET_FILTER_OUTPUTS = 0x33 // i32 rxMask, i32 txMask (-1 = mirror rx)
    // Linear amplifier keying: [ampPinMask] OC pins (bit n = J16 pin n) that
    // follow PTT on EVERY band, OR-ed into the OC word only while keyed;
    // [txDelayMs] holds RF back after the amp pin asserts (relay settle,
    // hot-switch protection); [hangMs] keeps the amp pin up after RF stops.
    const val CMD_HL2_SET_AMP_KEY = 0x34       // i32 ampPinMask, i32 txDelayMs, i32 hangMs
    const val CMD_HL2_SET_VNA_COUNT = 0x35     // i32 sweep points (addr 0x09 data[15:0])
    const val CMD_HL2_SET_PURESIGNAL = 0x36    // u8 bool (addr 0x0A data[22] feedback routing)
    // Gateware iambic keyer: u8 enabled, i32 wpm, i32 mode(0/1/2), i32 weight,
    // u8 spacing, u8 reverse, i32 pttDelayMs, i32 hangMs
    const val CMD_HL2_SET_CW_KEYER = 0x3B
    const val CMD_G2_SET_ATTENUATOR = 0x38     // i32 dB (0..31 step attenuator)
    const val CMD_G2_SET_OC_OUTPUTS = 0x39     // i32 mask
    const val CMD_HRF_SET_LNA = 0x40           // i32 dB
    const val CMD_HRF_SET_VGA = 0x41           // i32 dB
    const val CMD_HRF_SET_TXVGA = 0x42         // i32 dB
    const val CMD_HRF_SET_AMP = 0x43           // u8 bool
    const val CMD_HRF_SET_ANTENNA_POWER = 0x44 // u8 bool
    const val CMD_HRF_START_RX = 0x45          // (empty)
    const val CMD_HRF_SWEEP_START = 0x46       // i32 startMHz, i32 stopMHz, i32 rateHz, i32 stepHz
    const val CMD_HRF_SWEEP_STOP = 0x47        // (empty)
    const val CMD_RTL_SET_GAIN = 0x50          // i32 tenths of dB
    const val CMD_RTL_SET_GAIN_MODE = 0x51     // u8 bool (manual)
    const val CMD_RTL_SET_AGC = 0x52           // u8 bool
    const val CMD_RTL_SET_PPM = 0x53           // i32 ppm
    const val CMD_RTL_SET_BIAS_TEE = 0x54      // u8 bool
    const val CMD_RTL_SET_DIRECT_SAMPLING = 0x55 // i32 mode (0 off, 1 I, 2 Q)
    const val CMD_RTL_SET_TUNER_BANDWIDTH = 0x56 // i32 hz (0 = auto/track rate)

    // ---- events (driver host -> app) ----
    const val EV_HELLO = 0x81                  // i32 protocol version
    const val EV_OPEN_RESULT = 0x82            // u8 ok
    const val EV_STATUS = 0x83                 // u8 connected, str status
    const val EV_DATA = 0x84                   // i32 nFft, f32[nFft], i32 nIq, f32[nIq] [, i32 seq (FEAT_SEQ_TAG)]
    const val EV_TELEMETRY = 0x85              // u8 flags, f64 tempC, f64 paA, f64 fwd, f64 rev, f64 volts
    const val EV_SWEEP_BLOCK = 0x86            // i64 lowerEdgeHz, i32 n, f32[n]
    const val EV_TX_STATE = 0x87               // u8 transmitting
    const val EV_AUTH_RESULT = 0x88            // u8 ok (LAN handshake)
    // Per-receiver IQ block (see CMD_SET_RX_STREAM_MASK). Emitted BEFORE the
    // EV_DATA of the same flush, covering exactly the same sample span, so a
    // consumer pairs them with zero added latency and can never stall
    // waiting for a block that is not coming.
    /**
     * Narrowband window actually in force: i32 widthHz, i64 centerHz,
     * i32 decimation. Sent whenever it changes, so the client knows which
     * span it may retune inside without a round trip — guessing would let it
     * tune to a frequency the window does not contain and hear silence.
     */
    const val EV_NARROWBAND = 0x8A

    const val EV_DATA_RX = 0x89                // i32 rx, i32 nIq, f32[nIq] [, i32 seq (FEAT_SEQ_TAG)]

    // EV_TELEMETRY flag bits (u16)
    const val TLM_HAS_TEMPERATURE = 1
    const val TLM_HAS_CURRENT = 2
    const val TLM_HAS_FWD_POWER = 4
    const val TLM_HAS_REV_POWER = 8
    const val TLM_HAS_SUPPLY_VOLTS = 16
    const val TLM_HAS_ADC_OVERLOAD = 32
    const val TLM_HAS_PLL_LOCK = 64
    const val TLM_HAS_EXCITER_POWER = 128
    const val TLM_HAS_KEY_INPUTS = 256
    // Trailing i64 counters after the six f64 channels (grown append-only:
    // readers stop at what they know — frames are length-prefixed).
    /** RX stream discontinuities seen by the driver (UDP loss/reorder). */
    const val TLM_HAS_RX_GAPS = 512
    /** Outbound data frames dropped by the host's backpressure queue. */
    const val TLM_HAS_LINK_DROPS = 1024

    // EV_TELEMETRY state bits (u8)
    const val TLM_ST_ADC_OVERLOAD = 1
    const val TLM_ST_PLL_LOCKED = 2
    const val TLM_ST_KEY_PTT = 4
    const val TLM_ST_KEY_DOT = 8
    const val TLM_ST_KEY_DASH = 16

    // ---- shared-memory IQ ring (append-only; FEAT_SHM_RING) ----
    //
    // Loopback-only zero-copy data plane: the driver host offers an ashmem
    // ring ([ShmRing]) whose file descriptor crosses the process boundary
    // ONCE via a Binder transaction on the driver service's binder (the app
    // already binds it for scheduling importance). EV_DATA / EV_DATA_RX
    // payloads then travel through the ring; the TCP session carries only a
    // tiny EV_SHM_FRAME notification per block, preserving frame order with
    // the control/status events around it. Fully additive: without the
    // feature bit, the transaction, or a successful CMD_SHM_ATTACH handshake,
    // both sides keep the TCP framing exactly as before.

    /** Host offers the shared-memory IQ ring (Binder + CMD_SHM_ATTACH). */
    const val FEAT_SHM_RING = 2

    /**
     * Binder transaction on the driver service binder: request the ring.
     * data:  str sessionToken (the CMD_AUTH secret, proving the caller owns
     *        the authenticated TCP session the ring will serve)
     * reply: i32 ok (1/0); on ok: i32 nSlots, i32 slotBytes, then the
     *        android.os.SharedMemory parcelable.
     */
    const val SHM_TRANSACT_GET_RING = 1   // IBinder.FIRST_CALL_TRANSACTION

    /**
     * App -> host after mapping the ring: u8 enable. Host replies
     * EV_SHM_RESULT; only after an ok=1 reply does the data plane switch.
     */
    const val CMD_SHM_ATTACH = 0x60

    /** u8 ok — ring data plane active (1) or unavailable (0). */
    const val EV_SHM_RESULT = 0x8A

    /**
     * One data block published to the ring: i64 seq (slot = seq % nSlots).
     * Sent through the same ordered event stream EV_DATA used, so pairing
     * (EV_DATA_RX before EV_DATA of the same span) and status ordering hold.
     */
    const val EV_SHM_FRAME = 0x8B
}

/**
 * Hardware telemetry crossing the driver boundary — the app-facing shape of
 * whatever the radio reports (HL2 AIN banks + RX-frame status bits, Saturn
 * high-priority status, …). Every field is gated by its `has*` flag: radios
 * report different subsets and a frame may carry only part of them.
 */
data class RadioTelemetry(
    val temperatureC: Double = 0.0,
    val paCurrentA: Double = 0.0,
    val forwardPower: Double = 0.0,
    val reversePower: Double = 0.0,
    val supplyVolts: Double = 0.0,
    /** Exciter (pre-PA) power, protocol units (P2). */
    val exciterPower: Double = 0.0,
    /** RF front-end clipping — actionable: lower the RF gain. */
    val adcOverload: Boolean = false,
    /** Synthesizer lock (P2); false with the flag set = fault. */
    val pllLocked: Boolean = true,
    /** Hardware key/PTT inputs on the radio (footswitch, paddle). */
    val keyPtt: Boolean = false,
    val keyDot: Boolean = false,
    val keyDash: Boolean = false,
    /** RX stream gap events (UDP loss/reorder) since the device opened. */
    val rxGaps: Long = 0,
    /** Outbound frames dropped by the host's per-session backpressure queue. */
    val linkDrops: Long = 0,
    val hasTemperature: Boolean = false,
    val hasCurrent: Boolean = false,
    val hasFwdPower: Boolean = false,
    val hasRevPower: Boolean = false,
    val hasSupplyVolts: Boolean = false,
    val hasAdcOverload: Boolean = false,
    val hasPllLock: Boolean = false,
    val hasExciterPower: Boolean = false,
    val hasKeyInputs: Boolean = false,
    val hasRxGaps: Boolean = false,
    val hasLinkDrops: Boolean = false,
) {
    fun flags(): Int =
        (if (hasTemperature) DriverProto.TLM_HAS_TEMPERATURE else 0) or
            (if (hasCurrent) DriverProto.TLM_HAS_CURRENT else 0) or
            (if (hasFwdPower) DriverProto.TLM_HAS_FWD_POWER else 0) or
            (if (hasRevPower) DriverProto.TLM_HAS_REV_POWER else 0) or
            (if (hasSupplyVolts) DriverProto.TLM_HAS_SUPPLY_VOLTS else 0) or
            (if (hasAdcOverload) DriverProto.TLM_HAS_ADC_OVERLOAD else 0) or
            (if (hasPllLock) DriverProto.TLM_HAS_PLL_LOCK else 0) or
            (if (hasExciterPower) DriverProto.TLM_HAS_EXCITER_POWER else 0) or
            (if (hasKeyInputs) DriverProto.TLM_HAS_KEY_INPUTS else 0) or
            (if (hasRxGaps) DriverProto.TLM_HAS_RX_GAPS else 0) or
            (if (hasLinkDrops) DriverProto.TLM_HAS_LINK_DROPS else 0)

    fun stateBits(): Int =
        (if (adcOverload) DriverProto.TLM_ST_ADC_OVERLOAD else 0) or
            (if (pllLocked) DriverProto.TLM_ST_PLL_LOCKED else 0) or
            (if (keyPtt) DriverProto.TLM_ST_KEY_PTT else 0) or
            (if (keyDot) DriverProto.TLM_ST_KEY_DOT else 0) or
            (if (keyDash) DriverProto.TLM_ST_KEY_DASH else 0)

    companion object {
        fun fromWire(
            flags: Int, state: Int,
            tempC: Double, paA: Double, fwd: Double, rev: Double, volts: Double,
            exciter: Double,
            rxGaps: Long = 0, linkDrops: Long = 0,
        ) = RadioTelemetry(
            temperatureC = tempC, paCurrentA = paA,
            forwardPower = fwd, reversePower = rev, supplyVolts = volts,
            exciterPower = exciter,
            rxGaps = rxGaps, linkDrops = linkDrops,
            adcOverload = state and DriverProto.TLM_ST_ADC_OVERLOAD != 0,
            pllLocked = state and DriverProto.TLM_ST_PLL_LOCKED != 0,
            keyPtt = state and DriverProto.TLM_ST_KEY_PTT != 0,
            keyDot = state and DriverProto.TLM_ST_KEY_DOT != 0,
            keyDash = state and DriverProto.TLM_ST_KEY_DASH != 0,
            hasTemperature = flags and DriverProto.TLM_HAS_TEMPERATURE != 0,
            hasCurrent = flags and DriverProto.TLM_HAS_CURRENT != 0,
            hasFwdPower = flags and DriverProto.TLM_HAS_FWD_POWER != 0,
            hasRevPower = flags and DriverProto.TLM_HAS_REV_POWER != 0,
            hasSupplyVolts = flags and DriverProto.TLM_HAS_SUPPLY_VOLTS != 0,
            hasAdcOverload = flags and DriverProto.TLM_HAS_ADC_OVERLOAD != 0,
            hasPllLock = flags and DriverProto.TLM_HAS_PLL_LOCK != 0,
            hasExciterPower = flags and DriverProto.TLM_HAS_EXCITER_POWER != 0,
            hasKeyInputs = flags and DriverProto.TLM_HAS_KEY_INPUTS != 0,
            hasRxGaps = flags and DriverProto.TLM_HAS_RX_GAPS != 0,
            hasLinkDrops = flags and DriverProto.TLM_HAS_LINK_DROPS != 0,
        )
    }
}
