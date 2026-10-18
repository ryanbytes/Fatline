package dev.scanrelay.app.net

/**
 * A silent WebSocket is not necessarily broken: no subscribed talkgroup may be
 * active. Reassert the subscription only after a long quiet period, and at most
 * once every ten minutes. Never disconnect a healthy, idle radio feed merely
 * because there were no calls.
 */
internal object QuietLivefeedRecoveryPolicy {
    const val MIN_QUIET_MS = 4L * 60_000L
    const val MIN_REASSERT_GAP_MS = 10L * 60_000L

    fun shouldReassert(
        nowElapsedMs: Long,
        lastLiveCallElapsedMs: Long,
        lastSubscriptionElapsedMs: Long,
        lastReassertElapsedMs: Long,
        connected: Boolean,
        paused: Boolean,
        hasEnabledChannels: Boolean
    ): Boolean {
        if (!connected || paused || !hasEnabledChannels) return false
        if (lastSubscriptionElapsedMs <= 0L) return false
        // elapsedRealtime is monotonic except when reboot resets it.
        val recentActivity = maxOf(lastSubscriptionElapsedMs, lastLiveCallElapsedMs)
        if (nowElapsedMs < recentActivity ||
            nowElapsedMs - recentActivity < MIN_QUIET_MS
        ) return false
        if (lastReassertElapsedMs > 0L &&
            (nowElapsedMs < lastReassertElapsedMs ||
             nowElapsedMs - lastReassertElapsedMs < MIN_REASSERT_GAP_MS)
        ) return false
        return true
    }
}
