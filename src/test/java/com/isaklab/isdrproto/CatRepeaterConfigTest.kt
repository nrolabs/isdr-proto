package com.isaklab.isdrproto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CatRepeaterConfigTest {
    private fun crossTone() = CatRepeaterConfig(
        duplex = CatRepeater.DUPLEX_MINUS,
        offsetHz = 600_000,
        txKind = CatRepeater.TONE_CTCSS,
        txValue = 885,
        txPolarity = CatRepeater.DCS_NORMAL,
        rxKind = CatRepeater.TONE_DCS,
        rxValue = 23,
        rxPolarity = CatRepeater.DCS_INVERTED,
    )

    @Test fun payloadIsByteExactBigEndian() {
        val encoded = crossTone().encode()
        assertArrayEquals(
            byteArrayOf(
                0x01,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x09, 0x27, 0xC0.toByte(),
                0x01, 0x00, 0x00, 0x03, 0x75,
                0x00,
                0x02, 0x00, 0x00, 0x00, 0x17,
                0x01,
            ),
            encoded,
        )
        assertEquals(crossTone(), CatRepeaterConfig.decode(encoded))
    }

    @Test fun decodeRejectsWrongLengthAndNoncanonicalValues() {
        val valid = crossTone().encode()
        assertNull(CatRepeaterConfig.decode(valid.copyOf(20)))
        assertNull(CatRepeaterConfig.decode(valid.copyOf(22)))

        val badTone = valid.clone().also { it[13] = 0x76 }
        assertNull(CatRepeaterConfig.decode(badTone))
        val badKind = valid.clone().also { it[15] = 9 }
        assertNull(CatRepeaterConfig.decode(badKind))
    }

    @Test fun encodeNeverRoundsOrRepairsInvalidState() {
        val invalid = crossTone().copy(offsetHz = 600_001)
        try {
            invalid.encode()
            fail("invalid offset must not be encoded")
        } catch (_: IllegalArgumentException) {
            // Expected: rejection is part of the contract.
        }
    }
}
