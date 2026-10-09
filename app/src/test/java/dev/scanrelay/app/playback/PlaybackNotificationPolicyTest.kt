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
    fun identicalNotificationTextAndQueueCountDoNotRepublish() {
        val current = PlaybackNotificationText("Dispatch", "County · 2 queued")
        assertEquals(true, PlaybackNotificationPolicy.needsUpdate(null, current))
        assertEquals(false, PlaybackNotificationPolicy.needsUpdate(current, current.copy()))
        assertEquals(true, PlaybackNotificationPolicy.needsUpdate(
            current, current.copy(subtitle = "County · 3 queued")
        ))
        assertEquals(true, PlaybackNotificationPolicy.needsUpdate(
            current, current.copy(title = "Fire dispatch")
        ))
    }

    @Test
    fun missingActiveCallMetadataGetsNeutralFallbacks() {
        assertEquals(
            PlaybackNotificationText("Radio traffic", "Listening"),
            PlaybackNotificationPolicy.display(true, "", null)
        )
    }
}
