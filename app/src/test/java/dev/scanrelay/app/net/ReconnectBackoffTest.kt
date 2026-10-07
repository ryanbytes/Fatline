package dev.scanrelay.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun growsExponentiallyAndCapsRetryDelay() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(1, 0))
        assertEquals(2_000L, ReconnectBackoff.delayMillis(2, 0))
        assertEquals(4_000L, ReconnectBackoff.delayMillis(3, 0))
        assertEquals(30_000L, ReconnectBackoff.delayMillis(6, 0))
        assertEquals(30_000L, ReconnectBackoff.delayMillis(100, 0))
    }

    @Test fun clampsJitterToAvoidChangingTheBackoffWindow() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(1, -10))
        assertEquals(1_349L, ReconnectBackoff.delayMillis(1, 10_000))
    }
}
