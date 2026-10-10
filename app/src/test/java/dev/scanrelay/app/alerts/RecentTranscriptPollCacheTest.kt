package dev.scanrelay.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTranscriptPollCacheTest {
    @Test
    fun unchangedTranscriptIsMatchedOnceAcrossPollingCycles() {
        val cache = RecentTranscriptPollCache()
        assertTrue(cache.changed(101, "No reported fire", "Police", "Dispatch"))
        for (i in 0 until 25) {
            assertFalse(cache.changed(101, "No reported fire", "Police", "Dispatch"))
        }
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun reviewedTextAndCallLabelsAreAlwaysRecheckedWhenChanged() {
        val cache = RecentTranscriptPollCache()
        assertTrue(cache.changed(55, "Unit en route", "EMS", "Dispatch"))
        assertFalse(cache.changed(55, "Unit en route", "EMS", "Dispatch"))
        assertTrue(cache.changed(55, "Possible cardiac arrest", "EMS", "Dispatch"))
        assertFalse(cache.changed(55, "Possible cardiac arrest", "EMS", "Dispatch"))
        assertTrue(cache.changed(55, "Possible cardiac arrest", "EMS", "Tactical"))
        assertTrue(cache.changed(55, "Possible cardiac arrest", "Medical", "Tactical"))
        assertFalse(cache.changed(55, "Possible cardiac arrest", "Medical", "Tactical"))
    }

    @Test
    fun eachMonitorRestartRechecksItsFirstPage() {
        val previousJob = RecentTranscriptPollCache()
        assertTrue(previousJob.changed(42, "shots fired", "Law", "Police"))
        assertFalse(previousJob.changed(42, "shots fired", "Law", "Police"))
        val newJob = RecentTranscriptPollCache()
        assertTrue(newJob.changed(42, "shots fired", "Law", "Police"))
    }

    @Test
    fun boundedCacheEvictsOldestAndRechecksItWhenItReturns() {
        val cache = RecentTranscriptPollCache(maxEntries = 3, maxTotalCharacters = 12, maxTextCharacters = 10)
        assertTrue(cache.changed(1, "aaaa", null, null))
        assertTrue(cache.changed(2, "bbbb", null, null))
        assertTrue(cache.changed(3, "cccc", null, null))
        assertFalse(cache.changed(3, "cccc", null, null))
        assertTrue(cache.changed(4, "dddd", null, null))
        assertEquals(3, cache.entryCount)
        assertTrue(cache.cachedCharacters <= 12)
        assertTrue(cache.changed(1, "aaaa", null, null))
        assertTrue(cache.entryCount <= 3)
        assertTrue(cache.cachedCharacters <= 12)
    }

    @Test
    fun largeTextAndUnknownIdCannotFillCacheOrSuppressMatching() {
        val cache = RecentTranscriptPollCache(maxEntries = 2, maxTotalCharacters = 6, maxTextCharacters = 5)
        for (i in 0 until 10) {
            assertTrue(cache.changed(1, "long transcript not cached", null, null))
            assertTrue(cache.changed(-1, "unit", null, null))
        }
        assertEquals(0, cache.entryCount)
        assertTrue(cache.changed(2, "fire", null, null))
        assertFalse(cache.changed(2, "fire", null, null))
        assertTrue(cache.changed(3, "fire", null, null))
        assertEquals(4, cache.cachedCharacters)
        assertTrue(cache.changed(2, "fire", null, null))
        assertEquals(4, cache.cachedCharacters)
    }

    @Test
    fun blankAndFailedTranscriptsMayBeRetried() {
        val cache = RecentTranscriptPollCache()
        assertTrue(cache.changed(100, "fire", null, null))
        cache.forget(100) // caller forgets when transcript processing throws
        assertTrue(cache.changed(100, "fire", null, null))
        assertTrue(cache.changed(100, "", null, null))
        assertEquals(0, cache.entryCount)
        assertTrue(cache.changed(100, "fire", null, null))
    }

    @Test
    fun cachingNeverChangesPreparedPhraseRules() {
        val cache = RecentTranscriptPollCache()
        val prepared = LocalTranscriptAlertPolicy.prepare(listOf("fire"))
        val texts = listOf("firefighter call", "fire", "fire", "structure fire", "structure fire")
        val observed = mutableListOf<String>()
        texts.forEach { text ->
            if (cache.changed(14, text, "Fire", "Dispatch")) {
                observed += LocalTranscriptAlertPolicy.matchesPrepared(text, prepared)
            }
        }
        assertEquals(listOf("fire", "fire"), observed)
    }
}
