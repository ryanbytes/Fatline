package dev.scanrelay.app.playback

internal data class PlaybackNotificationText(
    val title: String,
    val subtitle: String
)

/**
 * Report actual audio playback, not a selected or previously received media item.
 * A paused, buffering, stopped, or completed player must not claim to be playing it.
 */
internal object PlaybackNotificationPolicy {
    /** Identical title/subtitle/queue state needs no duplicate OS notification. */
    fun needsUpdate(previous: PlaybackNotificationText?, current: PlaybackNotificationText): Boolean =
        previous != current

    fun display(isPlaying: Boolean, title: String?, artist: String?): PlaybackNotificationText {
        if (!isPlaying) return PlaybackNotificationText("FatLine", "Waiting for traffic")
        return PlaybackNotificationText(
            title?.takeIf(String::isNotBlank) ?: "Radio traffic",
            artist?.takeIf(String::isNotBlank) ?: "Listening"
        )
    }
}
