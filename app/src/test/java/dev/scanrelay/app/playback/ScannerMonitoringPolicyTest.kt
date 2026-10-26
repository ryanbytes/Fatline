package dev.scanrelay.app.playback

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerMonitoringPolicyTest {
    @Test fun taskSwipeKeepsMonitoringWithNoActiveAudio() {
        assertTrue(ScannerMonitoringPolicy.keepOnTaskRemoved(1, 0))
        assertTrue(ScannerMonitoringPolicy.keepOnTaskRemoved(0, 1))
        assertTrue(ScannerMonitoringPolicy.keepOnTaskRemoved(2, 2))
    }

    @Test fun noActiveScannersPermitsNormalCleanup() {
        assertFalse(ScannerMonitoringPolicy.keepOnTaskRemoved(0, 0))
    }

    @Test fun aggregateMixCountsRepresentConcurrentMediaWithoutAppIdentifiers() {
        val counts = AudioMixDiagnostics.summarize(
            listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        )
        assertEquals(4, counts.activeStreams)
        assertEquals(2, counts.mediaStreams)
        assertEquals(1, counts.gameStreams)
        assertEquals(1, counts.navigationStreams)
        assertEquals(0, AudioMixDiagnostics.summarize(emptyList()).activeStreams)
    }
}
