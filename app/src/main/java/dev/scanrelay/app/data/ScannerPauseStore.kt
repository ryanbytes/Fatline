package dev.scanrelay.app.data

import android.content.Context

/** The user's explicit Pause choice survives socket replacement and service restarts. */
internal object ScannerPausePolicy {
    fun key(profileId: String): String = "paused_$profileId"

    /** Pausing a live scanner must not prevent a deliberate archived-call replay. */
    fun suppressIncomingAudio(liveFeed: Boolean, paused: Boolean): Boolean = liveFeed && paused
}

class ScannerPauseStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_scanner_pause", Context.MODE_PRIVATE)

    fun isPaused(profileId: String): Boolean =
        prefs.getBoolean(ScannerPausePolicy.key(profileId), false)

    fun setPaused(profileId: String, paused: Boolean) {
        prefs.edit().putBoolean(ScannerPausePolicy.key(profileId), paused).apply()
    }

    fun deleteProfile(profileId: String) {
        prefs.edit().remove(ScannerPausePolicy.key(profileId)).apply()
    }
}
