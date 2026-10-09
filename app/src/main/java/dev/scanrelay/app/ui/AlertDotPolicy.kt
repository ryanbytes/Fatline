package dev.scanrelay.app.ui

/** Alert badge state is local to a foreground app session; no vendor push service. */
internal object AlertDotPolicy {
    fun markViewedOrBaseline(
        previouslySeen: Map<String, Set<String>>,
        current: Map<String, Set<String>>,
        viewingAlerts: Boolean
    ): Map<String, Set<String>> {
        val next = previouslySeen.toMutableMap()
        current.forEach { (profileId, alertKeys) ->
            if (viewingAlerts || profileId !in next) next[profileId] = alertKeys
        }
        return next
    }

    fun hasNewAlerts(seen: Map<String, Set<String>>, current: Map<String, Set<String>>): Boolean =
        current.any { (profileId, keys) ->
            val baseline = seen[profileId] ?: keys
            keys.any { it !in baseline }
        }
}
