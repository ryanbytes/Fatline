package dev.scanrelay.app.playback

/**
 * Reconcile the listener's last explicitly active scanner IDs with current profiles
 * and existing sessions, so Activity relaunch does not reconnect already-live sockets.
 */
internal object SessionRestorePolicy {
    fun valid(savedIds: Set<String>, configuredIds: Set<String>): Set<String> =
        savedIds.filterTo(linkedSetOf()) { it in configuredIds }

    fun missing(validIds: Set<String>, runningIds: Set<String>): Set<String> =
        validIds.filterTo(linkedSetOf()) { it !in runningIds }
}
