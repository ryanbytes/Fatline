package dev.scanrelay.app.net

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.FavoriteTagKey
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSettingsTest {
    private val system = SystemConfig(
        systemRef = 10,
        label = "County",
        talkgroups = listOf(
            TalkgroupConfig(10, 101, "Fire Dispatch", tag = "Fire"),
            TalkgroupConfig(10, 102, "Fire Tac", tag = "Fire"),
            TalkgroupConfig(10, 201, "Sheriff", tag = "Law"),
            TalkgroupConfig(10, 301, "Interop", tag = "")
        )
    )

    @Test
    fun parsesTypedFavoritesAndExpandsParentScopesIntoChannels() {
        val settings = JSONObject().put(
            "favorites",
            JSONArray()
                .put(JSONObject().put("type", "tag").put("systemId", 10).put("tag", "Fire"))
                .put(JSONObject().put("type", "talkgroup").put("systemId", 10).put("talkgroupId", 201))
                .put(JSONObject().put("type", "tag").put("systemId", 10).put("tag", "Untagged"))
        )

        val selection = parseFavoriteSelection(settings, listOf(system))!!

        assertEquals(
            setOf(
                ChannelKey(10, 101),
                ChannelKey(10, 102),
                ChannelKey(10, 201),
                ChannelKey(10, 301)
            ),
            selection.channels
        )
        assertEquals(
            setOf(FavoriteTagKey(10, "Fire"), FavoriteTagKey(10, "Untagged")),
            selection.tags
        )
        assertTrue(selection.systemRefs.isEmpty())
    }

    @Test
    fun absentFavoritesDoesNotOverrideLocalFavorites() {
        assertNull(parseFavoriteSelection(JSONObject().put("scanLists", JSONArray()), listOf(system)))
    }

    @Test
    fun serializesOnlyExplicitValidParentMarkers() {
        val partial = serializeFavorites(
            FavoriteSettingsSelection(
                channels = setOf(ChannelKey(10, 101), ChannelKey(10, 102)),
                tags = setOf(FavoriteTagKey(10, "Fire"))
            ),
            listOf(system)
        )
        val partialTypes = (0 until partial.length()).map { partial.getJSONObject(it).getString("type") }
        assertTrue("tag" in partialTypes)
        assertFalse("system" in partialTypes)

        val all = serializeFavorites(
            FavoriteSettingsSelection(
                channels = system.talkgroups.map { it.key }.toSet(),
                systemRefs = setOf(10),
                tags = setOf(
                    FavoriteTagKey(10, "Fire"),
                    FavoriteTagKey(10, "Law"),
                    FavoriteTagKey(10, "Untagged")
                )
            ),
            listOf(system)
        )
        val allTypes = (0 until all.length()).map { all.getJSONObject(it).getString("type") }
        assertEquals(1, allTypes.count { it == "system" })
        assertEquals(3, allTypes.count { it == "tag" })
        assertEquals(4, allTypes.count { it == "talkgroup" })
    }

    @Test
    fun individualFavoriteDoesNotInventSingleMemberTagFavorite() {
        val json = serializeFavorites(
            FavoriteSettingsSelection(channels = setOf(ChannelKey(10, 201))),
            listOf(system)
        )

        assertEquals(1, json.length())
        assertEquals("talkgroup", json.getJSONObject(0).getString("type"))
        assertEquals(201L, json.getJSONObject(0).getLong("talkgroupId"))
    }

    @Test
    fun mergePreservesOtherSettings() {
        val merged = mergeFavoritesIntoSettings(
            JSONObject().put("uiAccentColor", "#123456").put("scanLists", JSONArray().put("keep")),
            FavoriteSettingsSelection(channels = setOf(ChannelKey(10, 201))),
            listOf(system)
        )

        assertEquals("#123456", merged.getString("uiAccentColor"))
        assertEquals("keep", merged.getJSONArray("scanLists").getString(0))
        assertEquals(1, merged.getJSONArray("favorites").length())
    }
}
