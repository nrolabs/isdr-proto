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
 * Which optional controls the OPEN radio can honour, as a bitmask.
 *
 * The driver works this out — from firmware version, board revision, whatever
 * the hardware demands — and sends the ANSWER. The app draws its panel from
 * this and never from the criterion: the rule belongs to the side that talks
 * to the hardware and pays for guessing wrong.
 *
 * A control the radio cannot honour must be visibly unavailable rather than
 * absent, so the operator can see it exists; what must never happen is a
 * control that looks operable and quietly does nothing.
 */
object BoardControls {

    const val ANTENNA_SWITCH_PORTS = 1
    const val ANTENNA_SWITCH_MODE = 1 shl 1
    const val ANTENNA_SWITCH_TABLES = 1 shl 2
    const val HARDWARE_SYNC = 1 shl 3
    const val BOARD_UI = 1 shl 4
    const val STREAM_COUNTERS = 1 shl 5
    const val WATCHDOG_LIMITS = 1 shl 6
    const val CLOCK_OUTPUT = 1 shl 7
    const val CLOCK_INPUT_SELECT = 1 shl 8
    const val PANEL_LEDS = 1 shl 9
    const val ANTENNA_POWER_PER_MODE = 1 shl 10
    const val SELF_TEST = 1 shl 11
    const val NARROWBAND_FILTER = 1 shl 12
    const val RESET = 1 shl 13

    /** True when the radio reported [control] among the ones it supports. */
    fun supports(controls: Int, control: Int): Boolean = controls and control != 0
}

/**
 * Explicit signal-path tuning: the intermediate frequency, the oscillator and
 * which filter the signal passes through, set outright instead of derived
 * from the wanted frequency.
 *
 * Worth reaching for when the automatic choice drops a mixer spur or a mirror
 * image inside the span being watched — moving the intermediate frequency
 * shifts the artefact without moving the signal.
 */
object RfPath {

    /** Mixer taken out of the signal path entirely. */
    const val BYPASS = 0
    const val LOW_PASS = 1
    const val HIGH_PASS = 2

    /** Intermediate-frequency range the mixer can work with, in Hz. */
    const val IF_MIN_HZ = 2_000_000_000L
    const val IF_MAX_HZ = 3_000_000_000L

    /** Recommended intermediate frequency when nothing else is known. */
    const val IF_DEFAULT_HZ = 2_400_000_000L

    /** Oscillator range reachable while the mixer IS in the path. */
    const val LO_MIN_HZ = 84_375_000L
    const val LO_MAX_HZ = 5_400_000_000L
}

/**
 * An add-on switch that routes the radio between several antennas, manually
 * or by following the tuned frequency.
 */
object AntennaSwitch {

    const val MAX_BOARDS = 8
    const val MAX_FREQ_RANGES = 8
    const val MAX_DWELL_TIMES = 16

    const val MODE_MANUAL = 0
    const val MODE_FREQUENCY = 1
    const val MODE_TIME = 2

    const val PORT_A1 = 0
    const val PORT_A4 = 3
    const val PORT_B1 = 4
    const val PORT_B4 = 7

    /** Port label as printed on the board (A1..A4, B1..B4). */
    fun portName(port: Int): String =
        if (port in PORT_A1..PORT_B4) {
            (if (port <= PORT_A4) "A" else "B") + ((port % 4) + 1)
        } else {
            "?"
        }

    /**
     * The two ports must sit on OPPOSITE sides of the switch. Both on one side
     * is a routing the hardware cannot make, and the driver refuses it rather
     * than applying half of it.
     */
    fun portsValid(portA: Int, portB: Int): Boolean {
        if (portA !in PORT_A1..PORT_B4 || portB !in PORT_A1..PORT_B4) return false
        return (portA <= PORT_A4) != (portB <= PORT_A4)
    }
}

/**
 * External frequency reference and trigger routing — what lets two boards run
 * off one clock and start sampling on the same edge.
 */
object ClockTrigger {

    const val CLKIN_P1 = 0
    const val CLKIN_P22 = 1

    const val P1_TRIGGER_IN = 0
    const val P1_AUX_CLK1 = 1
    const val P1_CLKIN = 2
    const val P1_TRIGGER_OUT = 3
    const val P1_P22_CLKIN = 4
    const val P1_P2_5 = 5
    const val P1_NC = 6
    const val P1_AUX_CLK2 = 7

    const val P2_CLK3 = 0
    const val P2_TRIGGER_IN = 2
    const val P2_TRIGGER_OUT = 3

    /** The P2 signal numbers are NOT contiguous — 1 is not a valid choice. */
    val P2_SIGNALS = intArrayOf(P2_CLK3, P2_TRIGGER_IN, P2_TRIGGER_OUT)
}

/** Front-panel indicators, as a mask. Zero hands them back to the firmware. */
object PanelLeds {
    const val USB = 1
    const val RX = 2
    const val TX = 4
}

/**
 * Why the sample-transfer loop stopped, when it did. A transmit timeout or a
 * missed deadline means the host failed to keep the converter fed, which on
 * the air is splatter rather than a gap.
 */
object StreamCounters {
    const val ERROR_NONE = 0
    const val ERROR_RX_TIMEOUT = 1
    const val ERROR_TX_TIMEOUT = 2
    const val ERROR_MISSED_DEADLINE = 3
}

/** Canonical values and exact ladders for [CatRepeaterConfig]. */
object CatRepeater {
    const val PAYLOAD_LEN = DriverProto.CAT_REPEATER_PAYLOAD_LEN

    const val DUPLEX_SIMPLEX = DriverProto.CAT_DUPLEX_SIMPLEX
    const val DUPLEX_MINUS = DriverProto.CAT_DUPLEX_MINUS
    const val DUPLEX_PLUS = DriverProto.CAT_DUPLEX_PLUS

    const val TONE_OFF = DriverProto.CAT_TONE_OFF
    /** Tone value is in tenths of a hertz (885 = 88.5 Hz). */
    const val TONE_CTCSS = DriverProto.CAT_TONE_CTCSS
    /** Tone value is the printed three-digit code (023 is integer 23). */
    const val TONE_DCS = DriverProto.CAT_TONE_DCS

    const val DCS_NORMAL = DriverProto.CAT_DCS_NORMAL
    const val DCS_INVERTED = DriverProto.CAT_DCS_INVERTED

    const val MAX_OFFSET_HZ = DriverProto.CAT_REPEATER_MAX_OFFSET_HZ

    const val CAP_DUPLEX = DriverProto.CAT_REPEATER_CAP_DUPLEX
    const val CAP_OFFSET = DriverProto.CAT_REPEATER_CAP_OFFSET
    const val CAP_CTCSS_TX = DriverProto.CAT_REPEATER_CAP_CTCSS_TX
    const val CAP_CTCSS_RX = DriverProto.CAT_REPEATER_CAP_CTCSS_RX
    const val CAP_DCS_TX = DriverProto.CAT_REPEATER_CAP_DCS_TX
    const val CAP_DCS_RX = DriverProto.CAT_REPEATER_CAP_DCS_RX
    const val CAP_DCS_POLARITY = DriverProto.CAT_REPEATER_CAP_DCS_POLARITY
    const val CAP_CROSS_TONE = DriverProto.CAT_REPEATER_CAP_CROSS_TONE

    val CTCSS_TONES_TENTHS_HZ = intArrayOf(
        600, 670, 693, 719, 744, 770, 797, 825, 854, 885, 915,
        948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1200, 1230, 1273,
        1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679,
        1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995,
        2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541,
    )

    val DCS_CODES = intArrayOf(
        23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74,
        114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155, 156,
        162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246,
        251, 252, 255, 261, 263, 265, 266, 271, 274, 306, 311, 315, 325,
        331, 332, 343, 346, 351, 356, 364, 365, 371, 411, 412, 413, 423,
        431, 432, 445, 446, 452, 454, 455, 462, 464, 465, 466, 503, 506,
        516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654,
        662, 664, 703, 712, 723, 731, 732, 734, 743, 754,
    )

    internal fun validationError(c: CatRepeaterConfig): String? {
        if (c.duplex !in DUPLEX_SIMPLEX..DUPLEX_PLUS) return "unknown CAT duplex value"
        if (c.offsetHz !in 0..MAX_OFFSET_HZ || c.offsetHz % 100L != 0L) {
            return "CAT repeater offset is outside the 100 Hz wire ladder"
        }
        if (c.duplex == DUPLEX_SIMPLEX && c.offsetHz != 0L) {
            return "simplex CAT repeater state must have zero offset"
        }
        if (c.duplex != DUPLEX_SIMPLEX && c.offsetHz == 0L) {
            return "duplex CAT repeater state requires a positive offset"
        }
        return toneError(c.txKind, c.txValue, c.txPolarity)
            ?: toneError(c.rxKind, c.rxValue, c.rxPolarity)
    }

    private fun toneError(kind: Int, value: Int, polarity: Int): String? = when (kind) {
        TONE_OFF -> if (value == 0 && polarity == DCS_NORMAL) null
            else "disabled CAT tone must have zero value and normal polarity"
        TONE_CTCSS -> if (value in CTCSS_TONES_TENTHS_HZ && polarity == DCS_NORMAL) null
            else "CAT CTCSS value is not on the canonical tone ladder"
        TONE_DCS -> if (value in DCS_CODES && polarity in DCS_NORMAL..DCS_INVERTED) null
            else "CAT DCS code or polarity is invalid"
        else -> "unknown CAT tone kind"
    }
}

/**
 * Complete operator intent for one atomic CAT repeater transaction.
 *
 * The wire representation is exactly 21 bytes, big-endian. Encoding invalid
 * state throws instead of substituting a nearby tone or silently clearing a
 * field; decoding rejects trailing bytes as well as truncated payloads.
 */
data class CatRepeaterConfig(
    val duplex: Int,
    val offsetHz: Long,
    val txKind: Int,
    val txValue: Int,
    val txPolarity: Int,
    val rxKind: Int,
    val rxValue: Int,
    val rxPolarity: Int,
) {
    fun encode(): ByteArray {
        CatRepeater.validationError(this)?.let { throw IllegalArgumentException(it) }
        return java.nio.ByteBuffer.allocate(CatRepeater.PAYLOAD_LEN)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .put(duplex.toByte())
            .putLong(offsetHz)
            .put(txKind.toByte())
            .putInt(txValue)
            .put(txPolarity.toByte())
            .put(rxKind.toByte())
            .putInt(rxValue)
            .put(rxPolarity.toByte())
            .array()
    }

    companion object {
        fun decode(payload: ByteArray): CatRepeaterConfig? {
            if (payload.size != CatRepeater.PAYLOAD_LEN) return null
            val b = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.BIG_ENDIAN)
            val config = CatRepeaterConfig(
                duplex = b.get().toInt() and 0xFF,
                offsetHz = b.long,
                txKind = b.get().toInt() and 0xFF,
                txValue = b.int,
                txPolarity = b.get().toInt() and 0xFF,
                rxKind = b.get().toInt() and 0xFF,
                rxValue = b.int,
                rxPolarity = b.get().toInt() and 0xFF,
            )
            return config.takeIf { CatRepeater.validationError(it) == null }
        }
    }
}
