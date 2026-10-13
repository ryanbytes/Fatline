package dev.scanrelay.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDeliveryTraceTest {
    @Test fun boundedTimelineKeepsOnlyWhitelistedEventsAndMarksOverwrites() {
        val journal = PlaybackDeliveryJournal(limit = 2)
        val initial = journal.sequence()
        journal.record("activity_hidden", timeMs = 1_000)
        journal.record("player_buffering", "state=2 queued=1", timeMs = 2_000)
        journal.record("activity_visible", timeMs = 3_000)
        val report = journal.reportSince(initial)
        assertTrue(report.contains("events / overwritten): 2 / 1"))
        assertTrue(report.contains("1970-01-01T00:00:02Z player_buffering state=2 queued=1"))
        assertTrue(report.contains("1970-01-01T00:00:03Z activity_visible"))
        assertFalse(report.contains("activity_hidden"))
        assertFalse(journal.reportSince(journal.sequence()).contains("player_buffering"))
    }

    
    @Test fun liveCallIdentifiersAndAndroidAutoRequestsAreCorrelatable() {
        val journal = PlaybackDeliveryJournal(limit = 4)
        journal.record(
            "live_call_received",
            callId = 9123L,
            systemRef = 4L,
            talkgroupRef = 27L,
            timeMs = 1_000
        )
        journal.record("android_auto_channel_selected", systemRef = 4L, talkgroupRef = 27L, timeMs = 2_000)
        val report = journal.reportSince(0)
        assertTrue(report.contains("live_call_received call=9123 system=4 talkgroup=27"))
        assertTrue(report.contains("android_auto_channel_selected system=4 talkgroup=27"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryEventNamesCannotEnterPrivateUpload() {
        PlaybackDeliveryJournal().record("call_12345 confidential")
    }
}
