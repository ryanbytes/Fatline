package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNotificationPolicyTest {
    @Test
    fun activePlaybackShowsCurrentCall() {
        assertEquals(
            PlaybackNotificationText("Dispatch", "Server · County"),
            PlaybackNotificationPolicy.display(true, "Dispatch", "Server · County")
        )
    }

    @Test
    fun stoppedOrPausedPlaybackDoesNotShowStaleCallMetadata() {
        assertEquals(
            PlaybackNotificationText("FatLine", "Waiting for traffic"),
            PlaybackNotificationPolicy.display(false, "Earlier dispatch", "Earlier server")
        )
    }

    @Test
    fun missingActiveCallMetadataGetsNeutralFallbacks() {
        assertEquals(
            PlaybackNotificationText("Radio traffic", "Listening"),
            PlaybackNotificationPolicy.display(true, "", null)
        )
    }
}
