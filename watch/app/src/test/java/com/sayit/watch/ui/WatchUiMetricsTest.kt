package com.sayit.watch.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z3 Repair 3 必修 2: physical pixels are NOT logical dp — the 480×480 panel
 * maps to a smaller dp width on the real watch. These tests therefore pin the
 * RESPONSIVE layout policy (fraction/weight-based widths via `fillMaxWidth` /
 * `weight`, dp only for heights, padding and the circular main action) instead
 * of asserting any absolute screen-width arithmetic. Real 480×480 px device
 * clipping stays with the later device gate.
 */
class WatchUiMetricsTest {

    @Test
    fun `chips and rows meet the minimum 48 dp touch height`() {
        assertTrue(WatchUiMetrics.WideChipHeightDp >= 48.dp)
        assertTrue(WatchUiMetrics.RowMinHeightDp >= 48.dp)
    }

    @Test
    fun `primary microphone stays within the approved 88 to 96 dp range`() {
        assertTrue(WatchUiMetrics.MainActionSizeDp >= 88.dp)
        assertTrue(WatchUiMetrics.MainActionSizeDp <= 96.dp)
    }

    @Test
    fun `wide chip fills the padded parent width instead of a fixed dp value`() {
        assertEquals(1f, WatchUiMetrics.WideChipWidthFraction)
    }

    @Test
    fun `action spacing stays compact on the round screen`() {
        assertTrue(WatchUiMetrics.HalfChipWeight > 0f)
        assertTrue(WatchUiMetrics.HalfChipGapDp > 0.dp)
        // Two equally weighted chips always share the row regardless of the
        // device's dp width; the dp gap must stay small next to any plausible
        // screen width so both chips keep usable, roughly equal touch targets.
        assertTrue(WatchUiMetrics.HalfChipGapDp <= 16.dp)
    }

    @Test
    fun `screen side padding keeps primary controls inside a round safe area`() {
        assertTrue(WatchUiMetrics.ScreenSidePaddingDp >= 16.dp)
    }

    @Test
    fun `recording duration uses minute second display without a raw ms label`() {
        assertEquals("00:00", formatRecordingDuration(0))
        assertEquals("00:01", formatRecordingDuration(1_999))
        assertEquals("01:05", formatRecordingDuration(65_000))
    }

    @Test
    fun `recording duration derives from captured samples using wav rounding`() {
        assertEquals("00:00", formatRecordingDurationFromSamples(0))
        assertEquals("00:01", formatRecordingDurationFromSamples(16_000))
        assertEquals("00:01", formatRecordingDurationFromSamples(29_472))
    }

    @Test
    fun `low power presentation begins at the configured captured duration`() {
        assertFalse(isLowPowerRecording(sampleCount = 159_999, afterSeconds = 10))
        assertTrue(isLowPowerRecording(sampleCount = 160_000, afterSeconds = 10))
        assertFalse(isLowPowerRecording(sampleCount = 2_879_999, afterSeconds = 180))
        assertTrue(isLowPowerRecording(sampleCount = 2_880_000, afterSeconds = 180))
    }

}
