package dev.scanrelay.app.playback

import dev.scanrelay.app.model.CallKey
import dev.scanrelay.app.model.RadioCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueuePolicyTest {
    private fun id(kind: String, n: Int) = "call:p1:$kind:$n:1:1:$n"

    private fun liveCall(n: Long) = RadioCall(
        profileId = "p1", serverName = "Scanner", id = n,
        systemRef = 1, talkgroupRef = 11, systemLabel = "Law",
        talkgroupLabel = "Dispatch $n", dateTime = "2026-10-09T10:00:00Z"
    )

    @Test
    fun queuedAndStartedCallsAreNotRecentUntilNaturalCompletion() {
        val tracker = PlayedLiveCallTracker()
        val call = liveCall(11)
        // A queued arrival never enters this tracker; a Media3 "playing"
        // callback alone is not evidence that the recording was consumed.
        tracker.started(id("live", 11), call)
        assertEquals(null, tracker.transitioned(true, id("live", 12), liveCall(12), true))
        assertEquals(null, tracker.ended()) // still no position movement

        tracker.started(id("live", 11), call, 0L)
        assertTrue(tracker.awaitingProgress(id("live", 11)))
        assertFalse(tracker.observedProgress(id("live", 11), 0L))
        assertTrue(tracker.observedProgress(id("live", 11), 150L))
        val finished = tracker.transitioned(true, id("live", 12), liveCall(12), true)
        assertEquals(11L, finished?.id)
        assertTrue(tracker.awaitingProgress(id("live", 12))) // next call has not progressed
        tracker.observedProgress(id("live", 12), 75L)
        assertEquals(12L, tracker.ended()?.id)
        assertEquals(null, tracker.ended()) // no duplicate
    }

    @Test
    fun manualStopAndRemovingAPlayingCallNeverMarkItPlayed() {
        val tracker = PlayedLiveCallTracker()
        tracker.started(id("live", 1), liveCall(1))
        tracker.observedProgress(id("live", 1), 700L)
        tracker.cancelIf(id("live", 1))
        assertEquals(null, tracker.ended())
        tracker.started(id("live", 2), liveCall(2))
        tracker.observedProgress(id("live", 2), 200L)
        tracker.cancel()
        assertEquals(null, tracker.transitioned(true, null, null, false))
        assertEquals(null, tracker.ended())
    }

    @Test
    fun replayItemsAndUnstartedCallsNeverCountAsPlayedLive() {
        val tracker = PlayedLiveCallTracker()
        tracker.started(id("replay", 1), liveCall(1))
        assertFalse(tracker.observedProgress(id("replay", 1), 1000L))
        assertEquals(null, tracker.ended())
        tracker.started(null, null)
        assertEquals(null, tracker.transitioned(true, id("live", 2), liveCall(2), false))
        assertEquals(null, tracker.ended()) // next item never started
        tracker.started(id("live", 2), liveCall(2))
        assertEquals(null, tracker.ended()) // Media3 ended without rendered progress
        tracker.started(id("live", 2), liveCall(2))
        assertTrue(tracker.observedProgress(id("live", 2), 25L))
        assertEquals(2L, tracker.ended()?.id)
        assertEquals(null, tracker.ended())
    }

    @Test
    fun otherArrivalsAndReplayProgressCannotAdvanceCurrentLiveCall() {
        val tracker = PlayedLiveCallTracker()
        tracker.started(id("live", 8), liveCall(8), 500L)
        assertFalse(tracker.observedProgress(id("live", 9), 900L))
        assertFalse(tracker.observedProgress(id("replay", 8), 900L))
        assertFalse(tracker.observedProgress(id("live", 8), 500L))
        assertTrue(tracker.awaitingProgress(id("live", 8)))
        // Repeated "playing" callback during buffering must not reset evidence
        // or starting position for the same call.
        tracker.started(id("live", 8), liveCall(8), 850L)
        assertTrue(tracker.observedProgress(id("live", 8), 501L))
        tracker.started(id("live", 8), liveCall(8), 1000L)
        assertFalse(tracker.awaitingProgress(id("live", 8)))
        assertEquals(8L, tracker.ended()?.id)
    }

    @Test
    fun autoTransitionRequiresPlaybackProgressAndCannotInventRecentEntries() {
        val tracker = PlayedLiveCallTracker()
        tracker.started(id("live", 1), liveCall(1))
        // The service receives a second media item but the first never rendered.
        assertEquals(null, tracker.transitioned(true, id("live", 2), liveCall(2), false))
        assertEquals(null, tracker.ended())
        tracker.started(id("live", 3), liveCall(3))
        assertEquals(null, tracker.transitioned(false, id("live", 4), liveCall(4), true))
        tracker.observedProgress(id("live", 4), 70L)
        assertEquals(4L, tracker.ended()?.id)
    }

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
