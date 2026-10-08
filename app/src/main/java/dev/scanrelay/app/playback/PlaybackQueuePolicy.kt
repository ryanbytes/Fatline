package dev.scanrelay.app.playback

internal object PlaybackQueuePolicy {
    const val LIVE_LIMIT = 30
    const val REPLAY_LIMIT = 500

    fun removalIndex(
        mediaIds: List<String>,
        currentIndex: Int,
        incomingLiveFeed: Boolean
    ): Int {
        val incomingKind = if (incomingLiveFeed) "live" else "replay"
        val limit = if (incomingLiveFeed) LIVE_LIMIT else REPLAY_LIMIT
        val sameKind = mediaIds.indices.filter { index ->
            mediaKind(mediaIds[index]) == incomingKind
        }
        if (sameKind.size < limit) return -1
        return sameKind.firstOrNull { it != currentIndex } ?: -1
    }

    fun queuedCount(mediaCount: Int, currentIndex: Int): Int {
        if (mediaCount <= 0) return 0
        if (currentIndex !in 0 until mediaCount) return mediaCount
        return mediaCount - currentIndex - 1
    }

    /**
     * Put an explicitly chosen replay before the current call, keeping the
     * interrupted call and all pending calls after it in their existing order.
     */
    fun immediateInsertIndex(mediaCount: Int, currentIndex: Int): Int {
        return if (currentIndex in 0 until mediaCount) currentIndex else 0
    }

    fun firstQueuedIndex(mediaCount: Int, currentIndex: Int): Int {
        return if (currentIndex in 0 until mediaCount) currentIndex + 1 else 0
    }

    fun queuedMediaIds(mediaIds: List<String>, currentIndex: Int): List<String> {
        return mediaIds.drop(firstQueuedIndex(mediaIds.size, currentIndex))
    }

    fun playingMediaId(mediaIds: List<String>, currentIndex: Int, isPlaying: Boolean): String? {
        return mediaIds.getOrNull(currentIndex).takeIf { isPlaying }
    }

    fun isLiveCallForProfile(mediaId: String, profileId: String): Boolean {
        val parts = mediaId.split(':', limit = 4)
        return parts.size == 4 && parts[0] == "call" &&
            parts[1] == profileId && parts[2] == "live" && parts[3].isNotBlank()
    }

    internal fun mediaKind(mediaId: String): String? {
        val parts = mediaId.split(':')
        if (parts.size < 3 || parts[0] != "call") return null
        return parts[2].takeIf { it == "live" || it == "replay" }
    }
}
