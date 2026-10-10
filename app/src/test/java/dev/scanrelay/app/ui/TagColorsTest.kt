package dev.scanrelay.app.ui

import org.junit.Assert.assertEquals
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.junit.Assert.assertNull
import org.junit.Test

class TagColorsTest {
    @Test
    fun normalizesThreeAndSixDigitHex() {
        assertEquals("#aabbcc", TagColors.normalizeHex("#abc"))
        assertEquals("#2979ff", TagColors.normalizeHex("2979FF"))
        assertNull(TagColors.normalizeHex("orange"))
    }

    @Test
    fun activeCallTagColorUsesConfiguredOverridesAndSemanticDefaults() {
        val profile = ServerProfile(id = "server1", name = "County", baseUrl = "https://scanner.invalid")
        val server = ServerScannerState(
            profile = profile,
            systems = listOf(
                SystemConfig(10, "Public Safety", listOf(
                    TalkgroupConfig(10, 100, "Fire Dispatch", tag = "Fire"),
                    TalkgroupConfig(10, 200, "County Police", tag = "Law"),
                    TalkgroupConfig(10, 300, "No Tag", tag = "")
                ))
            ),
            tagColors = mapOf("fire" to "#00e5ff")
        )
        val call = RadioCall(
            profileId = profile.id, serverName = profile.name, id = 8,
            systemRef = 10, talkgroupRef = 100, systemLabel = "Public Safety",
            talkgroupLabel = "Fire Dispatch", dateTime = ""
        )
        assertEquals(UiAccentRgb(0, 229, 255), TagColors.playingCallColor(call, server))
        assertEquals(UiAccentRgb(41, 121, 255),
            TagColors.playingCallColor(call.copy(talkgroupRef = 200), server))
        assertNull(TagColors.playingCallColor(call.copy(talkgroupRef = 300), server))
        assertNull(TagColors.playingCallColor(call.copy(talkgroupRef = 999), server))
        assertNull(TagColors.playingCallColor(call.copy(profileId = "other"), server))
        assertNull(TagColors.playingCallColor(null, server))
        assertNull(TagColors.playingCallColor(call, null))

        val recolored = server.copy(tagColors = mapOf("fire" to "#ff1744"))
        assertEquals(UiAccentRgb(255, 23, 68), TagColors.playingCallColor(call, recolored))
    }

    @Test
    fun userColorOverridesSemanticDefault() {
        val colors = mapOf("fire dispatch" to "#00e5ff")
        assertEquals("#00e5ff", TagColors.resolvedHex("Fire Dispatch", colors))
        assertEquals("#2979ff", TagColors.resolvedHex("Law Dispatch", emptyMap()))
        assertEquals("#ffffff", TagColors.resolvedHex("Unknown", emptyMap()))
    }
}
