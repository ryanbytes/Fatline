package dev.scanrelay.app.playback

import dev.scanrelay.app.model.ScannerState

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
