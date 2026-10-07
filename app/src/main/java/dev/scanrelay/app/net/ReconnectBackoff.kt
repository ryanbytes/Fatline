package dev.scanrelay.app.net

internal object ReconnectBackoff {
    private const val MAX_ATTEMPT = 6
    private const val MAX_BASE_DELAY_MILLIS = 30_000L
    private const val MAX_JITTER_MILLIS = 349L

    fun delayMillis(attempt: Int, jitterMillis: Long): Long {
        val boundedAttempt = attempt.coerceIn(1, MAX_ATTEMPT)
        val baseDelay = (1_000L shl (boundedAttempt - 1)).coerceAtMost(MAX_BASE_DELAY_MILLIS)
        return baseDelay + jitterMillis.coerceIn(0L, MAX_JITTER_MILLIS)
    }
}
