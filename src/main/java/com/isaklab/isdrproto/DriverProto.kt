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
 * Protocol constants and command/event definitions for the iSDR wire protocol.
 *
 * Why a custom protocol? The iSDR architecture strictly decouples the DSP/UI (app) 
 * from hardware access (driver host). The driver host acts as a dumb pipe: it performs no 
 * demodulation or decoding, bridging RF hardware to the app. This file defines the binary, 
 * big-endian format used over TCP (or shared memory) to stream IQ data, transmit commands, 
 * and report hardware telemetry. 
 * 
 * State Management & Discovery:
 * Features are additive and discovered via `EV_HELLO` feature bits, avoiding hard version bumps 
 * when introducing optional capabilities like `FEAT_NARROWBAND` or `FEAT_SHM_RING`.
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

    // Capability bits below name a CONCEPT, not a radio. The next board with
    // an antenna switch or an external clock input reuses the bit rather than
    // adding one of its own — which is what a single radio-named bit covering
    // two dozen unrelated opcodes would have forced.
    //
    // All of them are gated on a bit rather than a version bump because an app
    // paired with an older driver host must HIDE the controls instead of
    // sending opcodes into a void: a switch that appears to work and does
    // nothing is worse than one that is absent.

    /** Explicit IF/oscillator/filter-path tuning, overriding the automatic map. */
    const val FEAT_RF_PATH_CONTROL = 32

    /** Antenna switch driven by band, dwell time or manual port select. */
    const val FEAT_ANTENNA_SWITCH = 64

    /** External clock reference in/out and trigger routing for multi-board sync. */
    const val FEAT_CLOCK_TRIGGER = 128

    /** Board identity, self-test and stream-shortfall counters. */
    const val FEAT_BOARD_DIAGNOSTICS = 256



    // ---- IQ wire format ----
    //
    // float32 was the original choice so one format could carry every radio,
    // from the RTL's 8 bits to the HPSDR boards' 24. That is right for the
    // DSP and wrong for the wire: an 8-bit dongle shipped 4 bytes to carry
    // 1 byte of information. Block floating point carries the block's own
    // scale instead, so one byte per component serves every radio.
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

    /**
     * Block floating point: a float32 scale for the block, then eight bits
     * per component measured against it.
     *
     * Halves the 24-bit boards' cost (s16 -> one byte) and, on the 8-bit
     * front ends, reclaims the headroom a fixed scale throws away: a stream
     * sitting 12 dB below full scale spends two of its eight bits on
     * nothing. Only worth it after channelisation, which is what removes the
     * strong neighbours that would otherwise need the extra range.
     */
    const val IQ_FORMAT_BFP8 = 3


    /**
     * Spectrum wire encodings. U8 is block floating point (see
     * SpectrumCodec): a quarter of float32's bytes for a display that
     * resolves half a decibel.
     *
     * It matters once the station channelises. Wideband the spectrum was
     * noise against the IQ; at a 6 kHz SSB window the float32 spectrum is
     * three times the IQ it accompanies, and the display — not the signal —
     * becomes what the link is carrying.
     */
    const val SPECTRUM_FORMAT_F32 = 0
    const val SPECTRUM_FORMAT_U8 = 1

    /**
     * THE encodings EV_DATA / EV_DATA_RX use on the wire.
     *
     * There is no negotiation, deliberately. Two ends that disagree about a
     * format cannot detect it — every block is garbled and nothing fails —
     * and that is exactly what happened when the station assumed float32
     * while the phone had agreed s16 with the driver behind it. One value,
     * one meaning, no handshake to get wrong.
     */
    const val IQ_WIRE_FORMAT = IQ_FORMAT_BFP8
    const val SPECTRUM_WIRE_FORMAT = SPECTRUM_FORMAT_U8

    /**
     * Encoding for CMD_TX_IQ_NARROW. The same one, for the same reason —
     * and on transmit the block scale is worth more than on receive, since
     * speech spends most of its time well below the peak the level control
     * is set for.
     */
    const val TX_IQ_WIRE_FORMAT = IQ_FORMAT_BFP8

    /** Bytes one sample occupies on the wire in [format]. */
    fun iqSampleBytes(format: Int): Int = when (format) {
        IQ_FORMAT_S16 -> 2
        IQ_FORMAT_S8 -> 1
        IQ_FORMAT_BFP8 -> 1
        else -> 4
    }

    /** True when [format] is one this build understands. */
    fun isKnownIqFormat(format: Int): Boolean =
        format == IQ_FORMAT_F32 || format == IQ_FORMAT_S16 ||
            format == IQ_FORMAT_S8 || format == IQ_FORMAT_BFP8

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
    /**
     * FlexRadio 6000 (SmartSDR network API). DECLARED BUT WITHDRAWN: the
     * driver library went private and is no longer bundled, so the host
     * answers this kind with a refusal and the app leaves it out of the radios
     * it offers. The number stays reserved rather than being reused, so a
     * client built against an older contract cannot have it mean something
     * else.
     */
    const val DEV_FLEX = 5

    /**
     * CAT rig (control protocol, no IQ plane): the driver picks the dialect —
     * CI-V today; Yaesu/Kenwood/Flex are future dialects behind the same kind.
     * Open payload: host = serial device path or TCP-bridge host, port = baud
     * when serial (0 = 115200) else TCP port, flags low byte = bus address
     * (0 = probe).
     */
    const val DEV_CAT = 6

    /**
     * Native IQ from the dedicated USB ports of the IC-7610 and IC-R8600 —
     * a real SDR front end, not a CAT radio. Open payload: host = path of
     * the operator's .spt firmware file (IC-R8600; empty for the IC-7610),
     * flags 0 = either rig, 1 = IC-7610 only, 2 = IC-R8600 only.
     */
    const val DEV_ICOM_IQ = 7

    /** CMD_OPEN flag bit: HL2 codec drives a classic P1 ANAN board. */
    const val OPEN_FLAG_CLASSIC_BOARD = 1

    // ---- DEV_CAT open-flags layout ----
    // Low byte: dialect-specific station address (CI-V bus address; 0 = probe).
    // Bits 8..11: the CAT dialect the driver must speak.
    const val CAT_DIALECT_SHIFT = 8
    const val CAT_DIALECT_MASK = 0xF shl CAT_DIALECT_SHIFT
    const val CAT_DIALECT_CIV = 0
    const val CAT_DIALECT_KENWOOD = 1

    /** The dialect carried in a DEV_CAT open's flags word. */
    fun catDialect(flags: Int): Int = (flags and CAT_DIALECT_MASK) shr CAT_DIALECT_SHIFT

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

    /**
     * Transmit chain timing: i32 latencyMs, i32 hangMs.
     *
     * [latencyMs] is how much TX audio the radio buffers before keying RF:
     * it is the slack that absorbs network jitter between the host and the
     * board. Too small and the first syllable is dropped whenever a packet
     * arrives late (Wi-Fi/cellular); larger only adds that many ms of
     * TX latency. [hangMs] keeps the transmitter keyed after the audio
     * stops, so inter-word gaps do not bounce the T/R relay.
     *
     * ONE concept-level opcode, like CMD_SET_ANTENNA_POWER: any radio with a
     * host-visible TX buffer maps these onto its own registers and clamps to
     * its own range; radios without the concept ignore the command.
     */
    const val CMD_SET_TX_TIMING = 0x25         // i32 latencyMs, i32 hangMs
    const val CMD_SET_RECEIVER_COUNT = 0x28    // i32 n
    const val CMD_SET_NARROWBAND = 0x3A        // i32 widthHz (0 = off), i64 centerHz

    /**
     * u8 bool — feed DC up the antenna cable for a mast amplifier or active
     * antenna (the bias tee).
     *
     * ONE opcode for one concept. Two radios carried this under two names and
     * two numbers while the app's capability table and its settings screen had
     * always treated it as a single feature; the split existed only in the
     * wire and in the code beneath it.
     */
    const val CMD_SET_ANTENNA_POWER = 0x13

    /**
     * i32 Hz — analogue anti-alias filter width ahead of the converter;
     * 0 follows the sample rate. Both radios that have this already meant the
     * same thing by it, including the zero.
     */
    const val CMD_SET_ANALOG_FILTER = 0x14

    /**
     * Panadapter window: i32 decimation (1 = the whole stream), i64 offsetHz
     * from the stream centre.
     *
     * A decimation above 1 narrows the span BEFORE the transform, which is
     * the only thing that resolves finer: slicing finished bins cannot
     * separate what the transform never separated. The driver answers with
     * EV_SPECTRUM_ZOOM.
     */
    const val CMD_SET_SPECTRUM_ZOOM = 0x15

    /**
     * Transmit IQ at a REDUCED rate: i32 rateHz, then s16 interleaved IQ.
     *
     * CMD_TX_IQ carries 48 kSps float32 — 3.07 Mbit/s upstream to convey
     * voice that occupies 3 kHz. The station interpolates back to the
     * driver's 48 kSps, so the radio and the transmit chain see no change;
     * only the link narrows.
     *
     * The rate is carried per frame rather than negotiated once: transmit
     * starts and stops, and a rate agreed at connect time would be wrong
     * after any change to the audio path.
     */
    const val CMD_TX_IQ_NARROW = 0x3C          // i32 rateHz, s16[] interleaved IQ

    /** Rate CMD_TX_IQ is defined at; the narrow form is restored to it. */
    const val TX_RATE_HZ = 48_000
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
    // N2ADR IO board (Pico I2C slave 0x1D): u8 enabled, i32 rfInput (REG_RF_INPUTS
    // 0..2), i32 opMode (Thetis mode codes, -1 = unknown). While enabled the
    // driver mirrors TX/RX frequencies into the board's registers itself.
    const val CMD_HL2_SET_IOBOARD = 0x37
    // Gateware iambic keyer: u8 enabled, i32 wpm, i32 mode(0/1/2), i32 weight,
    // u8 spacing, u8 reverse, i32 pttDelayMs, i32 hangMs
    const val CMD_HL2_SET_CW_KEYER = 0x3B
    const val CMD_G2_SET_ATTENUATOR = 0x38     // i32 dB (0..31 step attenuator)
    const val CMD_G2_SET_OC_OUTPUTS = 0x39     // i32 mask
    const val CMD_HRF_SET_LNA = 0x40           // i32 dB
    const val CMD_HRF_SET_VGA = 0x41           // i32 dB
    const val CMD_HRF_SET_TXVGA = 0x42         // i32 dB
    const val CMD_HRF_SET_AMP = 0x43           // u8 bool
    const val CMD_HRF_START_RX = 0x45          // (empty)
    const val CMD_HRF_SWEEP_START = 0x46       // i32 startMHz, i32 stopMHz, i32 rateHz, i32 stepHz
    const val CMD_HRF_SWEEP_STOP = 0x47        // (empty)

    // HackRF, second block. Everything the board can be told to do that is
    // not a gain or a frequency. These exist because a control the driver
    // implements but the protocol cannot carry is a control the radio does
    // not have — the driver had all of these and none of them reached the
    // app.

    /**
     * Explicit IF/LO tuning with an RF path filter: i64 ifHz, i64 loHz,
     * i32 path (0 bypass, 1 low pass, 2 high pass).
     *
     * Overrides the intermediate frequency, local oscillator and image-reject
     * filter the firmware would derive on its own, so a mixer spur or image
     * landing inside the watched span can be moved out of it.
     */
    const val CMD_HRF_SET_FREQ_EXPLICIT = 0x70
    /** Per-mode bias tee: 9 x u8 — (update, changeOnEntry, enabled) x off/rx/tx. */
    const val CMD_HRF_SET_BIAS_T_OPTS = 0x71
    /** u8 — hold sampling until the trigger input fires (multi-board sync). */
    const val CMD_HRF_SET_HW_SYNC = 0x72
    /** u8 — let an add-on display (PortaPack) drive the board's own UI. */
    const val CMD_HRF_SET_UI_ENABLE = 0x73
    /** i32 mask — front-panel LEDs (bit 0 USB, 1 RX, 2 TX). */
    const val CMD_HRF_SET_LEDS = 0x74
    /** u8 — narrowband front-end filter (newer hardware). */
    const val CMD_HRF_SET_NARROWBAND_FILTER = 0x75
    /** u8 — 10 MHz reference output on the CLKOUT port. */
    const val CMD_HRF_SET_CLKOUT = 0x76
    /** i32 — which pin the external clock input is taken from. */
    const val CMD_HRF_SET_CLKIN_CTRL = 0x77
    /** i32 — expansion header P1 signal routing. */
    const val CMD_HRF_SET_P1_CTRL = 0x78
    /** i32 — expansion header P2 signal routing. */
    const val CMD_HRF_SET_P2_CTRL = 0x79
    /** i32 — transmit underrun watchdog limit in bytes (0 disables). */
    const val CMD_HRF_SET_TX_UNDERRUN_LIMIT = 0x7A
    /** i32 — receive overrun watchdog limit in bytes (0 disables). */
    const val CMD_HRF_SET_RX_OVERRUN_LIMIT = 0x7B
    /** i32 address, i32 portA, i32 portB — Opera Cake manual port select. */
    const val CMD_HRF_OPERACAKE_SET_PORTS = 0x7C
    /** i32 address, i32 mode (0 manual, 1 frequency, 2 time). */
    const val CMD_HRF_OPERACAKE_SET_MODE = 0x7D
    /** i32 n, then n x (i32 minMHz, i32 maxMHz, i32 port). */
    const val CMD_HRF_OPERACAKE_SET_RANGES = 0x7E
    /** i32 n, then n x (i32 dwellSamples, i32 port). */
    const val CMD_HRF_OPERACAKE_SET_DWELL = 0x7F
    /** (empty) — reboot the board. */
    const val CMD_HRF_RESET = 0x57
    /** (empty) — ask for EV_HRF_INFO. */
    const val CMD_HRF_QUERY_INFO = 0x58
    /** (empty) — ask for EV_HRF_M0_STATE (transmit shortfall counters). */
    const val CMD_HRF_QUERY_M0_STATE = 0x59
    /** (empty) — run the board self-test, answered by EV_HRF_SELFTEST. */
    const val CMD_HRF_SELFTEST = 0x5A
    /** (empty) — drop an explicit tuning override, back to automatic. */
    const val CMD_HRF_CLEAR_FREQ_EXPLICIT = 0x5C
    const val CMD_RTL_SET_GAIN = 0x50          // i32 tenths of dB
    const val CMD_RTL_SET_GAIN_MODE = 0x51     // u8 bool (manual)
    const val CMD_RTL_SET_AGC = 0x52           // u8 bool
    const val CMD_RTL_SET_PPM = 0x53           // i32 ppm
    const val CMD_RTL_SET_DIRECT_SAMPLING = 0x55 // i32 mode (0 off, 1 I, 2 Q)

    // ---- events (driver host -> app) ----
    const val EV_HELLO = 0x81                  // i32 protocol version
    const val EV_OPEN_RESULT = 0x82            // u8 ok
    const val EV_STATUS = 0x83                 // u8 connected, str status
    const val EV_DATA = 0x84                   // i32 nFft, spectrum[nFft], i32 nIq, iq[nIq] [, i32 seq (FEAT_SEQ_TAG)]
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
    const val EV_NARROWBAND = 0x8C

    const val EV_DATA_RX = 0x89                // i32 rx, i32 nIq, iq[nIq] [, i32 seq (FEAT_SEQ_TAG)]

    /**
     * What the HackRF says about itself, in reply to CMD_HRF_QUERY_INFO:
     * i32 boardId, str boardName, i32 boardRev, str revName, u8 genuineGsg,
     * i32 platformBits, str platformName, str firmwareVersion,
     * i32 usbApiVersion, str usbApiName, str serialNumber, u8 clkinPresent,
     * i32 nOperacake, i32[nOperacake] addresses, i64 cpldChecksum,
     * u8 revisionKnown, i32 basebandFilterHz, u8 explicitTuningActive,
     * i32 operacakeMode (-1 unknown).
     *
     * The board identity drives which controls are OFFERED, not just what is
     * displayed: a control the running firmware does not implement must not
     * appear as a switch that silently does nothing.
     */
    const val EV_HRF_INFO = 0x8D

    /**
     * M0 SGPIO loop state: i32 × 11 in struct order (requestedMode,
     * requestFlag, activeMode, m0Count, m4Count, numShortfalls,
     * longestShortfall, shortfallLimit, threshold, nextMode, error).
     * The board's own verdict on whether the host kept the DAC fed.
     */
    const val EV_HRF_M0_STATE = 0x8E

    /** Self-test result: u8 pass, str message, u8 hasRtc, u8 rtcPass. */
    const val EV_HRF_SELFTEST = 0x8F

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
    const val CMD_CAT_SET_MODE = 0x68        // i32 CAT operating-mode code (dialect-defined)
    /**
     * i32 control id (CATCTL_*), i32 value — the rig's OWN receive controls,
     * driven over CAT. Each dialect maps the shared id onto its wire and
     * ignores ids its rig does not have (the UI hides those by capability).
     */
    const val CMD_CAT_SET_CONTROL = 0x69

    // ---- CAT rig control ids (CMD_CAT_SET_CONTROL) ----
    const val CATCTL_FIL = 1
    const val CATCTL_RF_GAIN = 2
    const val CATCTL_SQUELCH = 3
    const val CATCTL_NR = 4
    const val CATCTL_NB = 5
    const val CATCTL_NOTCH_AUTO = 6
    const val CATCTL_AGC = 7
    const val CATCTL_PREAMP = 8
    const val CATCTL_ATT = 9
    const val CATCTL_PBT_IN = 10
    const val CATCTL_PBT_OUT = 11
    const val CATCTL_FILTER_WIDTH = 12
    const val CATCTL_AF_GAIN = 13

    /** u8 ok — ring data plane active (1) or unavailable (0). */
    const val EV_SHM_RESULT = 0x8A

    /**
     * One data block published to the ring: i64 seq (slot = seq % nSlots).
     * Sent through the same ordered event stream EV_DATA used, so pairing
     * (EV_DATA_RX before EV_DATA of the same span) and status ordering hold.
     */
    const val EV_SHM_FRAME = 0x8B

    /**
     * The panadapter window actually in force: i32 decimation, i64 offsetHz.
     *
     * Sent whenever it changes, including when the driver clamped what was
     * asked for. The app labels its axis from THIS, never from the request: a
     * display that draws one span and names another is worse than none.
     */
    const val EV_SPECTRUM_ZOOM = 0x91

    /**
     * The baseband rate the hardware is ACTUALLY running: i32 hz.
     *
     * Sent when a radio opens — a converter keeps the clock a previous
     * session left it on, so the rate in force at open is not the app's
     * default — and after every CMD_SET_SAMPLE_RATE, whether or not the
     * request was met exactly. The app scales its spectrum, its NCO and its
     * audio decimation by THIS value and never by the one it asked for.
     */
    const val EV_SAMPLE_RATE = 0x90

    /**
     * The frequency the receiver is ACTUALLY programmed to: i64 hz.
     *
     * Same truth-over-request rule as EV_SAMPLE_RATE: sent when a radio
     * opens (whatever it powered up on or a previous session left it
     * holding) and after every CMD_SET_FREQUENCY. This is the tuning path's
     * reconciliation — without it, a tune that never reached the hardware
     * leaves the app displaying one band while the IQ carries another, and
     * nothing anywhere can detect the divergence. A driver that cannot read
     * its frequency back sends nothing rather than a guess.
     */
    const val EV_FREQUENCY = 0x92
}

/**
 * Hardware telemetry crossing the driver boundary — the app-facing shape of
 * whatever the radio reports (HL2 AIN banks + RX-frame status bits, Saturn
 * high-priority status, …). 
 * 
 * State Management:
 * Because different radios provide different subsets of metrics (e.g. some lack SWR or temp sensors),
 * every metric is paired with a `has*` flag. The protocol parser strictly respects these flags 
 * to prevent zero-values from being interpreted as valid sensor readings. 
 * The layout is designed to be append-only, allowing newer drivers to supply additional fields 
 * while maintaining backward compatibility with older clients.
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
