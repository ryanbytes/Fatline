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
    ): Boolean = shouldEnqueueLiveCall(
        profileId, callId, recentlyAccepted, mediaIds.size, mediaIds::get
    )

    /** Look up player media IDs lazily: no temporary full-playlist List per arrival. */
    fun shouldEnqueueLiveCall(
        profileId: String,
        callId: Long,
        recentlyAccepted: Set<CallKey>,
        mediaCount: Int,
        mediaIdAt: (Int) -> String
    ): Boolean {
        if (callId <= 0L) return true
        val key = CallKey(profileId, callId)
        if (key in recentlyAccepted) return false
        for (index in 0 until mediaCount) {
            if (liveCallKey(mediaIdAt(index)) == key) return false
        }
        return true
    }

    const val LIVE_LIMIT = 30
    const val REPLAY_LIMIT = 500

    fun removalIndex(
        mediaIds: List<String>,
        currentIndex: Int,
        incomingLiveFeed: Boolean
    ): Int = removalIndex(mediaIds.size, currentIndex, incomingLiveFeed, mediaIds::get)

    /** Single pass, without a filtered list of up to 500 replay media IDs. */
    fun removalIndex(
        mediaCount: Int,
        currentIndex: Int,
        incomingLiveFeed: Boolean,
        mediaIdAt: (Int) -> String
    ): Int {
        val incomingKind = if (incomingLiveFeed) "live" else "replay"
        val limit = if (incomingLiveFeed) LIVE_LIMIT else REPLAY_LIMIT
        var sameKindCount = 0
        var firstRemovable = -1
        for (index in 0 until mediaCount) {
            if (mediaKind(mediaIdAt(index)) != incomingKind) continue
            sameKindCount++
            if (firstRemovable == -1 && index != currentIndex) firstRemovable = index
        }
        return if (sameKindCount >= limit) firstRemovable else -1
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
        if (!mediaId.startsWith("call:")) return null
        val afterProfile = mediaId.indexOf(':', startIndex = 5)
        if (afterProfile < 0) return null
        val kindStart = afterProfile + 1
        val nextDelimiter = mediaId.indexOf(':', startIndex = kindStart)
        val kindLength = (if (nextDelimiter >= 0) nextDelimiter else mediaId.length) - kindStart
        return when {
            kindLength == 4 && mediaId.regionMatches(kindStart, "live", 0, 4) -> "live"
            kindLength == 6 && mediaId.regionMatches(kindStart, "replay", 0, 6) -> "replay"
            else -> null
        }
    }
}


/** One playlist walk provides current playback, queued preview and stale-ID cleanup. */
internal data class PlaybackQueueProjection(
    val activeMediaIds: Set<String>,
    val playingCall: RadioCall?,
    val queuedCalls: List<QueuedCall>
)

internal object PlaybackQueueProjectionPolicy {
    fun project(
        mediaCount: Int,
        currentIndex: Int,
        isPlaying: Boolean,
        mediaIdAt: (Int) -> String,
        callsByMediaId: Map<String, RadioCall>
    ): PlaybackQueueProjection {
        val activeIds = HashSet<String>(mediaCount.coerceAtLeast(0))
        val queue = ArrayList<QueuedCall>()
        val firstQueued = PlaybackQueuePolicy.firstQueuedIndex(mediaCount, currentIndex)
        var playingCall: RadioCall? = null
        for (index in 0 until mediaCount) {
            val mediaId = mediaIdAt(index)
            activeIds.add(mediaId)
            if (index == currentIndex && isPlaying) {
                playingCall = callsByMediaId[mediaId]
            }
            if (index >= firstQueued) {
                callsByMediaId[mediaId]?.let { call ->
                    queue.add(QueuedCall(call, PlaybackQueuePolicy.mediaKind(mediaId) == "live"))
                }
            }
        }
        return PlaybackQueueProjection(activeIds, playingCall, queue)
    }
}

/**
 * Recent means a completed, actually progressed live recording, never merely
 * an arrived/queued media item or a transient Media3 ready/playing callback.
 * Keep position evidence tied to the media ID so later arrivals cannot
 * advance a different call's Recent state.
 */
internal class PlayedLiveCallTracker {
    private var startedMediaId: String? = null
    private var startedCall: RadioCall? = null
    private var startPositionMs = 0L
    private var playbackAdvanced = false

    fun started(mediaId: String?, call: RadioCall?, positionMs: Long = 0L) {
        if (mediaId != null && PlaybackQueuePolicy.mediaKind(mediaId) == "live" && call != null) {
            // isPlaying can toggle during buffering/pause. Never reset already
            // observed progress for the same media item.
            if (mediaId != startedMediaId) {
                startedMediaId = mediaId
                startedCall = call
                startPositionMs = positionMs.coerceAtLeast(0L)
                playbackAdvanced = false
            }
        } else {
            cancel()
        }
    }

    /** Returns true after the currently playing recording's position has moved. */
    fun observedProgress(mediaId: String?, positionMs: Long): Boolean {
        if (mediaId == null || mediaId != startedMediaId) return false
        if (positionMs > startPositionMs && positionMs >= 0L) playbackAdvanced = true
        return playbackAdvanced
    }

    fun awaitingProgress(mediaId: String?): Boolean =
        mediaId != null && mediaId == startedMediaId && !playbackAdvanced

    fun transitioned(
        automatic: Boolean, nextMediaId: String?, nextCall: RadioCall?,
        nextPlaying: Boolean, nextPositionMs: Long = 0L
    ): RadioCall? {
        val completed = if (automatic && playbackAdvanced) startedCall else null
        cancel()
        if (nextPlaying) started(nextMediaId, nextCall, nextPositionMs)
        return completed
    }

    fun ended(): RadioCall? {
        val completed = if (playbackAdvanced) startedCall else null
        cancel()
        return completed
    }

    fun cancel() {
        startedMediaId = null
        startedCall = null
        startPositionMs = 0L
        playbackAdvanced = false
    }

    fun cancelIf(mediaId: String) {
        if (startedMediaId == mediaId) cancel()
    }
}
