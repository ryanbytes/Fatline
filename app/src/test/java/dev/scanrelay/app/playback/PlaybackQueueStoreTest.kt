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
    fun identicalQueueSnapshotsDoNotNeedRepeatedDiskWrites() {
        val original = SavedPlaybackQueue(
            calls = listOf(SavedPlaybackCall("call:one:live:1:4:120:a", call(1), true)),
            positionMs = 750L,
            playWhenReady = true
        )
        assertTrue(PlaybackQueueJournalPolicy.shouldWrite(null, original))
        assertFalse(PlaybackQueueJournalPolicy.shouldWrite(original, original.copy()))
        assertFalse(PlaybackQueueJournalPolicy.shouldWrite(null, null))
    }

    @Test
    fun queueEditsAndRecoveryPositionStillJournalImmediately() {
        val first = SavedPlaybackQueue(
            calls = listOf(SavedPlaybackCall("call:one:live:1:4:120:a", call(1), true)),
            positionMs = 750L,
            playWhenReady = true
        )
        val second = first.copy(calls = first.calls +
            SavedPlaybackCall("call:one:live:2:4:120:b", call(2), true))
        assertTrue(PlaybackQueueJournalPolicy.shouldWrite(first, second))
        assertTrue(PlaybackQueueJournalPolicy.shouldWrite(second, second.copy(positionMs = 751L)))
        assertTrue(PlaybackQueueJournalPolicy.shouldWrite(second, second.copy(playWhenReady = false)))
        assertTrue(PlaybackQueueJournalPolicy.shouldWrite(second, null))
        assertTrue(PlaybackQueueJournalPolicy.shouldWrite(null, second))
        assertFalse(PlaybackQueueJournalPolicy.shouldWrite(second, second.copy()))
    }

    @Test
    fun cachePruningProtectsAllQueuedFilesAcrossProfiles() {
        val newest = (1..200).map { "/cache/$it.mp3" }
        val pinned = setOf(newest[0], newest[190], newest[199])
        val evicted = PlaybackCachePolicy.evictablePaths(newest, pinned)
        assertEquals(47, evicted.size)
        assertTrue(evicted.none { it in pinned })
        assertTrue(evicted.contains("/cache/198.mp3"))
    }

    @Test
    fun cacheSortReadsEachFileMtimeOnceAndPreservesStableNewestFirstOrder() {
        val files = (0 until 415).map { java.io.File("/cache/scanner/${it}.mp3") }
        val timestamps = files.withIndex().associate { (index, file) ->
            file to ((index * 17L) % 43L)
        }
        var mtimeReads = 0
        val actual = PlaybackCachePolicy.newestFirst(files.toTypedArray()) { file ->
            mtimeReads++
            timestamps.getValue(file)
        }
        val previous = files.sortedByDescending { timestamps.getValue(it) }
        assertEquals(previous, actual)
        assertEquals(files.size, mtimeReads)
        assertEquals(emptyList<java.io.File>(), PlaybackCachePolicy.newestFirst(emptyArray()))
        assertEquals(files.take(1), PlaybackCachePolicy.newestFirst(files.take(1).toTypedArray()))
    }

    @Test
    fun cacheOnePassEvictionMatchesOriginalPolicyAcrossRetentionAndPinnedFiles() {
        val newest = (0 until 450).map { java.io.File("/cache/scanner/${it}.mp3") }
        val paths = newest.map { it.absolutePath }
        val protectedSets = listOf(
            emptySet(),
            setOf(paths[0], paths[1], paths[200], paths[445], paths[449]),
            paths.toSet()
        )
        for (protected in protectedSets) {
            for (keep in listOf(0, 1, 50, 150, 449, 500)) {
                val evicted = mutableListOf<String>()
                PlaybackCachePolicy.forEachEvictableFile(newest, protected, keep) {
                    evicted += it.absolutePath
                }
                val expected = PlaybackCachePolicy.evictablePaths(paths, protected, keep)
                assertEquals("retention=$keep, protected=${protected.size}", expected, evicted)
                assertTrue(evicted.none { it in protected })
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cacheEvictionRejectsNegativeRetention() {
        PlaybackCachePolicy.forEachEvictableFile(emptyList(), emptySet(), -1) { error("Unexpected delete") }
    }

    @Test
    fun cachePruningHappensImmediatelyThenEverySixteenWritesPerScanner() {
        val schedule = PlaybackCachePruneSchedule()
        assertTrue(schedule.afterWrite("one")) // startup safety sweep
        repeat(15) { assertFalse(schedule.afterWrite("one")) }
        assertTrue(schedule.afterWrite("one")) // 17th write
        repeat(15) { assertFalse(schedule.afterWrite("one")) }
        assertTrue(schedule.afterWrite("one")) // 33rd write
        assertTrue(schedule.afterWrite("two")) // independent profile baseline
    }

    @Test
    fun batchedPruningStillProtectsQueuedCallsWhenSweepOccurs() {
        val schedule = PlaybackCachePruneSchedule(interval = 3)
        val files = (1..200).map { "/cache/$it.mp3" }
        val queued = setOf(files[199], files[190], files[0])
        assertTrue(schedule.afterWrite("one"))
        assertFalse(schedule.afterWrite("one"))
        assertFalse(schedule.afterWrite("one"))
        assertTrue(schedule.afterWrite("one"))
        val evict = PlaybackCachePolicy.evictablePaths(files, queued)
        assertEquals(47, evict.size)
        assertTrue(evict.none { it in queued })
        assertTrue(evict.contains(files[197]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pruningScheduleRejectsZeroInterval() {
        PlaybackCachePruneSchedule(interval = 0)
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
