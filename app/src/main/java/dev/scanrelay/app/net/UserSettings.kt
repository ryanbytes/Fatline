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
