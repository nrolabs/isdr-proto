package com.isaklab.isdrproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class NarrowbandPlanTest {

    // ---- the bandwidth claim ---------------------------------------------

    @Test fun a_twelve_kilohertz_window_fits_on_mobile_data() {
        // The entire reason this mode exists. Full-rate float32 at 2.048 MS/s
        // is ~16 MB/s; if this ever stops being ~1000x smaller the mode has
        // no purpose.
        val plan = NarrowbandPlan.resolve(2_048_000, 12_000, 14_074_000)!!
        val narrow = plan.bytesPerSecond(DriverProto.IQ_FORMAT_S16)
        val full = 2_048_000L * 2 * 4
        assertTrue("narrow=$narrow full=$full", full / narrow >= 100)
        assertTrue("$narrow B/s is too much for 4G", narrow <= 140_000)
    }

    @Test fun decimation_is_a_power_of_two() {
        // The front end is a half-band cascade. A non-power-of-two would be
        // rounded somewhere downstream and the client would compute its
        // tuning offsets against a width the station never produced.
        for (rate in intArrayOf(240_000, 960_000, 2_048_000, 2_400_000, 3_200_000)) {
            val plan = NarrowbandPlan.resolve(rate, 12_000, 0) ?: continue
            assertEquals("rate $rate gave ${plan.decimation}",
                0, plan.decimation and (plan.decimation - 1))
        }
    }

    @Test fun the_window_never_comes_out_narrower_than_asked() {
        // Overshooting the decimation would silently cut the operator's span.
        for (rate in intArrayOf(960_000, 2_048_000, 2_400_000, 3_200_000)) {
            for (want in intArrayOf(6_000, 12_000, 24_000, 48_000)) {
                val plan = NarrowbandPlan.resolve(rate, want, 0) ?: continue
                assertTrue(
                    "rate $rate want $want got ${plan.widthHz}",
                    plan.widthHz >= want,
                )
            }
        }
    }

    @Test fun a_request_below_the_ssb_floor_is_widened_not_honoured() {
        val plan = NarrowbandPlan.resolve(2_048_000, 500, 0)!!
        assertTrue("SSB does not fit in ${plan.widthHz} Hz",
            plan.widthHz >= NarrowbandPlan.MIN_WIDTH_HZ)
    }

    @Test fun no_plan_when_the_radio_is_already_narrow() {
        // 8 kHz in, 12 kHz wanted: decimating would only make it worse.
        assertNull(NarrowbandPlan.resolve(8_000, 12_000, 0))
        assertNull(NarrowbandPlan.resolve(0, 12_000, 0))
        assertNull(NarrowbandPlan.resolve(2_048_000, 0, 0))
    }

    @Test fun decimation_never_exceeds_what_the_front_end_accepts() {
        // osConfig is defined for D in 2..64. A plan above that is refused at
        // runtime, after the client already sized buffers for a window that
        // never arrives.
        assertEquals(64, NarrowbandPlan.MAX_DECIMATION)
        for (rate in intArrayOf(960_000, 2_048_000, 2_400_000, 3_200_000)) {
            val plan = NarrowbandPlan.resolve(rate, NarrowbandPlan.MIN_WIDTH_HZ, 0) ?: continue
            assertTrue("rate $rate gave decim ${plan.decimation}",
                plan.decimation in 2..NarrowbandPlan.MAX_DECIMATION)
        }
    }

    @Test fun the_real_configuration_still_fits_on_mobile_data() {
        // With D capped at 64, 2.048 MS/s yields a 32 kHz window rather than
        // 12 kHz. That is the honest number and it must still be usable.
        val plan = NarrowbandPlan.resolve(2_048_000, 12_000, 14_074_000)!!
        assertEquals(64, plan.decimation)
        assertEquals(32_000, plan.widthHz)
        val bits = plan.bytesPerSecond(DriverProto.IQ_FORMAT_S16) * 8
        assertTrue("$bits bit/s is too much for 4G", bits <= 1_100_000)
    }

    // ---- coverage: hearing a signal vs hearing silence --------------------

    @Test fun the_usable_span_excludes_the_filter_skirts() {
        // A signal parked in the transition band is attenuated, not absent,
        // which sounds like a fault rather than like an edge. Claiming it is
        // usable is worse than not delivering it.
        val plan = NarrowbandPlan(decimation = 128, widthHz = 16_000, centerHz = 14_074_000)
        assertTrue(plan.usableHalfHz < plan.widthHz / 2)
        assertTrue(plan.covers(14_074_000))
        assertTrue(plan.covers(14_074_000 + plan.usableHalfHz.toLong()))
        assertFalse(plan.covers(14_074_000 + plan.widthHz / 2 + 1L))
    }

    @Test fun coverage_is_symmetric_around_the_centre() {
        val plan = NarrowbandPlan(64, 24_000, 7_100_000)
        for (d in longArrayOf(0, 1_000, 5_000, plan.usableHalfHz.toLong())) {
            assertEquals("asymmetric at $d",
                plan.covers(7_100_000 + d), plan.covers(7_100_000 - d))
        }
    }

    // ---- the mixer sign: the classic way to lose the signal ---------------

    @Test fun the_shift_brings_the_wanted_signal_towards_zero() {
        // To move a signal at +f down to DC the mixer must run at -f. The
        // opposite sign puts it at -f, twice as far out, where the decimation
        // filter removes it — the radio looks dead and nothing logs an error.
        val rate = 2_048_000
        val centre = 14_074_000L
        val above = NarrowbandPlan.shiftFor(tunedHz = centre + 10_000, centerHz = centre, sampleRateHz = rate)
        val below = NarrowbandPlan.shiftFor(tunedHz = centre - 10_000, centerHz = centre, sampleRateHz = rate)
        assertTrue("a signal above centre must shift one way", above > 0)
        assertTrue("and below the other", below < 0)
        assertEquals("magnitudes must match", abs(above), abs(below), 1e-12)
    }

    @Test fun the_shift_is_zero_when_already_centred() {
        assertEquals(0.0, NarrowbandPlan.shiftFor(14_074_000, 14_074_000, 2_048_000), 0.0)
    }

    @Test fun the_shift_matches_the_normalised_frequency() {
        val rate = 48_000
        val shift = NarrowbandPlan.shiftFor(12_000, 0, rate)   // +12 kHz = rate/4
        assertEquals(2.0 * PI * 0.25, abs(shift), 1e-9)
    }

    @Test fun shift_survives_a_degenerate_rate() {
        assertEquals(0.0, NarrowbandPlan.shiftFor(1_000, 0, 0), 0.0)
    }

    // ---- recentring ------------------------------------------------------

    @Test fun recentring_triggers_inside_the_edge_not_at_it() {
        // At the edge exactly, an operator tuning across a band would toggle
        // the link on every step.
        val plan = NarrowbandPlan(128, 16_000, 14_074_000)
        val edge = plan.usableHalfHz
        assertFalse(NarrowbandPlan.needsRecentre(plan, 14_074_000))
        assertFalse(NarrowbandPlan.needsRecentre(plan, 14_074_000 + edge / 2L))
        assertTrue(NarrowbandPlan.needsRecentre(plan, 14_074_000 + edge.toLong()))
    }

    @Test fun anything_needing_a_recentre_is_still_inside_the_covered_span() {
        // The trigger must fire BEFORE coverage is lost, or the operator hits
        // silence and only then does the station move.
        val plan = NarrowbandPlan(128, 16_000, 14_074_000)
        val trigger = (plan.usableHalfHz * NarrowbandPlan.RECENTRE_TRIGGER).toLong()
        assertTrue(plan.covers(14_074_000 + trigger + 1))
    }

    @Test fun offset_within_the_window_is_what_the_client_tunes_to() {
        val plan = NarrowbandPlan(128, 16_000, 14_074_000)
        assertEquals(3_000L, NarrowbandPlan.offsetWithin(plan, 14_077_000))
        assertEquals(-3_000L, NarrowbandPlan.offsetWithin(plan, 14_071_000))
    }

    // ---- cost ------------------------------------------------------------

    @Test fun cost_scales_with_the_format() {
        val plan = NarrowbandPlan(128, 16_000, 0)
        assertEquals(128_000L, plan.bytesPerSecond(DriverProto.IQ_FORMAT_F32))
        assertEquals(64_000L, plan.bytesPerSecond(DriverProto.IQ_FORMAT_S16))
        assertEquals(32_000L, plan.bytesPerSecond(DriverProto.IQ_FORMAT_S8))
    }

    @Test fun a_real_configuration_stays_under_a_megabit_each_way() {
        // A lower radio rate buys a proportionally narrower window.
        val plan = NarrowbandPlan.resolve(960_000, 12_000, 14_074_000)!!
        val bits = plan.bytesPerSecond(DriverProto.IQ_FORMAT_S16) * 8
        assertTrue("$bits bit/s", bits < 600_000)
        assertNotNull(plan)
    }
}
