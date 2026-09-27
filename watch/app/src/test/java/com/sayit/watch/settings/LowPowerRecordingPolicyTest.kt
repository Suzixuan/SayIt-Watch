package com.sayit.watch.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LowPowerRecordingPolicyTest {
    @Test
    fun `default and valid boundaries are stable`() {
        assertEquals(10, LowPowerRecordingPolicy.DEFAULT_AFTER_SECONDS)
        assertEquals(1, LowPowerRecordingPolicy.parseSeconds("1"))
        assertEquals(180, LowPowerRecordingPolicy.parseSeconds(" 180 "))
    }

    @Test
    fun `invalid values cannot escape the recording duration`() {
        assertNull(LowPowerRecordingPolicy.parseSeconds("0"))
        assertNull(LowPowerRecordingPolicy.parseSeconds("181"))
        assertNull(LowPowerRecordingPolicy.parseSeconds("ten"))
        assertEquals(10, LowPowerRecordingPolicy.sanitizeStored(0))
        assertEquals(10, LowPowerRecordingPolicy.sanitizeStored(181))
    }
}
