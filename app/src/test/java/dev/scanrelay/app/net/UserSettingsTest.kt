package dev.scanrelay.app.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UserSettingsTest {
    @Test
    fun parsesBacklogWithSafeDefaultAndClamp() {
        assertEquals(0, parseLivefeedBacklogMinutes(null))
        assertEquals(0, parseLivefeedBacklogMinutes(JSONObject()))
        assertEquals(3, parseLivefeedBacklogMinutes(JSONObject().put("livefeedBacklogMinutes", 3)))
        assertEquals(0, parseLivefeedBacklogMinutes(JSONObject().put("livefeedBacklogMinutes", -4)))
    }

    @Test
    fun mergeBacklogPreservesOtherUserSettings() {
        val merged = mergeLivefeedBacklogIntoSettings(
            JSONObject()
                .put("favorites", JSONArray().put("keep"))
                .put("appFont", "Roboto"),
            5
        )

        assertEquals(5, merged.getInt("livefeedBacklogMinutes"))
        assertEquals("Roboto", merged.getString("appFont"))
        assertEquals("keep", merged.getJSONArray("favorites").getString(0))
    }
    @Test
    fun parsesTagColorsWithNormalizedKeys() {
        val settings = JSONObject().put(
            "tagColors",
            JSONObject()
                .put("Fire Dispatch", "#ff1744")
                .put(" LAW ", "#2979ff")
        )

        assertEquals(
            mapOf("fire dispatch" to "#ff1744", "law" to "#2979ff"),
            parseTagColors(settings)
        )
    }

    @Test
    fun mergeTagColorsPreservesOtherUserSettings() {
        val merged = mergeTagColorsIntoSettings(
            JSONObject()
                .put("livefeedBacklogMinutes", 4)
                .put("favorites", JSONArray().put("keep")),
            mapOf("Fire Dispatch" to "#00e5ff")
        )

        assertEquals(4, merged.getInt("livefeedBacklogMinutes"))
        assertEquals("keep", merged.getJSONArray("favorites").getString(0))
        assertEquals(
            "#00e5ff",
            merged.getJSONObject("tagColors").getString("fire dispatch")
        )
    }

}
