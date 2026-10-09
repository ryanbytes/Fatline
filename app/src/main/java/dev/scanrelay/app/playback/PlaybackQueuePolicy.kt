package dev.scanrelay.app.playback

import dev.scanrelay.app.model.CallKey
import dev.scanrelay.app.model.RadioCall

internal object PlaybackQueuePolicy {
    const val RECENT_LIVE_ID_LIMIT = 512

    /** An incoming live call is identified by server profile and call ID, not by a random media token. */
    fun liveCallKey(mediaId: String): CallKey? {
        val parts = mediaId.split(':')
        if (parts.size != 7 || parts[0] != "call" || parts[2] != "live") return null
        val profileId = parts[1].takeIf(String::isNotBlank) ?: return null
        val callId = parts[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return CallKey(profileId, callId)
    }

    fun shouldEnqueueLiveCall(
        profileId: String,
        callId: Long,
        recentlyAccepted: Set<CallKey>,
        mediaIds: List<String>
    ): Boolean {
        if (callId <= 0L) return true
        val key = CallKey(profileId, callId)
        return key !in recentlyAccepted && mediaIds.none { liveCallKey(it) == key }
    }

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

    /** Never restore already completed calls preceding the current media item. */
    fun recoveryStartIndex(mediaCount: Int, currentIndex: Int): Int =
        if (currentIndex in 0 until mediaCount) currentIndex else 0

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

/**
 * Receipts and queued items must never populate Recent. Track an item only
 * after Media3 reports actual playback, and publish it only after the player
 * naturally completes the item (automatic transition or final STATE_ENDED).
 * Manual Skip/Stop/playlist removal cancels the pending completion.
 */
internal class PlayedLiveCallTracker {
    private var startedMediaId: String? = null
    private var startedCall: RadioCall? = null

    fun started(mediaId: String?, call: RadioCall?) {
        if (mediaId != null && PlaybackQueuePolicy.mediaKind(mediaId) == "live" && call != null) {
            startedMediaId = mediaId
            startedCall = call
        } else {
            cancel()
        }
    }

    fun transitioned(automatic: Boolean, nextMediaId: String?, nextCall: RadioCall?, nextPlaying: Boolean): RadioCall? {
        val completed = if (automatic) startedCall else null
        cancel()
        if (nextPlaying) started(nextMediaId, nextCall)
        return completed
    }

    fun ended(): RadioCall? {
        val completed = startedCall
        cancel()
        return completed
    }

    fun cancel() {
        startedMediaId = null
        startedCall = null
    }

    fun cancelIf(mediaId: String) {
        if (startedMediaId == mediaId) cancel()
    }
}
