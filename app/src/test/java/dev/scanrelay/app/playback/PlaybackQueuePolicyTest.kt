package dev.scanrelay.app.playback

import dev.scanrelay.app.model.CallKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueuePolicyTest {
    private fun id(kind: String, n: Int) = "call:p1:$kind:$n:1:1:$n"

    @Test
    fun reconnectCannotQueueDuplicateLiveCallOrEvictOtherWaitingCalls() {
        val existing = listOf(id("live", 10), id("live", 11), id("replay", 10))
        assertFalse(
            PlaybackQueuePolicy.shouldEnqueueLiveCall(
                "p1", 10L, emptySet(), existing
            )
        )
        assertTrue(
            PlaybackQueuePolicy.shouldEnqueueLiveCall(
                "p1", 12L, emptySet(), existing
            )
        )
    }

    @Test
    fun recentlyPlayedLiveCallsAreNotReplayedWhenServerResendsBacklog() {
        val recent = setOf(CallKey("p1", 45L))
        assertFalse(PlaybackQueuePolicy.shouldEnqueueLiveCall("p1", 45L, recent, emptyList()))
        assertTrue(PlaybackQueuePolicy.shouldEnqueueLiveCall("p2", 45L, recent, emptyList()))
        assertTrue(PlaybackQueuePolicy.shouldEnqueueLiveCall("p1", 46L, recent, emptyList()))
    }

    @Test
    fun replayEntriesAndMalformedIdsAreNotMistakenForLiveDuplicates() {
        assertEquals(null, PlaybackQueuePolicy.liveCallKey("call:p1:replay:1:2:3:token"))
        assertEquals(null, PlaybackQueuePolicy.liveCallKey("call:p1:live:not-a-number:2:3:token"))
        assertEquals(null, PlaybackQueuePolicy.liveCallKey("call:p1:live:12"))
        assertEquals(CallKey("p1", 12L), PlaybackQueuePolicy.liveCallKey("call:p1:live:12:2:3:token"))
        assertTrue(
            PlaybackQueuePolicy.shouldEnqueueLiveCall(
                "p1", 12L, emptySet(), listOf("call:p1:replay:12:2:3:token")
            )
        )
    }

    @Test
    fun pausingScannerFiltersOnlyItsLiveMediaAndPreservesManualReplay() {
        val media = listOf(
            id("live", 1),
            id("replay", 2),
            "call:p2:live:3:1:1:3",
            "call:p2:replay:4:1:1:4"
        )
        val retained = media.filterNot { PlaybackQueuePolicy.isLiveCallForProfile(it, "p1") }
        assertEquals(
            listOf(id("replay", 2), "call:p2:live:3:1:1:3", "call:p2:replay:4:1:1:4"),
            retained
        )
    }

    @Test
    fun malformedMediaIdentifiersAreNeverMatchedAsLiveCalls() {
        assertEquals(false, PlaybackQueuePolicy.isLiveCallForProfile("live:1", "p1"))
        assertEquals(false, PlaybackQueuePolicy.isLiveCallForProfile("call:p1:live", "p1"))
        assertEquals(false, PlaybackQueuePolicy.isLiveCallForProfile("call:p1:replay:3", "p1"))
        assertEquals(false, PlaybackQueuePolicy.isLiveCallForProfile("call:p2:live:3", "p1"))
    }

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
    fun immediateReplayInsertsBeforeCurrentWithoutDiscardingQueue() {
        val media = listOf(id("live", 1), id("live", 2), id("replay", 3))
        val position = PlaybackQueuePolicy.immediateInsertIndex(media.size, 1)
        val updated = media.toMutableList().apply { add(position, id("replay", 4)) }
        assertEquals(listOf(id("live", 1), id("replay", 4), id("live", 2), id("replay", 3)), updated)
        assertEquals(1, position)
    }

    @Test
    fun immediateReplayIsFirstWhenQueueHasNoSelectedItem() {
        assertEquals(0, PlaybackQueuePolicy.immediateInsertIndex(3, -1))
        assertEquals(0, PlaybackQueuePolicy.immediateInsertIndex(0, -1))
        assertEquals(0, PlaybackQueuePolicy.immediateInsertIndex(3, 12))
    }

    @Test
    fun serviceRecoveryKeepsCurrentAndPendingCallsButNotCompletedOnes() {
        val mediaIds = listOf(id("live", 1), id("live", 2), id("live", 3), id("replay", 4))
        val first = PlaybackQueuePolicy.recoveryStartIndex(mediaIds.size, 1)
        assertEquals(listOf(id("live", 2), id("live", 3), id("replay", 4)), mediaIds.drop(first))
        assertEquals(0, PlaybackQueuePolicy.recoveryStartIndex(2, -1))
        assertEquals(0, PlaybackQueuePolicy.recoveryStartIndex(0, -1))
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
