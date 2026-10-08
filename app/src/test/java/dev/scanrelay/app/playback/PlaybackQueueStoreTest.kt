package dev.scanrelay.app.playback

import dev.scanrelay.app.model.RadioCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueStoreTest {
    private fun call(id: Long, profile: String = "one") = RadioCall(
        profileId = profile,
        serverName = "Home scanner",
        id = id,
        systemRef = 4,
        talkgroupRef = 120,
        systemLabel = "County",
        talkgroupLabel = "Dispatch $id",
        dateTime = "2026-10-08T08:00:00Z",
        audioPath = "/data/user/0/dev.scanrelay.app/cache/fatline_audio/one/$id.mp3"
    )

    @Test
    fun queuedCallsSurviveSnapshotRoundTripInOrder() {
        val saved = SavedPlaybackQueue(
            calls = listOf(
                SavedPlaybackCall("call:one:live:1:4:120:a", call(1), true),
                SavedPlaybackCall("call:one:live:2:4:120:b", call(2), true),
                SavedPlaybackCall("call:two:replay:3:4:120:c", call(3, "two"), false)
            ),
            positionMs = 1250,
            playWhenReady = true
        )
        val recovered = PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(saved))
        assertEquals(saved, recovered)
        assertEquals(listOf(1L, 2L, 3L), recovered?.calls?.map { it.call.id })
    }

    @Test
    fun savedPausedPlaybackDoesNotAutoResume() {
        val saved = SavedPlaybackQueue(
            calls = listOf(SavedPlaybackCall("call:one:replay:1:4:120:a", call(1), false)),
            playWhenReady = false
        )
        assertFalse(PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(saved))!!.playWhenReady)
    }

    @Test
    fun unknownOrMalformedSnapshotsAreIgnored() {
        assertNull(PlaybackQueueCodec.decode(null))
        assertNull(PlaybackQueueCodec.decode("garbage"))
        assertNull(PlaybackQueueCodec.decode("""{"version":2,"calls":[]}"""))
    }

    @Test
    fun invalidRowsCannotImpersonateAProfile() {
        val raw = """{"version":1,"calls":[
          {"mediaId":"call:two:live:1:4:120:a","profileId":"one","audioPath":"/x"},
          {"mediaId":"call:one:live:2:4:120:b","profileId":"one","audioPath":"/valid",
           "id":2,"systemRef":4,"talkgroupRef":120,"liveFeed":true}
        ]}"""
        val recovered = PlaybackQueueCodec.decode(raw)
        assertEquals(1, recovered?.calls?.size)
        assertEquals(2L, recovered?.calls?.single()?.call?.id)
        assertTrue(recovered!!.calls.single().liveFeed)
    }
}
