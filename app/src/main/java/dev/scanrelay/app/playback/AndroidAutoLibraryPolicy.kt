package dev.scanrelay.app.playback

import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.SystemConfig

internal data class AndroidAutoFavorite(
    val mediaId: String,
    val title: String,
    val subtitle: String
)

internal object AndroidAutoLibraryPolicy {
    fun favoriteChannels(profileId: String, state: ScannerState): List<AndroidAutoFavorite> {
        val server = state.servers[profileId] ?: return emptyList()
        return server.systems
            .filterNot { it.systemRef in server.hiddenSystemRefs }
            .flatMap { system ->
                system.talkgroups
                    .filter { it.favorite }
                    .map { talkgroup ->
                        AndroidAutoFavorite(
                            mediaId = "channel:$profileId:${talkgroup.systemRef}:${talkgroup.talkgroupRef}",
                            title = talkgroup.displayName,
                            subtitle = system.label
                        )
                    }
            }
    }

    fun childrenChanged(
        previous: List<AndroidAutoFavorite>?,
        current: List<AndroidAutoFavorite>
    ): Boolean = previous != null && previous != current
}

/**
 * Android Auto's favorite-channel library depends only on system configuration
 * and hidden-system membership. Live calls, queue changes and transcript alerts
 * leave these immutable input references unchanged, so avoid rebuilding the
 * entire favorites list for every scanner state emission.
 *
 * Owned by ScannerService's main-thread state collector; no synchronization
 * with the repository thread or global cache lifetime is required.
 */
internal data class AndroidAutoFavoritesUpdate(
    val favorites: List<AndroidAutoFavorite>,
    val changed: Boolean,
    val rebuilt: Boolean
)

internal class AndroidAutoFavoritesCache {
    private data class Snapshot(
        val systems: List<SystemConfig>,
        val hiddenSystems: Set<Long>,
        val favorites: List<AndroidAutoFavorite>
    )

    private val snapshots = mutableMapOf<String, Snapshot>()

    val profileIds: Set<String> get() = snapshots.keys.toSet()

    /**
     * ScannerService receives every repository StateFlow emission. Most change
     * audio, History or alerts, not channel configuration. Only touch the
     * favorites cache when a profile's immutable configuration inputs change.
     *
     * Iterate the live profiles and stale cache entries directly; don't create
     * a union Set of profile IDs or allocate "unchanged" result objects on
     * every received scanner call.
     */
    fun forEachChanged(state: ScannerState, onChanged: (String, Int) -> Unit) {
        for ((profileId, server) in state.servers) {
            val previous = snapshots[profileId]
            if (previous != null &&
                previous.systems === server.systems &&
                previous.hiddenSystems === server.hiddenSystemRefs
            ) continue
            val update = refresh(profileId, state)
            if (update.changed) onChanged(profileId, update.favorites.size)
        }

        // Removing/replacing a scanner can leave an entry even when the number
        // of active profiles stays constant. Prune those entries as well.
        val stale = snapshots.entries.iterator()
        while (stale.hasNext()) {
            val entry = stale.next()
            if (entry.key !in state.servers) {
                if (entry.value.favorites.isNotEmpty()) onChanged(entry.key, 0)
                stale.remove()
            }
        }
    }

    fun refresh(profileId: String, state: ScannerState): AndroidAutoFavoritesUpdate {
        val server = state.servers[profileId]
        val previous = snapshots[profileId]
        if (server == null) {
            snapshots.remove(profileId)
            return AndroidAutoFavoritesUpdate(
                favorites = emptyList(),
                changed = AndroidAutoLibraryPolicy.childrenChanged(previous?.favorites, emptyList()),
                rebuilt = previous != null
            )
        }

        if (previous != null &&
            previous.systems === server.systems &&
            previous.hiddenSystems === server.hiddenSystemRefs
        ) {
            return AndroidAutoFavoritesUpdate(previous.favorites, changed = false, rebuilt = false)
        }

        val current = AndroidAutoLibraryPolicy.favoriteChannels(profileId, state)
        snapshots[profileId] = Snapshot(server.systems, server.hiddenSystemRefs, current)
        return AndroidAutoFavoritesUpdate(
            favorites = current,
            changed = AndroidAutoLibraryPolicy.childrenChanged(previous?.favorites, current),
            rebuilt = true
        )
    }
}
