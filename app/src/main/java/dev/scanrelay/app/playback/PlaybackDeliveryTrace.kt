package dev.scanrelay.app.playback

import java.time.Instant

/**
 * Bounded, private diagnostic timeline. Only fixed event names and scalar player
 * state are recorded. Never include call IDs, channel names, server URLs or audio.
 * Time stamps are needed to align an Activity return with a stalled player.
 */
internal class PlaybackDeliveryJournal(private val limit: Int = 96) {
    private data class Entry(val sequence: Long, val timeMs: Long, val event: String, val state: String)
    private val entries = ArrayDeque<Entry>()
    private var nextSequence = 0L

    @Synchronized
    fun sequence(): Long = nextSequence

    @Synchronized
    fun record(event: String, state: String = "", timeMs: Long = System.currentTimeMillis()) {
        require(event in EVENTS)
        nextSequence++
        entries.addLast(Entry(nextSequence, timeMs, event, state))
        while (entries.size > limit) entries.removeFirst()
    }

    @Synchronized
    fun reportSince(afterSequence: Long): String = buildString {
        val kept = entries.filter { it.sequence > afterSequence }
        val dropped = entries.firstOrNull()?.let {
            (it.sequence - afterSequence - 1L).coerceAtLeast(0L)
        } ?: 0L
        appendLine("Playback timeline (bounded, private; events / overwritten): ${kept.size} / $dropped")
        for (event in kept) {
            append("  ").append(Instant.ofEpochMilli(event.timeMs))
                .append(' ').append(event.event)
            if (event.state.isNotEmpty()) append(' ').append(event.state)
            appendLine()
        }
    }

    companion object {
        private val EVENTS = setOf(
            "activity_visible", "activity_hidden", "service_created", "service_destroyed",
            "service_resume", "sticky_restart", "player_playing", "player_not_playing",
            "player_buffering", "player_error", "stall_recovery",
            "audio_enabled_loaded", "audio_enabled_changed", "player_ready_changed"
        )
    }
}

internal object PlaybackDeliveryTrace {
    val journal = PlaybackDeliveryJournal()
    fun record(event: String, state: String = "") = journal.record(event, state)
}
