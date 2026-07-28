/*
 * Narrowband window arithmetic (FEAT_NARROWBAND).
 *
 * Pure decisions, no I/O: which decimation to use, what the resulting window
 * actually covers, and whether a requested frequency still falls inside it.
 * These are the calculations that decide whether an operator hears a signal
 * or silence, so they are kept away from sockets and tested on their own.
 */
package com.isaklab.isdrproto

import kotlin.math.abs

/**
 * A resolved narrowband window.
 *
 * @param decimation factor applied to the radio's sample rate; one of
 *                   [NarrowbandPlan.DECIMATIONS]
 * @param widthHz    the delivered sample rate, i.e. the usable span
 * @param centerHz   absolute frequency the window is centred on
 */
data class NarrowbandPlan(
    val decimation: Int,
    val widthHz: Int,
    val centerHz: Long,
) {
    /**
     * Usable half-span. Deliberately smaller than widthHz/2: the outer edges
     * belong to the decimation filter's transition band, so a signal parked
     * there is attenuated rather than absent — which sounds like a fault
     * rather than like an edge.
     */
    val usableHalfHz: Int get() = (widthHz * USABLE_FRACTION / 2).toInt()

    val lowHz: Long get() = centerHz - usableHalfHz
    val highHz: Long get() = centerHz + usableHalfHz

    /** True when [hz] can be tuned without asking the station to move. */
    fun covers(hz: Long): Boolean = hz in lowHz..highHz

    /** Bytes per second this window costs in [format], counting I and Q. */
    fun bytesPerSecond(format: Int): Long =
        widthHz.toLong() * 2 * DriverProto.iqSampleBytes(format)

    companion object {
        /** Fraction of the delivered width that is flat enough to use. */
        const val USABLE_FRACTION = 0.8

        /** Narrowest window worth asking for; below this, SSB does not fit. */
        const val MIN_WIDTH_HZ = 6_000

        /**
         * Resolve a request against what the radio can actually deliver.
         *
         * The factor is taken from [DECIMATIONS] — the set the front end
         * will actually arm. Asking for anything else would be refused
         * downstream, and the client would compute its tuning offsets
         * against a width the station never produced.
         *
         * @param sampleRateHz the radio's current rate
         * @param requestedWidthHz desired span; clamped to what is reachable
         * @param centerHz absolute centre for the window
         * @return the plan, or null when no decimation helps (the radio is
         *         already narrower than the request)
         */
        fun resolve(sampleRateHz: Int, requestedWidthHz: Int, centerHz: Long): NarrowbandPlan? {
            if (sampleRateHz <= 0 || requestedWidthHz <= 0) return null
            val target = maxOf(requestedWidthHz, MIN_WIDTH_HZ)
            if (sampleRateHz <= target) return null   // nothing to gain

            // Deepest decimation whose output still COVERS the target —
            // overshooting would cut the operator's span, so it is the
            // largest factor that still fits, never the closest one.
            // No factor covers the request — WFM out of a 384 kSps radio, say.
            // Returning a D=1 "window" would have the station run a
            // decimate-by-one filter and announce a narrowing that never
            // happened; null keeps the full stream and says so honestly.
            val decim = DECIMATIONS.lastOrNull { sampleRateHz / it >= target } ?: return null
            val width = sampleRateHz / decim
            if (width < MIN_WIDTH_HZ) return null
            return NarrowbandPlan(decim, width, centerHz)
        }

        /**
         * Hard ceiling from the front end itself: the overlap-save geometry
         * is defined for D in 2..64 (N = 4800 must stay divisible by D and
         * the anti-alias attenuation must clear 100 dB). Promising more here
         * would produce a plan the DSP refuses at runtime, and the client
         * would already have sized its buffers for a window that never
         * arrives.
         */
        const val MAX_DECIMATION = 64

        /**
         * Every decimation the overlap-save front end will actually arm, in
         * ascending order.
         *
         * Not the powers of two. The transform's constraints are that the
         * segment length N, the overlap K-1 and the useful length L all
         * divide by D, and that both transform lengths stay 2/3/5-smooth; at
         * the production geometry (N=4800, K=1729) that admits 3, 6, 12, 24
         * and 48 as well. Restricting the ladder to powers of two made the
         * plan round up: 250 kHz out of 960 kSps became a 480 kHz window
         * instead of 320 kHz, and the link paid half again as much as the
         * mode needed.
         *
         * Kept in step with os_setup — a value the DSP refuses would have the
         * client sizing its buffers for a window that never arrives.
         */
        val DECIMATIONS = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)

        /**
         * Normalised phase increment that shifts [centerHz] to DC, for
         * NativeDsp.setShift.
         *
         * Sign matters, and the front end's convention is the POSITIVE
         * offset of the wanted signal from centre — the same value
         * Demodulator passes for CTUN (2*PI*shiftHz/rate). Reasoning about a
         * time-domain NCO suggests the opposite ("to bring +f to zero, mix at
         * -f"), and that is how this was written; but the overlap-save path
         * does the coarse shift by SELECTING source bins, where a positive
         * offset selects the positive bin. Negated, the wanted signal moved
         * to -f instead, landed outside the protected band, and was buried by
         * the decimation filter — leaving noise as the strongest thing in the
         * window rather than an obvious failure.
         */
        fun shiftFor(tunedHz: Long, centerHz: Long, sampleRateHz: Int): Double {
            if (sampleRateHz <= 0) return 0.0
            val offset = (centerHz - tunedHz).toDouble()
            return 2.0 * Math.PI * offset / sampleRateHz
        }

        /**
         * How far [hz] sits from the window centre, in Hz. The client uses
         * this to place its own VFO inside the delivered span.
         */
        fun offsetWithin(plan: NarrowbandPlan, hz: Long): Long = hz - plan.centerHz

        /**
         * True when the client should ask the station to re-centre.
         *
         * Hysteresis: recentring on every edge touch would thrash the link
         * while an operator tunes across a band, so the trigger is set inside
         * the usable span, not at it.
         */
        fun needsRecentre(plan: NarrowbandPlan, hz: Long): Boolean =
            abs(hz - plan.centerHz) > plan.usableHalfHz * RECENTRE_TRIGGER

        const val RECENTRE_TRIGGER = 0.75
    }
}
