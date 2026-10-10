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


/**
 * Compose receives a new ScannerState after queue/status/transcript updates.
 * Alert lists are immutable and normally retain their *same instance*. Avoid
 * rehashing hundreds of stable alert keys on every unrelated state update.
 *
 * The cache holds only the latest alert list per connected server; disconnect
 * prunes that entry. The separate "seen" badge baseline is not modified.
 */
internal class AlertKeySnapshotCache {
    private data class Entry(
        val alerts: List<dev.scanrelay.app.model.ScannerAlert>,
        val keys: Set<String>
    )

    private val cached = mutableMapOf<String, Entry>()
    private var previousKeys: Map<String, Set<String>> = emptyMap()

    fun snapshot(
        servers: Map<String, dev.scanrelay.app.model.ServerScannerState>
    ): Map<String, Set<String>> {
        // A fast no-work path for the common scanner update, including status,
        // playback queue changes and transcript refreshes.
        if (servers.size == cached.size &&
            servers.all { (profileId, server) -> cached[profileId]?.alerts === server.alerts }
        ) return previousKeys

        val next = LinkedHashMap<String, Set<String>>(servers.size)
        val entries = HashMap<String, Entry>(servers.size)
        servers.forEach { (profileId, server) ->
            val old = cached[profileId]
            val entry = if (old?.alerts === server.alerts) old else Entry(
                server.alerts, server.alerts.mapTo(LinkedHashSet()) { it.stableKey }
            )
            next[profileId] = entry.keys
            entries[profileId] = entry
        }
        cached.clear()
        cached.putAll(entries)
        previousKeys = next
        return next
    }
}
