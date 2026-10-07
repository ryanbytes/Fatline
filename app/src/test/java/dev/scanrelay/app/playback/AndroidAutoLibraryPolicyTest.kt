package dev.scanrelay.app.playback

import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoLibraryPolicyTest {
    @Test fun exposesOnlyFavoriteChannelsFromVisibleSystems() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "http://scanner.local")
        val state = ScannerState(
            servers = mapOf(
                profile.id to ServerScannerState(
                    profile = profile,
                    systems = listOf(
                        SystemConfig(
                            systemRef = 7,
                            label = "Law",
                            talkgroups = listOf(
                                TalkgroupConfig(7, 56, "Dispatch", favorite = true),
                                TalkgroupConfig(7, 57, "Fire", favorite = false)
                            )
                        ),
                        SystemConfig(
                            systemRef = 8,
                            label = "Hidden",
                            talkgroups = listOf(TalkgroupConfig(8, 1, "Hidden favorite", favorite = true))
                        )
                    ),
                    hiddenSystemRefs = setOf(8),
                    status = ConnectionStatus.CONNECTED
                )
            )
        )

        assertEquals(
            listOf(AndroidAutoFavorite("channel:one:7:56", "Dispatch", "Law")),
            AndroidAutoLibraryPolicy.favoriteChannels("one", state)
        )
    }

    @Test fun detectsOnlyMeaningfulFavoriteLibraryChanges() {
        val original = listOf(AndroidAutoFavorite("channel:one:7:56", "Dispatch", "Law"))
        assertFalse(AndroidAutoLibraryPolicy.childrenChanged(null, original))
        assertFalse(AndroidAutoLibraryPolicy.childrenChanged(original, original.toList()))
        assertTrue(
            AndroidAutoLibraryPolicy.childrenChanged(
                original,
                original + AndroidAutoFavorite("channel:one:7:57", "Fire", "Law")
            )
        )
        assertTrue(
            AndroidAutoLibraryPolicy.childrenChanged(
                original,
                listOf(original.single().copy(title = "County Dispatch"))
            )
        )
    }
}
