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


/**
 * A foreground scanner must say "Scanning" on the lock screen even while
 * silent between calls. Media3's last-item metadata is never evidence that
 * audio is currently playing. The scanner service owns the single foreground
 * notification; this policy only chooses its public title and summary.
 */
internal object ScannerLockScreenPolicy {
    fun display(
        activeProfiles: Set<String>,
        state: dev.scanrelay.app.model.ScannerState,
        pausedProfiles: Set<String>,
        playing: PlaybackNotificationText,
        isPlaying: Boolean
    ): PlaybackNotificationText {
        if (activeProfiles.isEmpty()) return playing

        var connected = 0
        var pausedConnected = 0
        for (id in activeProfiles) {
            if (state.servers[id]?.status == dev.scanrelay.app.model.ConnectionStatus.CONNECTED) {
                connected++
                if (id in pausedProfiles) pausedConnected++
            }
        }

        if (connected == 0) {
            return PlaybackNotificationText("FatLine · Connecting", "Waiting for scanner connection")
        }
        val connectedText = if (connected == 1) "1 scanner connected" else "$connected scanners connected"
        val pending = activeProfiles.size - connected
        val pendingText = if (pending > 0) " · $pending connecting" else ""
        if (isPlaying) {
            return PlaybackNotificationText(
                "FatLine · Scanning",
                "Playing: ${playing.title} · $connectedText$pendingText"
            )
        }
        if (pausedConnected == connected) {
            return PlaybackNotificationText(
                "FatLine · Monitoring",
                "$connectedText · Live audio paused$pendingText"
            )
        }
        return PlaybackNotificationText(
            "FatLine · Scanning",
            connectedText + pendingText
        )
    }
}

/**
 * Device and channel notification choices are authoritative. Setting
 * VISIBILITY_PUBLIC in the foreground service cannot override them.
 */
internal object ScannerLockScreenAvailability {
    fun description(appNotificationsEnabled: Boolean, channelEnabled: Boolean?): String = when {
        !appNotificationsEnabled -> "FatLine notifications are blocked by Android."
        channelEnabled == false -> "Scanner playback notifications are turned off."
        channelEnabled == null -> "Start scanning to create the Scanner playback notification channel."
        else -> "Scanner playback notifications are allowed. Android may still hide silent notifications on the lock screen."
    }
}


/**
 * An always-on radio scanner is unlike a music player: it often has no
 * playable item between transmissions, while its monitoring service and
 * transcript alerts must stay active.
 */
internal object ScannerForegroundNotificationPolicy {
    fun usesMediaStyle(isPlaying: Boolean): Boolean = isPlaying

    /** A style transition is meaningful even if text and accent did not change. */
    fun needsUpdate(
        previous: PlaybackNotificationText?,
        previousColor: Int?,
        previousMediaStyle: Boolean?,
        current: PlaybackNotificationText,
        color: Int?,
        mediaStyle: Boolean
    ): Boolean = PlaybackNotificationPolicy.needsUpdate(previous, current) ||
        previousColor != color || previousMediaStyle != mediaStyle
}
