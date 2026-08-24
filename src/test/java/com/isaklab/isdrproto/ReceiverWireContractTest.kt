package com.isaklab.isdrproto

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverWireContractTest {
    @Test
    fun sevenDdcWireCeilingAndAdditionalStreamMeaningAreExact() {
        assertNull(ReceiverWireContract.stateError(7, 0, 0b1111110))
        assertTrue(ReceiverWireContract.stateError(8, 0, 0) != null)
        assertTrue(ReceiverWireContract.stateError(7, 0, 0b0000001) != null)
        assertTrue(ReceiverWireContract.stateError(2, 0, 0b0100) != null)
    }

    @Test
    fun reductionAndActiveSelectionCannotInvalidateExistingStreams() {
        assertTrue(ReceiverWireContract.countError(0, 0b1000, 2) != null)
        assertTrue(ReceiverWireContract.activeReceiverError(4, 0b0010, 1) != null)
        assertNull(ReceiverWireContract.activeReceiverError(4, 0b0010, 2))
    }
}
