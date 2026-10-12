package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCallLifecycleMetricsTest {
    @Test
    fun independentCountersAndImmutableSnapshots() {
        val metrics = LiveCallLifecycleCounters()
        val before = metrics.snapshot()
        metrics.received()
        metrics.received()
        metrics.routed()
        metrics.filtered()
        metrics.missingAudio()
        metrics.dispatchFailed()
        metrics.suppressed()
        metrics.duplicate()
        metrics.enqueued()
        metrics.progressVerified()
        metrics.completed()
        metrics.removed()
        metrics.playerError()
        val after = metrics.snapshot()
        assertEquals(LiveCallLifecycleSnapshot(), before)
        assertEquals(2L, after.received)
        assertEquals(1L, after.routed)
        assertEquals(1L, after.filtered)
        assertEquals(1L, after.missingAudio)
        assertEquals(1L, after.dispatchFailures)
        assertEquals(1L, after.suppressed)
        assertEquals(1L, after.duplicates)
        assertEquals(1L, after.enqueued)
        assertEquals(1L, after.started)
        assertEquals(1L, after.completed)
        assertEquals(1L, after.removed)
        assertEquals(1L, after.playerErrors)
    }

    @Test
    fun playbackProgressClassifiesForegroundVersusBackground() {
        val metrics = LiveCallLifecycleCounters()
        metrics.activityVisible(false)
        metrics.progressVerified()
        metrics.activityVisible(true)
        metrics.progressVerified()
        metrics.progressVerified()
        metrics.activityVisible(false)
        metrics.progressVerified()
        val result = metrics.snapshot()
        assertEquals(4L, result.started)
        assertEquals(2L, result.verifiedWhileForeground)
        assertEquals(2L, result.verifiedWhileBackground)
    }
}
