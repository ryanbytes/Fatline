package dev.scanrelay.app.playback

/**
 * Sanitized, read-only audio output observations. Route is Android's predicted
 * destination for FatLine's media audio; it does not establish that sound was heard.
 */
internal object AudioOutputRoutePolicy {
    private val routeTypes = setOf(
        "speaker", "earpiece", "bluetooth", "wired", "usb", "hdmi", "remote", "other"
    )

    fun combine(types: List<String>): String {
        val safe = types.filter { it in routeTypes }.distinct().sorted()
        return safe.takeIf { it.isNotEmpty() }?.joinToString("+") ?: "unknown"
    }

    fun snapshot(
        route: String, streamMuted: Boolean, gainPercent: Int,
        audioTrackInitialized: Boolean, audioSinkAdvancing: Boolean
    ): String {
        // Validate again: never emit hardware IDs, device names or arbitrary strings.
        val safe = combine(route.split("+"))
        val gain = gainPercent.coerceIn(0, 100)
        return "route=$safe stream_muted=$streamMuted gain=$gain track=$audioTrackInitialized sink_advanced=$audioSinkAdvancing"
    }

    fun errorFamily(name: String): String = when (name) {
        "InitializationException" -> "initialization"
        "WriteException" -> "write"
        "UnexpectedDiscontinuityException" -> "discontinuity"
        else -> "other"
    }
}
