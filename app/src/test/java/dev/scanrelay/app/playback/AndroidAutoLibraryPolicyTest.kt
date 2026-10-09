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

    @Test fun skipsRebuildOnLiveCallAndAlertStateChanges() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "https://scanner.local")
        val systems = listOf(
            SystemConfig(7, "Law", listOf(TalkgroupConfig(7, 56, "Dispatch", favorite = true)))
        )
        val base = ServerScannerState(profile = profile, systems = systems, status = ConnectionStatus.CONNECTED)
        val cache = AndroidAutoFavoritesCache()
        val initial = cache.refresh(profile.id, ScannerState(servers = mapOf(profile.id to base)))
        assertTrue(initial.rebuilt)
        assertFalse(initial.changed)
        assertEquals(1, initial.favorites.size)

        // Neither status nor call/alert flow changes the systems/hidden-system
        // object references in these immutable scanner-state snapshots.
        val liveStatusUpdate = base.copy(status = ConnectionStatus.DISCONNECTED, statusText = "Reconnecting")
        val update = cache.refresh(profile.id, ScannerState(servers = mapOf(profile.id to liveStatusUpdate)))
        assertFalse(update.rebuilt)
        assertFalse(update.changed)
        assertTrue(initial.favorites === update.favorites)
    }

    @Test fun changedFavoritesAndHiddenSystemsRefreshAutoLibrary() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "https://scanner.local")
        val originalSystems = listOf(
            SystemConfig(7, "Law", listOf(TalkgroupConfig(7, 56, "Dispatch", favorite = true)))
        )
        val base = ServerScannerState(profile = profile, systems = originalSystems)
        val cache = AndroidAutoFavoritesCache()
        cache.refresh(profile.id, ScannerState(servers = mapOf(profile.id to base)))

        // Rebuilt configuration with the same effective favorites should not
        // notify Android Auto, even though it was necessary to recalculate.
        val sameConfig = base.copy(systems = originalSystems.toList())
        val same = cache.refresh(profile.id, ScannerState(servers = mapOf(profile.id to sameConfig)))
        // Kotlin may optimize toList() on singleton lists by reusing the object.
        assertFalse(same.changed)

        val newSystems = listOf(
            SystemConfig(7, "Law", listOf(
                TalkgroupConfig(7, 56, "Dispatch", favorite = true),
                TalkgroupConfig(7, 57, "Fire", favorite = true)
            ))
        )
        val changed = cache.refresh(profile.id, ScannerState(servers = mapOf(profile.id to base.copy(systems = newSystems))))
        assertTrue(changed.rebuilt)
        assertTrue(changed.changed)
        assertEquals(2, changed.favorites.size)

        val hidden = cache.refresh(profile.id, ScannerState(servers = mapOf(profile.id to base.copy(systems = newSystems, hiddenSystemRefs = setOf(7)))))
        assertTrue(hidden.rebuilt)
        assertTrue(hidden.changed)
        assertTrue(hidden.favorites.isEmpty())
    }

    @Test fun removedServerClearsCachedFavoritesAndDoesNotLeakStaleEntries() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "https://scanner.local")
        val state = ScannerState(servers = mapOf(profile.id to ServerScannerState(
            profile = profile,
            systems = listOf(SystemConfig(7, "Law", listOf(TalkgroupConfig(7, 56, "Dispatch", favorite = true))))
        )))
        val cache = AndroidAutoFavoritesCache()
        cache.refresh(profile.id, state)
        assertEquals(setOf("one"), cache.profileIds)
        val removed = cache.refresh(profile.id, ScannerState())
        assertTrue(removed.changed)
        assertTrue(removed.rebuilt)
        assertTrue(removed.favorites.isEmpty())
        assertTrue(cache.profileIds.isEmpty())
        assertFalse(cache.refresh(profile.id, ScannerState()).changed)
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
