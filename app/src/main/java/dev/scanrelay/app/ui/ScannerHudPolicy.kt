package dev.scanrelay.app.ui

import dev.scanrelay.app.model.ConnectionStatus

internal data class ScannerHudFlags(
    val live: Boolean,
    val scan: Boolean,
    val rx: Boolean,
    val holdSystem: Boolean,
    val holdTalkgroup: Boolean,
    val pause: Boolean,
    val headline: String
)

/** Only server connection and actual audio state may light scanner annunciators. */
internal object ScannerHudPolicy {
    fun flags(
        connection: ConnectionStatus,
        paused: Boolean,
        isPlaying: Boolean,
        holdSystem: Boolean,
        holdTalkgroup: Boolean
    ): ScannerHudFlags {
        val connected = connection == ConnectionStatus.CONNECTED
        val live = connected && !paused
        return ScannerHudFlags(
            live = live,
            scan = live && !isPlaying,
            rx = isPlaying,
            holdSystem = holdSystem,
            holdTalkgroup = holdTalkgroup,
            pause = paused,
            headline = when {
                isPlaying -> "PLAYBACK"
                paused -> "PAUSED"
                connected -> "SCANNING"
                else -> "DISCONNECTED"
            }
        )
    }
}
