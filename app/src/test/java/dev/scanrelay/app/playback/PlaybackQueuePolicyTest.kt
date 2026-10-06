package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueuePolicyTest {
    private fun id(kind: String, n: Int) = "call:p1:$kind:$n:1:1:$n"

    @Test
    fun liveOverflowRemovesOnlyLiveItems() {
        val media = buildList {
            add(id("replay", 1))
            repeat(PlaybackQueuePolicy.LIVE_LIMIT) { add(id("live", it + 1)) }
            add(id("replay", 2))
        }

        val remove = PlaybackQueuePolicy.removalIndex(
            mediaIds = media,
            currentIndex = 0,
            incomingLiveFeed = true
        )

        assertEquals(1, remove)
        assertEquals("live", PlaybackQueuePolicy.mediaKind(media[remove]))
    }

    @Test
    fun replayOverflowDoesNotDiscardQueuedLiveTraffic() {
        val media = buildList {
            add(id("live", 1))
            repeat(PlaybackQueuePolicy.REPLAY_LIMIT) { add(id("replay", it + 1)) }
            add(id("live", 2))
        }

        val remove = PlaybackQueuePolicy.removalIndex(
            mediaIds = media,
            currentIndex = 0,
            incomingLiveFeed = false
        )

        assertEquals(1, remove)
        assertEquals("replay", PlaybackQueuePolicy.mediaKind(media[remove]))
    }

    @Test
    fun liveTrafficDoesNotTrimReplayBacklogBeforeLiveLimit() {
        val media = buildList {
            repeat(200) { add(id("replay", it + 1)) }
            repeat(PlaybackQueuePolicy.LIVE_LIMIT - 1) { add(id("live", it + 1)) }
        }

        assertEquals(
            -1,
            PlaybackQueuePolicy.removalIndex(
                mediaIds = media,
                currentIndex = 0,
                incomingLiveFeed = true
            )
        )
    }
}
