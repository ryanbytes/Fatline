package dev.scanrelay.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfiniteScrollPolicyTest {
    @Test fun requestsNearBottom() {
        assertTrue(InfiniteScrollPolicy.shouldLoad(94, 100, 50, true, false, true))
        assertTrue(InfiniteScrollPolicy.shouldLoad(99, 100, 200, true, false, true))
    }
    @Test fun preventsOverlappingOrOfflinePages() {
        assertFalse(InfiniteScrollPolicy.shouldLoad(99, 100, 50, true, true, true))
        assertFalse(InfiniteScrollPolicy.shouldLoad(99, 100, 50, false, false, true))
        assertFalse(InfiniteScrollPolicy.shouldLoad(99, 100, 50, true, false, false))
    }
    @Test fun avoidsEagerLoading() {
        assertFalse(InfiniteScrollPolicy.shouldLoad(null, 100, 50, true, false, true))
        assertFalse(InfiniteScrollPolicy.shouldLoad(9, 100, 50, true, false, true))
        assertFalse(InfiniteScrollPolicy.shouldLoad(0, 12, 0, true, false, true))
    }
}
