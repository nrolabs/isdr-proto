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
