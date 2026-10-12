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

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryEventNamesCannotEnterPrivateUpload() {
        PlaybackDeliveryJournal().record("call_12345 confidential")
    }
}
