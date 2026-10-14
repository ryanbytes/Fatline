package dev.scanrelay.app.playback

/**
 * Volume changes must never be translated into disabling scanner playback.
 * Only a separately confirmed scanner-card Stop command may disable audio.
 * Starting audio remains a one-step action (including explicit replay).
 */
internal object ScannerAudioStopPolicy {
    fun permit(enabled: Boolean, confirmedStop: Boolean, fromScannerCard: Boolean): Boolean =
        enabled || (confirmedStop && fromScannerCard)
}
