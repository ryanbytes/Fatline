package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRoutePolicyTest {
    @Test fun sanitizerRetainsOnlyBroadExpectedOutputTypes() {
        assertEquals("bluetooth+speaker", AudioOutputRoutePolicy.combine(
            listOf("speaker", "Bluetooth device name", "bluetooth", "speaker")))
        assertEquals("unknown", AudioOutputRoutePolicy.combine(listOf("00:12:34:ab:cd:ef")))
        val snapshot = AudioOutputRoutePolicy.snapshot(
            "speaker+secret_device", true, 120, true, false)
        assertTrue(snapshot.contains("route=speaker"))
        assertTrue(snapshot.contains("stream_muted=true"))
        assertTrue(snapshot.contains("gain=100"))
        assertFalse(snapshot.contains("secret_device"))
    }

    @Test fun sinkErrorFamilyNeverExposesExceptionMessage() {
        assertEquals("write", AudioOutputRoutePolicy.errorFamily("WriteException"))
        assertEquals("other", AudioOutputRoutePolicy.errorFamily("Untrusted network credentials"))
    }
}
