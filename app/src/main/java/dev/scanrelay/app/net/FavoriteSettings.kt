package dev.scanrelay.app.net

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.FavoriteTagKey
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.json.JSONArray
import org.json.JSONObject

internal data class FavoriteSettingsSelection(
    val channels: Set<ChannelKey> = emptySet(),
    val systemRefs: Set<Long> = emptySet(),
    val tags: Set<FavoriteTagKey> = emptySet()
)

private fun favoriteLong(item: JSONObject, name: String): Long? =
    item.opt(name)?.toString()?.toLongOrNull()?.takeIf { it > 0 }

internal fun normalizedFavoriteTag(tag: String): String = tag.trim().ifBlank { "Untagged" }

internal fun parseFavoriteSelection(
    userSettings: JSONObject?,
    systems: List<SystemConfig>
): FavoriteSettingsSelection? {
    if (userSettings == null || !userSettings.has("favorites")) return null
    val array = userSettings.optJSONArray("favorites") ?: return FavoriteSettingsSelection()
    val bySystemRef = systems.associateBy { it.systemRef }
    val channels = linkedSetOf<ChannelKey>()
    val systemRefs = linkedSetOf<Long>()
    val tags = linkedSetOf<FavoriteTagKey>()

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val systemRef = favoriteLong(item, "systemId") ?: continue
        when (item.optString("type")) {
            "system" -> {
                systemRefs += systemRef
                bySystemRef[systemRef]?.talkgroups?.forEach { channels += it.key }
            }
            "tag" -> {
                val tag = item.optString("tag").trim().takeIf { it.isNotEmpty() } ?: continue
                tags += FavoriteTagKey(systemRef, tag)
                bySystemRef[systemRef]?.talkgroups
                    ?.filter { normalizedFavoriteTag(it.tag) == tag }
                    ?.forEach { channels += it.key }
            }
            "talkgroup" -> {
                val talkgroupRef = favoriteLong(item, "talkgroupId") ?: continue
                channels += ChannelKey(systemRef, talkgroupRef)
            }
        }
    }
    return FavoriteSettingsSelection(channels, systemRefs, tags)
}

internal fun serializeFavorites(
    selection: FavoriteSettingsSelection,
    systems: List<SystemConfig>
): JSONArray = JSONArray().apply {
    systems.forEach { system ->
        val talkgroups = system.talkgroups
        val allSystemChannelsFavorite =
            talkgroups.isNotEmpty() && talkgroups.all { it.key in selection.channels }
        if (system.systemRef in selection.systemRefs && allSystemChannelsFavorite) {
            put(
                JSONObject()
                    .put("type", "system")
                    .put("systemId", system.systemRef)
            )
        }

        val tagGroups = talkgroups
            .groupBy { normalizedFavoriteTag(it.tag) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<TalkgroupConfig>>> { it.key == "Untagged" }
                    .thenBy { it.key.lowercase() }
            )
        tagGroups.forEach { (tag, members) ->
            val key = FavoriteTagKey(system.systemRef, tag)
            if (key in selection.tags && members.isNotEmpty() && members.all { it.key in selection.channels }) {
                put(
                    JSONObject()
                        .put("type", "tag")
                        .put("systemId", system.systemRef)
                        .put("tag", tag)
                )
            }
        }

        talkgroups.forEach { talkgroup ->
            if (talkgroup.key in selection.channels) {
                put(
                    JSONObject()
                        .put("type", "talkgroup")
                        .put("systemId", system.systemRef)
                        .put("talkgroupId", talkgroup.talkgroupRef)
                )
            }
        }
    }
}

internal fun mergeFavoritesIntoSettings(
    current: JSONObject,
    selection: FavoriteSettingsSelection,
    systems: List<SystemConfig>
): JSONObject =
    JSONObject(current.toString()).put("favorites", serializeFavorites(selection, systems))
