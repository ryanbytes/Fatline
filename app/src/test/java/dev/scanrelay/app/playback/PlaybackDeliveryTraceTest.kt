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

    @Test fun buttonCallbacksAndHardwareKeysAreDistinctTimelineEvents() {
        val journal = PlaybackDeliveryJournal()
        journal.record("hardware_volume_key", "direction=down repeat=0", timeMs = 1_000)
        journal.record("scanner_audio_button_click", "before=true requested=false", timeMs = 2_000)
        journal.record("audio_enabled_changed", "source=scanner_card before=true after=false changed=true", timeMs = 3_000)
        val text = journal.reportSince(0)
        assertTrue(text.contains("hardware_volume_key direction=down repeat=0"))
        assertTrue(text.contains("scanner_audio_button_click before=true requested=false"))
        assertTrue(text.contains("audio_enabled_changed source=scanner_card"))
    }

    @Test fun outputSinkEvidenceIsSeparateFromPlayerProgress() {
        val journal = PlaybackDeliveryJournal()
        journal.record("player_playing", "state=3 playing=true", timeMs = 1000)
        journal.record("audio_track_initialized", "route=speaker stream_muted=false gain=100 track=true sink_advanced=false", timeMs = 2000)
        journal.record("audio_sink_advancing", "route=speaker stream_muted=false gain=100 track=true sink_advanced=true", timeMs = 3000)
        val text = journal.reportSince(0)
        assertTrue(text.contains("audio_track_initialized route=speaker"))
        assertTrue(text.contains("audio_sink_advancing route=speaker"))
    }

    @Test fun taskRemovalReportsWhetherForegroundMonitoringWasPreserved() {
        val journal = PlaybackDeliveryJournal()
        journal.record("task_removed_monitoring_continued", "active_profiles=1 foreground=true", timeMs = 1_000)
        journal.record("task_removed_default_stop", "active_profiles=0 foreground=false", timeMs = 2_000)
        val report = journal.reportSince(0)
        assertTrue(report.contains("task_removed_monitoring_continued active_profiles=1 foreground=true"))
        assertTrue(report.contains("task_removed_default_stop active_profiles=0 foreground=false"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryEventNamesCannotEnterPrivateUpload() {
        PlaybackDeliveryJournal().record("call_12345 confidential")
    }
}
