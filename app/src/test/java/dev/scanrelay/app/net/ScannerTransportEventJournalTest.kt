package dev.scanrelay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTransportEventJournalTest {
    @Test
    fun retainsOnlyMostRecentEventsAndPreservesSequence() {
        val journal = ScannerTransportEventJournal(capacity = 2)
        assertEquals(1L, journal.record("socket lost", 1000L))
        assertEquals(2L, journal.record("retry", 2000L))
        assertEquals(3L, journal.record("ready", 3000L))
        assertEquals(3L, journal.sequence())
        val entries = journal.between(0L, 3L)
        assertEquals(listOf("retry", "ready"), entries.map { it.detail })
        assertEquals(listOf(2000L, 3000L), entries.map { it.timestampMs })
        assertEquals(listOf(2L, 3L), entries.map { it.sequence })
    }

    @Test
    fun returnsOnlyEventsInsideCaptureWindow() {
        val journal = ScannerTransportEventJournal(capacity = 4)
        val before = journal.record("before", 1000L)
        journal.record("during", 2000L)
        val end = journal.sequence()
        journal.record("after", 3000L)
        assertEquals(listOf("during"), journal.between(before, end).map { it.detail })
        assertTrue(journal.between(end, end).isEmpty())
    }
}
