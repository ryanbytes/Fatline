package dev.scanrelay.app.data

import android.content.Context
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.SystemConfig

internal fun filterHiddenChannelSelection(
    selected: Set<ChannelKey>,
    hiddenSystems: Set<Long>
): Set<ChannelKey> =
    selected.filterNotTo(mutableSetOf()) { it.systemRef in hiddenSystems }

internal fun reconcileChannelSelection(
    currentScope: Set<ChannelKey>,
    savedSelection: Set<ChannelKey>,
    knownScope: Set<ChannelKey>,
    autoEnableNewTalkgroups: Boolean
): Set<ChannelKey> {
    val retained = savedSelection.intersect(currentScope)
    if (!autoEnableNewTalkgroups) return retained
    return retained + (currentScope - knownScope)
}

/** User-selected monitoring filters are independent of base channel enablement. */
internal data class MonitoringOverrides(
    val hold: ChannelKey? = null,
    val holdSystemRef: Long? = null,
    val avoided: Set<ChannelKey> = emptySet()
)

/** On server scope changes, discard only holds/avoids whose refs disappeared. */
internal fun reconcileMonitoringOverrides(
    saved: MonitoringOverrides,
    systems: List<SystemConfig>
): MonitoringOverrides {
    val systemRefs = systems.mapTo(mutableSetOf()) { it.systemRef }
    val channelRefs = systems.flatMapTo(mutableSetOf()) { system ->
        system.talkgroups.map { it.key }
    }
    return saved.copy(
        hold = saved.hold?.takeIf { it in channelRefs },
        holdSystemRef = saved.holdSystemRef?.takeIf { it in systemRefs },
        avoided = saved.avoided.intersect(channelRefs)
    )
}

class ChannelStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_channels", Context.MODE_PRIVATE)

    private fun initializedKey(profileId: String) = "selection_initialized_$profileId"
    private fun selectedKey(profileId: String) = "selected_$profileId"
    private fun knownKey(profileId: String) = "known_$profileId"
    private fun favoritesKey(profileId: String) = "favorites_$profileId"
    private fun hiddenSystemsKey(profileId: String) = "hidden_systems_$profileId"
    private fun holdChannelKey(profileId: String) = "hold_channel_$profileId"
    private fun holdSystemKey(profileId: String) = "hold_system_$profileId"
    private fun avoidedChannelsKey(profileId: String) = "avoided_channels_$profileId"

    internal fun monitoringOverrides(profileId: String): MonitoringOverrides =
        MonitoringOverrides(
            hold = prefs.getString(holdChannelKey(profileId), null)?.let(ChannelKey::parse),
            holdSystemRef = prefs.getString(holdSystemKey(profileId), null)?.toLongOrNull(),
            avoided = readKeys(avoidedChannelsKey(profileId))
        )

    internal fun setMonitoringOverrides(profileId: String, overrides: MonitoringOverrides) {
        prefs.edit()
            .putString(holdChannelKey(profileId), overrides.hold?.toString())
            .putString(holdSystemKey(profileId), overrides.holdSystemRef?.toString())
            .putStringSet(
                avoidedChannelsKey(profileId),
                overrides.avoided.mapTo(mutableSetOf()) { it.toString() }
            )
            .apply()
    }

    fun apply(
        profileId: String,
        systems: List<SystemConfig>,
        autoEnableNewTalkgroups: Boolean = false
    ): List<SystemConfig> {
        val all = systems.flatMap { system -> system.talkgroups.map { it.key } }.toSet()
        val initialized = prefs.getBoolean(initializedKey(profileId), false)
        val selected = if (initialized) {
            val saved = readKeys(selectedKey(profileId))
            val hasKnownBaseline = prefs.contains(knownKey(profileId))
            // Existing FatLine installs predate the known-scope key. Treat the
            // current config as their baseline so an upgrade never bulk-enables
            // channels merely because they look new to this app version.
            val known = if (hasKnownBaseline) readKeys(knownKey(profileId)) else all
            reconcileChannelSelection(all, saved, known, autoEnableNewTalkgroups).also {
                writeKeys(selectedKey(profileId), it)
                writeKeys(knownKey(profileId), all)
            }
        } else {
            // FatLine's established first-connect behavior is all authorized
            // channels enabled. The server option only controls later scope additions.
            writeKeys(selectedKey(profileId), all)
            writeKeys(knownKey(profileId), all)
            prefs.edit().putBoolean(initializedKey(profileId), true).apply()
            all
        }
        val hiddenSystems = hiddenSystems(profileId)
        val visibleSelection = filterHiddenChannelSelection(selected, hiddenSystems)
        if (visibleSelection != selected) writeKeys(selectedKey(profileId), visibleSelection)
        val favorites = readKeys(favoritesKey(profileId))
        return systems.map { system ->
            val hidden = system.systemRef in hiddenSystems
            system.copy(talkgroups = system.talkgroups.map { talkgroup ->
                talkgroup.copy(
                    enabled = !hidden && talkgroup.key in visibleSelection,
                    favorite = talkgroup.key in favorites
                )
            })
        }
    }

    fun setEnabled(profileId: String, key: ChannelKey, enabled: Boolean) =
        setMany(profileId, setOf(key), enabled)

    fun setMany(profileId: String, keys: Collection<ChannelKey>, enabled: Boolean) {
        if (keys.isEmpty()) return
        val selected = readKeys(selectedKey(profileId)).toMutableSet()
        if (enabled) selected.addAll(keys) else selected.removeAll(keys.toSet())
        writeKeys(selectedKey(profileId), selected)
        prefs.edit().putBoolean(initializedKey(profileId), true).apply()
    }

    fun setAll(profileId: String, keys: Set<ChannelKey>, enabled: Boolean) {
        writeKeys(selectedKey(profileId), if (enabled) keys else emptySet())
        prefs.edit().putBoolean(initializedKey(profileId), true).apply()
    }

    fun setFavorite(profileId: String, key: ChannelKey, favorite: Boolean) =
        setFavorites(profileId, setOf(key), favorite)

    fun setFavorites(profileId: String, keys: Collection<ChannelKey>, favorite: Boolean) {
        if (keys.isEmpty()) return
        val favorites = readKeys(favoritesKey(profileId)).toMutableSet()
        if (favorite) favorites.addAll(keys) else favorites.removeAll(keys.toSet())
        writeKeys(favoritesKey(profileId), favorites)
    }

    fun replaceFavorites(profileId: String, favorites: Set<ChannelKey>) {
        writeKeys(favoritesKey(profileId), favorites)
    }

    fun favorites(profileId: String): Set<ChannelKey> = readKeys(favoritesKey(profileId))

    fun hiddenSystems(profileId: String): Set<Long> =
        prefs.getStringSet(hiddenSystemsKey(profileId), emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()

    fun setSystemHidden(profileId: String, systemRef: Long, hidden: Boolean) {
        val current = hiddenSystems(profileId).toMutableSet()
        if (hidden) current += systemRef else current -= systemRef
        prefs.edit()
            .putStringSet(hiddenSystemsKey(profileId), current.mapTo(mutableSetOf(), Long::toString))
            .apply()
    }

    fun deleteProfile(profileId: String) {
        prefs.edit()
            .remove(initializedKey(profileId))
            .remove(selectedKey(profileId))
            .remove(knownKey(profileId))
            .remove(favoritesKey(profileId))
            .remove(hiddenSystemsKey(profileId))
            .remove(holdChannelKey(profileId))
            .remove(holdSystemKey(profileId))
            .remove(avoidedChannelsKey(profileId))
            .apply()
    }

    private fun readKeys(name: String): Set<ChannelKey> =
        prefs.getStringSet(name, emptySet()).orEmpty().mapNotNull(ChannelKey::parse).toSet()

    private fun writeKeys(name: String, keys: Set<ChannelKey>) {
        prefs.edit().putStringSet(name, keys.mapTo(mutableSetOf()) { it.toString() }).apply()
    }
}
