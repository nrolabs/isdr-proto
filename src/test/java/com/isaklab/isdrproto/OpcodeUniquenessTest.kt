package com.isaklab.isdrproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every opcode must be unique.
 *
 * Two constants sharing a value is invisible: the dispatch simply runs the
 * first branch that matches, so one command silently performs another's work.
 * It has happened in this protocol before, and nothing was watching. Reading
 * the constants back by reflection means a new opcode is covered the moment it
 * is declared, without anyone remembering to extend a list.
 */
class OpcodeUniquenessTest {

    private fun constants(prefix: String): Map<String, Int> =
        DriverProto::class.java.declaredFields
            .filter { it.name.startsWith(prefix) && it.type == Int::class.javaPrimitiveType }
            .associate { it.isAccessible = true; it.name to it.getInt(DriverProto) }

    private fun assertNoDuplicates(prefix: String) {
        val byValue = constants(prefix).entries.groupBy({ it.value }, { it.key })
        val clashes = byValue.filterValues { it.size > 1 }
        assertTrue(
            "$prefix opcodes sharing a value: " +
                clashes.entries.joinToString { "0x%02X -> %s".format(it.key, it.value) },
            clashes.isEmpty(),
        )
    }

    @Test fun commandOpcodesAreUnique() = assertNoDuplicates("CMD_")

    @Test fun eventOpcodesAreUnique() = assertNoDuplicates("EV_")

    /**
     * Commands and events travel in the same byte on the wire, so the two
     * spaces must not overlap either.
     */
    @Test fun commandsAndEventsDoNotOverlap() {
        val shared = constants("CMD_").values.toSet() intersect constants("EV_").values.toSet()
        assertTrue("opcode used by both a command and an event: $shared", shared.isEmpty())
    }

    /** Feature bits are OR-ed into one word; two features on one bit is one feature. */
    @Test fun featureBitsAreDistinctPowersOfTwo() {
        val feats = constants("FEAT_")
        feats.forEach { (name, v) ->
            assertTrue("$name = $v is not a single bit", v > 0 && (v and (v - 1)) == 0)
        }
        assertEquals(
            "two features share a bit: $feats",
            feats.size, feats.values.toSet().size,
        )
    }
}
