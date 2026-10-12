package dev.scanrelay.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallPolicyTest {
    @Test fun recoveringRequiresSustainedBufferingAndExplicitPlaybackIntent() {
        val gate = PlaybackStallPolicy(stallAfterMs = 15_000L)
        fun check(time: Long, id: String? = "one", ready: Boolean = true,
                  playing: Boolean = false, buffering: Boolean = true,
                  suppressed: Boolean = false) = gate.shouldRecover(
            time, id, ready, playing, buffering, suppressed
        )
        assertFalse(check(0, ready = false))
        assertFalse(check(1000))
        assertFalse(check(15_999))
        assertTrue(check(16_000))
        assertFalse(check(31_000)) // Cooldown prevents repeat recovery for same item
        assertFalse(check(32_000, playing = true))
        assertFalse(check(40_000, buffering = false))
        assertFalse(check(41_000, suppressed = true))
        assertFalse(check(42_000, id = null))
    }

    @Test fun sameCallHasCooldownButNewCallMayRecover() {
        val gate = PlaybackStallPolicy(stallAfterMs = 10_000L, retryAfterMs = 120_000L)
        fun check(time: Long, id: String) =
            gate.shouldRecover(time, id, true, false, true, false)
        assertFalse(check(0, "a"))
        assertTrue(check(10_000, "a"))
        assertFalse(check(30_000, "a"))
        assertFalse(check(31_000, "b"))
        assertTrue(check(41_000, "b"))
        assertFalse(check(100_000, "a"))
        assertTrue(check(130_000, "a")) // Retry now allowed after observing a again
        assertFalse(check(140_000, "a")) // Second retry remains rate-limited
    }

    @Test fun recoveringStopsWhenPlaybackResumes() {
        val gate = PlaybackStallPolicy(stallAfterMs = 5_000L)
        assertFalse(gate.shouldRecover(1_000, "call", true, false, true, false))
        assertFalse(gate.shouldRecover(4_000, "call", true, true, false, false))
        assertFalse(gate.shouldRecover(7_000, "call", true, false, true, false))
        assertTrue(gate.shouldRecover(12_000, "call", true, false, true, false))
    }
}
