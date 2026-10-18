package dev.scanrelay.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietLivefeedRecoveryPolicyTest {
    @Test fun resubscribesAfterFourMinutesWithoutLiveCalls() {
        assertTrue(QuietLivefeedRecoveryPolicy.shouldReassert(
            nowElapsedMs = 300_000,
            lastLiveCallElapsedMs = 30_000,
            lastSubscriptionElapsedMs = 10_000,
            lastReassertElapsedMs = 0,
            connected = true, paused = false, hasEnabledChannels = true
        ))
    }

    @Test fun leavesRecentCallTrafficAlone() {
        assertFalse(QuietLivefeedRecoveryPolicy.shouldReassert(
            nowElapsedMs = 300_000,
            lastLiveCallElapsedMs = 240_000,
            lastSubscriptionElapsedMs = 10_000,
            lastReassertElapsedMs = 0,
            connected = true, paused = false, hasEnabledChannels = true
        ))
    }

    @Test fun rateLimitsRepeatedResubscriptionsDuringQuietPeriods() {
        assertFalse(QuietLivefeedRecoveryPolicy.shouldReassert(
            nowElapsedMs = 500_000,
            lastLiveCallElapsedMs = 10_000,
            lastSubscriptionElapsedMs = 20_000,
            lastReassertElapsedMs = 300_000,
            connected = true, paused = false, hasEnabledChannels = true
        ))
        assertTrue(QuietLivefeedRecoveryPolicy.shouldReassert(
            nowElapsedMs = 901_000,
            lastLiveCallElapsedMs = 10_000,
            lastSubscriptionElapsedMs = 20_000,
            lastReassertElapsedMs = 300_000,
            connected = true, paused = false, hasEnabledChannels = true
        ))
    }

    @Test fun neverProbesPausedDisconnectedOrUnsubscribedScanner() {
        for (connected in listOf(false, true)) for (paused in listOf(false, true))
            for (enabled in listOf(false, true)) {
                if (connected && !paused && enabled) continue
                assertFalse(QuietLivefeedRecoveryPolicy.shouldReassert(
                    nowElapsedMs = 300_000, lastLiveCallElapsedMs = 0,
                    lastSubscriptionElapsedMs = 10_000, lastReassertElapsedMs = 0,
                    connected = connected, paused = paused, hasEnabledChannels = enabled
                ))
            }
    }
}
