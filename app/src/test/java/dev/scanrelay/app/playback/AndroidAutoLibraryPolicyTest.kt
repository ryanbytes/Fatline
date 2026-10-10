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
    @Test fun singlePassFavoritesMatchLegacyFilterMapOrderAndMetadata() {
        val profile = ServerProfile(id = "scannerA", name = "Scanner A", baseUrl = "http://scanner.local")
        val systems = (0 until 30).map { system ->
            SystemConfig(
                systemRef = system.toLong() + 1L,
                label = "System $system",
                talkgroups = (0 until 45).map { talkgroup ->
                    TalkgroupConfig(
                        systemRef = system.toLong() + 1L,
                        talkgroupRef = talkgroup.toLong() + 100L,
                        label = "TG $system:$talkgroup",
                        favorite = (system + talkgroup) % 4 == 0
                    )
                }
            )
        }
        for (hidden in listOf(emptySet(), setOf(1L, 3L, 5L, 7L), (1L..30L).toSet())) {
            val state = ScannerState(servers = mapOf(profile.id to ServerScannerState(
                profile = profile, systems = systems, hiddenSystemRefs = hidden
            )))
            // Independent reference to the exact previous implementation.
            val expected = systems.filterNot { it.systemRef in hidden }.flatMap { system ->
                system.talkgroups.filter { it.favorite }.map { talkgroup ->
                    AndroidAutoFavorite(
                        mediaId = "channel:${profile.id}:${talkgroup.systemRef}:${talkgroup.talkgroupRef}",
                        title = talkgroup.displayName,
                        subtitle = system.label
                    )
                }
            }
            assertEquals(expected, AndroidAutoLibraryPolicy.favoriteChannels(profile.id, state))
        }
        assertEquals(
            emptyList<AndroidAutoFavorite>(),
            AndroidAutoLibraryPolicy.favoriteChannels(profile.id, ScannerState())
        )
    }

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

    @Test fun autoLibraryBulkRefreshDoesNotNotifyOnPlaybackAndStatusChanges() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "https://scanner.invalid")
        val systems = listOf(SystemConfig(
            7, "Law", listOf(TalkgroupConfig(7, 56, "Dispatch", favorite = true))
        ))
        val base = ServerScannerState(profile = profile, systems = systems)
        val cache = AndroidAutoFavoritesCache()
        val notices = mutableListOf<Pair<String, Int>>()
        cache.forEachChanged(ScannerState(servers = mapOf("one" to base))) { id, count ->
            notices += id to count
        }
        assertTrue(notices.isEmpty()) // first snapshot only baselines the library
        repeat(50) { iteration ->
            cache.forEachChanged(ScannerState(servers = mapOf(
                "one" to base.copy(statusText = "Receiving $iteration", listenerCount = iteration)
            ))) { id, count -> notices += id to count }
        }
        assertTrue(notices.isEmpty())
        assertEquals(setOf("one"), cache.profileIds)
    }

    @Test fun autoLibraryBulkRefreshNotifiesOnlyActualFavoriteChanges() {
        val profile = ServerProfile(id = "one", name = "County", baseUrl = "https://scanner.invalid")
        val systems = listOf(SystemConfig(
            7, "Law", listOf(TalkgroupConfig(7, 56, "Dispatch", favorite = true))
        ))
        val base = ServerScannerState(profile = profile, systems = systems)
        val cache = AndroidAutoFavoritesCache()
        val notices = mutableListOf<Pair<String, Int>>()
        fun apply(server: ServerScannerState) {
            cache.forEachChanged(ScannerState(servers = mapOf("one" to server))) { id, count ->
                notices += id to count
            }
        }
        apply(base)
        assertTrue(notices.isEmpty())
        apply(base.copy(systems = listOf(SystemConfig(
            7, "Law", listOf(
                TalkgroupConfig(7, 56, "Dispatch", favorite = true),
                TalkgroupConfig(7, 57, "Fire", favorite = true)
            )
        ))))
        assertEquals(listOf("one" to 2), notices)
        notices.clear()
        apply(base.copy(systems = systems))
        assertEquals(listOf("one" to 1), notices)
        notices.clear()
        apply(base.copy(hiddenSystemRefs = setOf(7)))
        assertEquals(listOf("one" to 0), notices)
    }

    @Test fun autoLibraryBulkRefreshPrunesProfilesOnRemovalAndEqualSizeReplacement() {
        val one = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val two = ServerProfile(id = "two", name = "Two", baseUrl = "https://scanner.invalid")
        val systems = listOf(SystemConfig(
            7, "Law", listOf(TalkgroupConfig(7, 56, "Dispatch", favorite = true))
        ))
        val cache = AndroidAutoFavoritesCache()
        val changes = mutableListOf<Pair<String, Int>>()
        fun apply(servers: Map<String, ServerScannerState>) {
            cache.forEachChanged(ScannerState(servers = servers)) { id, count ->
                changes += id to count
            }
        }
        val old = ServerScannerState(profile = one, systems = systems)
        apply(mapOf("one" to old))
        assertTrue(changes.isEmpty())
        apply(mapOf("two" to ServerScannerState(profile = two, systems = systems)))
        assertEquals(listOf("one" to 0), changes)
        assertEquals(setOf("two"), cache.profileIds)
        changes.clear()
        apply(emptyMap())
        assertEquals(listOf("two" to 0), changes)
        assertTrue(cache.profileIds.isEmpty())
        changes.clear()
        apply(emptyMap())
        assertTrue(changes.isEmpty())
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
