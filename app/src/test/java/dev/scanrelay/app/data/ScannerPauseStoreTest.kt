package dev.scanrelay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerPauseStoreTest {
    @Test
    fun pauseKeysAreUniqueForEachScannerProfile() {
        assertEquals("paused_scanner-a", ScannerPausePolicy.key("scanner-a"))
        assertEquals("paused_scanner-b", ScannerPausePolicy.key("scanner-b"))
        assertFalse(ScannerPausePolicy.key("scanner-a") == ScannerPausePolicy.key("scanner-b"))
    }

    @Test
    fun pausedScannerRejectsLiveCallsButAllowsManualReplays() {
        assertTrue(ScannerPausePolicy.suppressIncomingAudio(liveFeed = true, paused = true))
        assertFalse(ScannerPausePolicy.suppressIncomingAudio(liveFeed = false, paused = true))
        assertFalse(ScannerPausePolicy.suppressIncomingAudio(liveFeed = true, paused = false))
    }
}
