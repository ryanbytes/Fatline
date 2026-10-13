package dev.scanrelay.app.playback

import java.time.Instant

/**
 * Bounded, private diagnostic timeline. Fixed event names, scalar player state,
 * timestamps, and numeric call/system/talkgroup references support delivery
 * correlation. Never include channel labels, server URLs, transcripts, or audio.
 */
internal class PlaybackDeliveryJournal(private val limit: Int = 512) {
    private data class Entry(
        val sequence: Long,
        val timeMs: Long,
        val event: String,
        val state: String,
        val callId: Long?,
        val systemRef: Long?,
        val talkgroupRef: Long?
    )
    private val entries = ArrayDeque<Entry>()
    private var nextSequence = 0L

    @Synchronized
    fun sequence(): Long = nextSequence

    @Synchronized
    fun record(
        event: String,
        state: String = "",
        timeMs: Long = System.currentTimeMillis(),
        callId: Long? = null,
        systemRef: Long? = null,
        talkgroupRef: Long? = null
    ) {
        require(event in EVENTS)
        nextSequence++
        entries.addLast(Entry(
            nextSequence, timeMs, event, state,
            callId?.takeIf { it > 0L },
            systemRef?.takeIf { it > 0L },
            talkgroupRef?.takeIf { it > 0L }
        ))
        while (entries.size > limit) entries.removeFirst()
    }

    @Synchronized
    fun reportSince(afterSequence: Long): String = buildString {
        val kept = entries.filter { it.sequence > afterSequence }
        val dropped = entries.firstOrNull()?.let {
            (it.sequence - afterSequence - 1L).coerceAtLeast(0L)
        } ?: (nextSequence - afterSequence).coerceAtLeast(0L)
        appendLine("Playback timeline (bounded, private; events / overwritten): ${kept.size} / $dropped")
        for (event in kept) {
            append("  ").append(Instant.ofEpochMilli(event.timeMs))
                .append(' ').append(event.event)
            if (event.callId != null) append(" call=").append(event.callId)
            if (event.systemRef != null) append(" system=").append(event.systemRef)
            if (event.talkgroupRef != null) append(" talkgroup=").append(event.talkgroupRef)
            if (event.state.isNotEmpty()) append(' ').append(event.state)
            appendLine()
        }
    }

    companion object {
        private val EVENTS = setOf(
            "activity_visible", "activity_hidden", "service_created", "service_destroyed",
            "service_resume", "sticky_restart", "player_playing", "player_not_playing",
            "player_buffering", "player_error", "player_ready_changed", "stall_recovery",
            "live_call_received", "live_call_routed", "live_call_filtered",
            "live_call_missing_audio", "live_call_encryption_pending",
            "live_call_suppressed", "live_call_duplicate",
            "live_call_enqueued", "live_call_started", "live_call_progress",
            "live_call_completed", "live_call_removed", "live_call_player_error",
            "live_call_dispatch_failed", "android_auto_library_root",
            "android_auto_library_children", "android_auto_channel_selected",
            "android_auto_profile_selected", "audio_state_restored", "audio_toggle_requested",
            "audio_toggle_applied", "playback_stop_requested", "playback_stop_applied",
            "media_session_play_pause_requested"
        )
    }
}

internal object PlaybackDeliveryTrace {
    val journal = PlaybackDeliveryJournal()

    fun record(
        event: String,
        state: String = "",
        timeMs: Long = System.currentTimeMillis(),
        callId: Long? = null,
        systemRef: Long? = null,
        talkgroupRef: Long? = null
    ) = journal.record(event, state, timeMs, callId, systemRef, talkgroupRef)
}
