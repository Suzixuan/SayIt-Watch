package com.sayit.watch.ui

/**
 * Keeps the exact capture count internal while limiting visible timer updates to one per second.
 * A null result means the UI already received a value for the same whole second.
 */
internal class RecordingProgressPublisher(private val sampleRate: Int) {
    private var lastPublishedSecond: Int = 0

    init {
        require(sampleRate > 0)
    }

    fun reset(initialSampleCount: Int = 0) {
        lastPublishedSecond = initialSampleCount.coerceAtLeast(0) / sampleRate
    }

    fun next(sampleCount: Int): Int? {
        val safeCount = sampleCount.coerceAtLeast(0)
        val wholeSecond = safeCount / sampleRate
        if (wholeSecond <= lastPublishedSecond) return null
        lastPublishedSecond = wholeSecond
        return safeCount
    }
}
