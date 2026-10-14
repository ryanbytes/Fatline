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

    @Test fun whitelistedAudioTransitionEventsCarryStateWithoutDynamicIdentifiers() {
        val journal = PlaybackDeliveryJournal()
        journal.record("audio_enabled_loaded", "source=persisted_preference enabled=true", timeMs = 1000)
        journal.record("audio_enabled_changed", "source=scanner_card before=true after=false changed=true", timeMs = 2000)
        journal.record("player_ready_changed", "when_ready=false reason=1 audio_enabled=false", timeMs = 3000)
        val report = journal.reportSince(0)
        assertTrue(report.contains("audio_enabled_changed source=scanner_card before=true after=false"))
        assertTrue(report.contains("player_ready_changed when_ready=false reason=1 audio_enabled=false"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryEventNamesCannotEnterPrivateUpload() {
        PlaybackDeliveryJournal().record("call_12345 confidential")
    }
}
