package dev.scanrelay.app.data

import android.content.Context
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.SystemConfig

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

class ChannelStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_channels", Context.MODE_PRIVATE)

    private fun initializedKey(profileId: String) = "selection_initialized_$profileId"
    private fun selectedKey(profileId: String) = "selected_$profileId"
    private fun knownKey(profileId: String) = "known_$profileId"
    private fun favoritesKey(profileId: String) = "favorites_$profileId"

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
        val favorites = readKeys(favoritesKey(profileId))
        return systems.map { system ->
            system.copy(talkgroups = system.talkgroups.map { talkgroup ->
                talkgroup.copy(
                    enabled = talkgroup.key in selected,
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

    fun setFavorite(profileId: String, key: ChannelKey, favorite: Boolean) {
        val favorites = readKeys(favoritesKey(profileId)).toMutableSet()
        if (favorite) favorites += key else favorites -= key
        writeKeys(favoritesKey(profileId), favorites)
    }

    fun favorites(profileId: String): Set<ChannelKey> = readKeys(favoritesKey(profileId))

    fun deleteProfile(profileId: String) {
        prefs.edit()
            .remove(initializedKey(profileId))
            .remove(selectedKey(profileId))
            .remove(knownKey(profileId))
            .remove(favoritesKey(profileId))
            .apply()
    }

    private fun readKeys(name: String): Set<ChannelKey> =
        prefs.getStringSet(name, emptySet()).orEmpty().mapNotNull(ChannelKey::parse).toSet()

    private fun writeKeys(name: String, keys: Set<ChannelKey>) {
        prefs.edit().putStringSet(name, keys.mapTo(mutableSetOf()) { it.toString() }).apply()
    }
}
