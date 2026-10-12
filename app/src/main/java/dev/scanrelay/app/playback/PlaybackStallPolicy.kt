package dev.scanrelay.app.playback

/**
 * Recognize a media item stuck buffering while playback is explicitly wanted.
 * No recovery while paused, suppressed, already playing, idle, or between calls.
 * Rate-limit retry attempts for the same item to avoid repeated player resets.
 */
internal class PlaybackStallPolicy(
    private val stallAfterMs: Long = 15_000L,
    private val retryAfterMs: Long = 120_000L
) {
    private var waitingMediaId: String? = null
    private var waitingSinceMs: Long? = null
    private var lastRetryMediaId: String? = null
    private var lastRetryAtMs: Long = 0L

    fun shouldRecover(
        nowMs: Long,
        mediaId: String?,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean,
        playbackSuppressed: Boolean
    ): Boolean {
        if (mediaId == null || !playWhenReady || isPlaying || !isBuffering || playbackSuppressed) {
            waitingMediaId = null
            waitingSinceMs = null
            return false
        }
        if (waitingMediaId != mediaId || waitingSinceMs == null || nowMs < waitingSinceMs!!) {
            waitingMediaId = mediaId
            waitingSinceMs = nowMs
            return false
        }
        if (nowMs - waitingSinceMs!! < stallAfterMs) return false
        if (lastRetryMediaId == mediaId && nowMs >= lastRetryAtMs &&
            nowMs - lastRetryAtMs < retryAfterMs
        ) return false
        lastRetryMediaId = mediaId
        lastRetryAtMs = nowMs
        waitingSinceMs = nowMs
        return true
    }
}
