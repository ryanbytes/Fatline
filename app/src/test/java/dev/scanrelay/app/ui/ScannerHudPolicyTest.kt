package dev.scanrelay.app.ui

import dev.scanrelay.app.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerHudPolicyTest {
    @Test
    fun connectedIdleShowsScanningButNotReceiverActivity() {
        val flags = ScannerHudPolicy.flags(ConnectionStatus.CONNECTED, false, false, false, false)
        assertTrue(flags.live)
        assertTrue(flags.scan)
        assertFalse(flags.rx)
        assertEquals("SCANNING", flags.headline)
    }

    @Test
    fun actualAudioShowsRxAndHoldIndicators() {
        val flags = ScannerHudPolicy.flags(ConnectionStatus.CONNECTED, false, true, true, true)
        assertTrue(flags.rx)
        assertFalse(flags.scan)
        assertTrue(flags.holdSystem)
        assertTrue(flags.holdTalkgroup)
        assertEquals("PLAYBACK", flags.headline)
    }

    @Test
    fun pausedOrOfflineNeverClaimsScanning() {
        val paused = ScannerHudPolicy.flags(ConnectionStatus.CONNECTED, true, false, false, false)
        assertTrue(paused.pause)
        assertFalse(paused.live)
        assertFalse(paused.scan)
        assertEquals("PAUSED", paused.headline)

        val offline = ScannerHudPolicy.flags(ConnectionStatus.CONNECTING, false, false, false, false)
        assertFalse(offline.live)
        assertFalse(offline.scan)
        assertFalse(offline.rx)
        assertEquals("DISCONNECTED", offline.headline)
    }

    @Test
    fun disconnectedBufferedPlaybackIsStillRecognizedAsAudio() {
        val flags = ScannerHudPolicy.flags(ConnectionStatus.CONNECTING, false, true, false, false)
        assertFalse(flags.live)
        assertFalse(flags.scan)
        assertTrue(flags.rx)
        assertEquals("PLAYBACK", flags.headline)
    }
}
