package dev.scanrelay.app.net

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.SystemConfig
import org.json.JSONArray
import org.json.JSONObject

private fun favoriteLong(item: JSONObject, name: String): Long? =
    item.opt(name)?.toString()?.toLongOrNull()?.takeIf { it > 0 }

private fun normalizedFavoriteTag(tag: String): String = tag.trim().ifBlank { "Untagged" }

internal fun parseFavoriteChannels(
    userSettings: JSONObject?,
    systems: List<SystemConfig>
): Set<ChannelKey>? {
    if (userSettings == null || !userSettings.has("favorites")) return null
    val array = userSettings.optJSONArray("favorites") ?: return emptySet()
    val bySystemRef = systems.associateBy { it.systemRef }
    val favorites = linkedSetOf<ChannelKey>()

    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val systemRef = favoriteLong(item, "systemId") ?: continue
        when (item.optString("type")) {
            "system" -> {
                bySystemRef[systemRef]?.talkgroups?.forEach { favorites += it.key }
            }
            "tag" -> {
                val tag = item.optString("tag").trim().takeIf { it.isNotEmpty() } ?: continue
                bySystemRef[systemRef]?.talkgroups
                    ?.filter { normalizedFavoriteTag(it.tag) == tag }
                    ?.forEach { favorites += it.key }
            }
            "talkgroup" -> {
                val talkgroupRef = favoriteLong(item, "talkgroupId") ?: continue
                favorites += ChannelKey(systemRef, talkgroupRef)
            }
        }
    }
    return favorites
}

internal fun serializeFavorites(
    favorites: Set<ChannelKey>,
    systems: List<SystemConfig>
): JSONArray = JSONArray().apply {
    systems.forEach { system ->
        val talkgroups = system.talkgroups
        val allSystemFavorite = talkgroups.isNotEmpty() && talkgroups.all { it.key in favorites }
        if (allSystemFavorite) {
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
                compareBy<Map.Entry<String, List<dev.scanrelay.app.model.TalkgroupConfig>>> {
                    it.key == "Untagged"
                }.thenBy { it.key.lowercase() }
            )
        tagGroups.forEach { (tag, members) ->
            if (members.isNotEmpty() && members.all { it.key in favorites }) {
                put(
                    JSONObject()
                        .put("type", "tag")
                        .put("systemId", system.systemRef)
                        .put("tag", tag)
                )
            }
        }

        talkgroups.forEach { talkgroup ->
            if (talkgroup.key in favorites) {
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
    favorites: Set<ChannelKey>,
    systems: List<SystemConfig>
): JSONObject =
    JSONObject(current.toString()).put("favorites", serializeFavorites(favorites, systems))
