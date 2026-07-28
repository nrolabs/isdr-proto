package com.isaklab.isdrproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No two opcodes may share a value.
 *
 * A collision does not fail — it dispatches the WRONG handler. Three of them
 * shipped at once when the narrowband commands were added by hand:
 *
 *   0x2A  CMD_SET_NARROWBAND   vs CMD_SET_FREQUENCY2
 *   0x2B  CMD_TX_IQ_NARROW     vs CMD_SET_RX_STREAM_MASK
 *   0x8A  EV_NARROWBAND        vs EV_SHM_RESULT
 *
 * The last one killed every local session the moment the shared-memory ring
 * armed: the driver's EV_SHM_RESULT was parsed as EV_NARROWBAND, which reads
 * an i32 out of a one-byte payload and threw BufferUnderflowException — a
 * null message, logged as "link lost: null". The other two would have keyed
 * the wrong control on a radio.
 *
 * Reflection rather than a hand-kept list: a list would be updated by the
 * same person who forgot to check for a free value in the first place.
 */
class OpcodeUniquenessTest {

    private data class Op(val name: String, val value: Int)

    private fun opcodes(prefix: String): List<Op> =
        DriverProto::class.java.declaredFields
            .filter { it.name.startsWith(prefix) && it.type == Int::class.javaPrimitiveType }
            .map { it.isAccessible = true; Op(it.name, it.getInt(DriverProto)) }

    private fun assertUnique(prefix: String) {
        val ops = opcodes(prefix)
        assertTrue("no $prefix* opcodes found — reflection broke, not the protocol", ops.size > 5)
        val clashes = ops.groupBy { it.value }
            .filterValues { it.size > 1 }
            .map { (v, names) -> "0x%02x = %s".format(v, names.joinToString(", ") { it.name }) }
        assertEquals("opcodes sharing a value:\n  " + clashes.joinToString("\n  "), 0, clashes.size)
    }

    @Test fun commands_are_unique() = assertUnique("CMD_")

    @Test fun events_are_unique() = assertUnique("EV_")

    @Test fun commands_and_events_do_not_overlap() {
        // They travel in opposite directions, so an overlap is survivable —
        // but the ranges are deliberately disjoint (commands below 0x80,
        // events at or above it) and a stray value means a typo.
        for (c in opcodes("CMD_")) {
            assertTrue("${c.name} = 0x%02x is in the event range".format(c.value), c.value < 0x80)
        }
        for (e in opcodes("EV_")) {
            assertTrue("${e.name} = 0x%02x is in the command range".format(e.value), e.value >= 0x80)
        }
    }

    @Test fun opcodes_fit_the_single_byte_the_frame_header_carries() {
        for (o in opcodes("CMD_") + opcodes("EV_")) {
            assertTrue("${o.name} = ${o.value} does not fit in a byte", o.value in 0..255)
        }
    }
}
