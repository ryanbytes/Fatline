package dev.scanrelay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoffPolicyTest {
    @Test
    fun classifiesOfflineRestoreSwitchAndDuplicateCallbacks() {
        assertEquals(
            NetworkHandoffTransition.NO_CHANGE,
            NetworkHandoffPolicy.transition(null, null)
        )
        assertEquals(
            NetworkHandoffTransition.NETWORK_RESTORED,
            NetworkHandoffPolicy.transition(null, 10L)
        )
        assertEquals(
            NetworkHandoffTransition.NETWORK_SWITCHED,
            NetworkHandoffPolicy.transition(10L, 20L)
        )
        assertEquals(
            NetworkHandoffTransition.NETWORK_LOST,
            NetworkHandoffPolicy.transition(20L, null)
        )
        assertEquals(
            NetworkHandoffTransition.NO_CHANGE,
            NetworkHandoffPolicy.transition(20L, 20L)
        )
    }

    @Test
    fun ignoresLossCallbackFromAReplacedNetwork() {
        assertFalse(NetworkHandoffPolicy.isCurrentLoss(lostHandle = 10L, currentHandle = 20L))
        assertFalse(NetworkHandoffPolicy.isCurrentLoss(lostHandle = 10L, currentHandle = null))
        assertTrue(NetworkHandoffPolicy.isCurrentLoss(lostHandle = 20L, currentHandle = 20L))
    }
}
