package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueuePolicyTest {
    private fun id(kind: String, n: Int) = "call:p1:$kind:$n:1:1:$n"

    @Test
    fun queuedCountExcludesCurrentItem() {
        assertEquals(2, PlaybackQueuePolicy.queuedCount(mediaCount = 5, currentIndex = 2))
    }

    @Test
    fun queuedCountIncludesAllItemsWhenNoCurrentItemIsSelected() {
        assertEquals(3, PlaybackQueuePolicy.queuedCount(mediaCount = 3, currentIndex = -1))
    }

    @Test
    fun clearQueueStartsAfterCurrentCall() {
        assertEquals(3, PlaybackQueuePolicy.firstQueuedIndex(mediaCount = 5, currentIndex = 2))
    }

    @Test
    fun clearQueueRemovesAllItemsWhenNoCurrentCallExists() {
        assertEquals(0, PlaybackQueuePolicy.firstQueuedIndex(mediaCount = 3, currentIndex = -1))
    }

    @Test
    fun queuedPreviewListsCallsAfterCurrentPlayback() {
        val media = listOf(id("live", 1), id("replay", 2), id("live", 3))
        assertEquals(listOf(id("replay", 2), id("live", 3)), PlaybackQueuePolicy.queuedMediaIds(media, 0))
        assertEquals(listOf(id("live", 3)), PlaybackQueuePolicy.queuedMediaIds(media, 1))
        assertEquals(emptyList<String>(), PlaybackQueuePolicy.queuedMediaIds(media, 2))
    }

    @Test
    fun queuedPreviewIncludesWholeQueueWithoutValidCurrentItem() {
        val media = listOf(id("live", 1), id("replay", 2))
        assertEquals(media, PlaybackQueuePolicy.queuedMediaIds(media, -1))
        assertEquals(media, PlaybackQueuePolicy.queuedMediaIds(media, 99))
        assertEquals(emptyList<String>(), PlaybackQueuePolicy.queuedMediaIds(emptyList(), -1))
    }

    @Test
    fun playingMediaIdIsEmptyWhenPlayerIsStopped() {
        assertEquals(null, PlaybackQueuePolicy.playingMediaId(listOf(id("live", 1)), 0, isPlaying = false))
    }

    @Test
    fun playingMediaIdTracksCurrentPlayingItem() {
        val mediaIds = listOf(id("live", 1), id("replay", 2))

        assertEquals(id("replay", 2), PlaybackQueuePolicy.playingMediaId(mediaIds, 1, isPlaying = true))
        assertEquals(null, PlaybackQueuePolicy.playingMediaId(mediaIds, 4, isPlaying = true))
    }

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
    fun liveOverflowKeepsCurrentPlayingCallSelected() {
        val media = List(PlaybackQueuePolicy.LIVE_LIMIT) { index -> id("live", index + 1) }

        val remove = PlaybackQueuePolicy.removalIndex(
            mediaIds = media,
            currentIndex = 0,
            incomingLiveFeed = true
        )

        assertEquals(1, remove)
        assertEquals(
            PlaybackQueuePolicy.LIVE_LIMIT - 1,
            PlaybackQueuePolicy.queuedCount(mediaCount = media.size, currentIndex = 0)
        )
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
