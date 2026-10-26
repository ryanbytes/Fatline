package dev.scanrelay.app.playback

import android.media.AudioAttributes

/** Task removal is not scanner disconnection. Neither silent playback nor
 * a stale in-memory foreground flag authorizes stopping active monitoring. */
internal object ScannerMonitoringPolicy {
    fun keepOnTaskRemoved(persistedProfiles: Int, connectedProfiles: Int): Boolean =
        persistedProfiles > 0 || connectedProfiles > 0
}

/** The OS may anonymize other apps' player configurations. Aggregate counts
 * cannot prove that FatLine was actually audible or identify another app. */
internal data class AudioMixCounts(
    val activeStreams: Int,
    val mediaStreams: Int,
    val gameStreams: Int,
    val navigationStreams: Int
) {
    fun report(): String =
        "active_streams=$activeStreams media_streams=$mediaStreams" +
            " game_streams=$gameStreams navigation_streams=$navigationStreams"
}

internal object AudioMixDiagnostics {
    fun summarize(activeUsages: List<Int>): AudioMixCounts = AudioMixCounts(
        activeStreams = activeUsages.size,
        mediaStreams = activeUsages.count { it == AudioAttributes.USAGE_MEDIA },
        gameStreams = activeUsages.count { it == AudioAttributes.USAGE_GAME },
        navigationStreams = activeUsages.count {
            it == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
    )
}
