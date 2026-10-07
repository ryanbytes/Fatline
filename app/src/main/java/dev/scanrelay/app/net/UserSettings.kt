package dev.scanrelay.app.net

import org.json.JSONObject

internal fun parseLivefeedBacklogMinutes(userSettings: JSONObject?): Int =
    userSettings
        ?.optInt("livefeedBacklogMinutes", 0)
        ?.coerceAtLeast(0)
        ?: 0

internal fun mergeLivefeedBacklogIntoSettings(
    current: JSONObject,
    minutes: Int
): JSONObject =
    JSONObject(current.toString()).put("livefeedBacklogMinutes", minutes.coerceAtLeast(0))

internal fun parseTagColors(userSettings: JSONObject?): Map<String, String> {
    val colors = userSettings?.optJSONObject("tagColors") ?: return emptyMap()
    return buildMap {
        val keys = colors.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val key = rawKey.trim().lowercase()
            val value = colors.optString(rawKey).trim()
            if (key.isNotBlank() && value.isNotBlank()) put(key, value)
        }
    }
}

internal fun mergeTagColorsIntoSettings(
    current: JSONObject,
    colors: Map<String, String>
): JSONObject {
    val tagColors = JSONObject()
    colors.toSortedMap().forEach { (tag, color) ->
        tagColors.put(tag.trim().lowercase(), color.trim())
    }
    return JSONObject(current.toString()).put("tagColors", tagColors)
}
