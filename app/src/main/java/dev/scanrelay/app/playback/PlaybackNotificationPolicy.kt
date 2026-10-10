package dev.scanrelay.app.playback

/**
 * The foreground scanner notification is refreshed for every active scanner
 * state change, not only for new talkgroups. Resolve the color tag only when
 * the playing item or its immutable configuration input changes. The service
 * owns this cache on its main thread; null playback clears stale color.
 */
internal class PlayingTagColorCache {
    private var lastMediaId: String? = null
    private var lastCall: dev.scanrelay.app.model.RadioCall? = null
    private var lastSystems: List<dev.scanrelay.app.model.SystemConfig>? = null
    private var lastTagColors: Map<String, String>? = null
    private var color: dev.scanrelay.app.ui.UiAccentRgb? = null

    fun colorFor(
        mediaId: String?,
        call: dev.scanrelay.app.model.RadioCall?,
        server: dev.scanrelay.app.model.ServerScannerState?,
        resolve: (dev.scanrelay.app.model.RadioCall, dev.scanrelay.app.model.ServerScannerState) ->
            dev.scanrelay.app.ui.UiAccentRgb? = dev.scanrelay.app.ui.TagColors::playingCallColor
    ): dev.scanrelay.app.ui.UiAccentRgb? {
        if (mediaId == null || call == null || server == null || call.profileId != server.profile.id) {
            clear()
            return null
        }
        if (lastMediaId == mediaId && lastCall === call &&
            lastSystems === server.systems && lastTagColors === server.tagColors
        ) return color

        color = resolve(call, server)
        lastMediaId = mediaId
        lastCall = call
        lastSystems = server.systems
        lastTagColors = server.tagColors
        return color
    }

    fun clear() {
        lastMediaId = null
        lastCall = null
        lastSystems = null
        lastTagColors = null
        color = null
    }
}

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
 * A scanner's short transmissions must never change the notification's
 * Android presentation style. Only the displayed content and current call
 * identity change. A new call on the same TG still needs a fresh notification.
 */
internal object ScannerForegroundNotificationPolicy {
    fun needsUpdate(
        previous: PlaybackNotificationText?,
        previousColor: Int?,
        previousPlayingMediaId: String?,
        current: PlaybackNotificationText,
        color: Int?,
        playingMediaId: String?
    ): Boolean = PlaybackNotificationPolicy.needsUpdate(previous, current) ||
        previousColor != color || previousPlayingMediaId != playingMediaId
}


/**
 * FatLine-specific public lock-screen preview. The user wants radio call
 * details visible without changing the phone-wide sensitive-content setting.
 * Only FatLine's own public version includes these details.
 *
 * Null activeCall is the strict idle case: never expose a previously played
 * call or a queued call as though it is receiving right now.
 */
internal data class ScannerPublicNotification(
    val title: String,
    val summary: String,
    val expanded: String
)

internal object ScannerPublicLockScreenPolicy {
    fun display(
        current: PlaybackNotificationText,
        activeCall: dev.scanrelay.app.model.RadioCall?,
        queuedCount: Int
    ): ScannerPublicNotification {
        if (activeCall == null) return ScannerPublicNotification(
            current.title,
            current.subtitle,
            current.subtitle
        )
        val title = activeCall.talkgroupLabel.ifBlank { "Radio traffic" }
        val location = listOfNotNull(
            activeCall.serverName.takeIf(String::isNotBlank),
            activeCall.systemLabel.takeIf(String::isNotBlank),
            "TG ${activeCall.talkgroupRef}",
            if (queuedCount > 0) "$queuedCount queued" else null
        ).joinToString(" · ")
        val transcript = activeCall.transcript?.trim()?.takeIf(String::isNotBlank)
            ?.take(180)
        return ScannerPublicNotification(
            title = title,
            summary = location,
            expanded = if (transcript != null) "$location\n$transcript" else location
        )
    }
}
