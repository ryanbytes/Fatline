package dev.scanrelay.app.net

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import dev.scanrelay.app.model.UnitAlias
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerRepositoryTest {
    private val profile = ServerProfile(id = "test", name = "Test", baseUrl = "https://example.invalid")
    private val systems = listOf(
        SystemConfig(
            systemRef = 1,
            label = "One",
            talkgroups = listOf(
                TalkgroupConfig(1, 11, "One-A", enabled = true),
                TalkgroupConfig(1, 12, "One-B", enabled = true)
            )
        ),
        SystemConfig(
            systemRef = 2,
            label = "Two",
            talkgroups = listOf(
                TalkgroupConfig(2, 21, "Two-A", enabled = true)
            )
        )
    )

    @Test
    fun talkgroupHoldFiltersSubscriptionWithoutMutatingBaseSelection() {
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            hold = ChannelKey(1, 11)
        )

        val effective = ScannerRepository.effectiveLivefeedSystems(state)

        assertTrue(effective[0].talkgroups[0].enabled)
        assertFalse(effective[0].talkgroups[1].enabled)
        assertFalse(effective[1].talkgroups[0].enabled)
        assertTrue(state.systems[0].talkgroups[1].enabled)
        assertTrue(state.systems[1].talkgroups[0].enabled)
    }

    @Test
    fun systemHoldAndAvoidAreAppliedToSubscription() {
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            holdSystemRef = 1,
            avoided = setOf(ChannelKey(1, 12))
        )

        val effective = ScannerRepository.effectiveLivefeedSystems(state)

        assertTrue(effective[0].talkgroups[0].enabled)
        assertFalse(effective[0].talkgroups[1].enabled)
        assertFalse(effective[1].talkgroups[0].enabled)
    }
    @Test
    fun callSourcesAreOrderedDeduplicatedAndAliasAware() {
        val sourceSystems = listOf(
            SystemConfig(
                systemRef = 1,
                label = "One",
                talkgroups = emptyList(),
                units = listOf(
                    UnitAlias(label = "Engine 3", unitRef = 12345),
                    UnitAlias(label = "Portable", unitFrom = 20000, unitTo = 20099)
                )
            )
        )
        val payload = JSONObject(
            """
            {
              "source": 99999,
              "sources": [
                {"pos": 2, "src": 20042},
                {"pos": 0, "src": 12345},
                {"pos": 1, "src": 12345},
                {"pos": 3, "src": 30001, "tag": "Medic 7"}
              ]
            }
            """.trimIndent()
        )

        val sources = ScannerRepository.resolveCallSources(sourceSystems, 1, payload)

        assertEquals(listOf(12345L, 20042L, 30001L), sources.mapNotNull { it.sourceRef })
        assertEquals("Engine 3 | 12345", sources[0].display)
        assertEquals("Portable | 20042", sources[1].display)
        assertEquals("Medic 7 | 30001", sources[2].display)
    }

    @Test
    fun callSourcesFallBackToLegacySingleSource() {
        val payload = JSONObject("""{"source":12345}""")
        val sources = ScannerRepository.resolveCallSources(emptyList(), 1, payload)

        assertEquals(1, sources.size)
        assertEquals(12345L, sources.single().sourceRef)
        assertEquals("12345", sources.single().display)
    }

}
