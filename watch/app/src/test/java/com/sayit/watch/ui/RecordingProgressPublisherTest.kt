package com.sayit.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingProgressPublisherTest {
    @Test
    fun `publishes at most once for each captured whole second`() {
        val publisher = RecordingProgressPublisher(sampleRate = 16_000)
        publisher.reset()

        assertNull(publisher.next(320))
        assertNull(publisher.next(15_999))
        assertEquals(16_000, publisher.next(16_000))
        assertNull(publisher.next(16_640))
        assertEquals(32_000, publisher.next(32_000))
    }

    @Test
    fun `reset starts a new recording without carrying the old second`() {
        val publisher = RecordingProgressPublisher(sampleRate = 16_000)
        assertEquals(160_000, publisher.next(160_000))
        publisher.reset()
        assertNull(publisher.next(8_000))
        assertEquals(16_000, publisher.next(16_000))
    }
}
