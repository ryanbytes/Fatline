package dev.scanrelay.app.net

import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScanList
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import dev.scanrelay.app.model.UnitAlias
import org.json.JSONArray
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

    @Test
    fun liveCallsRespectActiveHistoryFilter() {
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            historySystemRef = 1,
            historyTalkgroupRef = 11
        )
        fun call(systemRef: Long, talkgroupRef: Long) = RadioCall(
            profileId = profile.id,
            serverName = profile.name,
            id = systemRef * 100 + talkgroupRef,
            systemRef = systemRef,
            talkgroupRef = talkgroupRef,
            systemLabel = "System",
            talkgroupLabel = "Talkgroup",
            dateTime = ""
        )

        assertTrue(ScannerRepository.matchesHistoryFilter(state, call(1, 11)))
        assertFalse(ScannerRepository.matchesHistoryFilter(state, call(1, 12)))
        assertFalse(ScannerRepository.matchesHistoryFilter(state, call(2, 11)))
    }

    @Test
    fun scanListSettingsMergePreservesUnrelatedPreferences() {
        val current = JSONObject(
            """
            {
              "autoLivefeed": true,
              "livefeedBacklogMinutes": 3,
              "theme": "dark",
              "activeScanListId": "old",
              "activeScanListIds": ["old"]
            }
            """.trimIndent()
        )
        val lists = listOf(
            ScanList(
                id = "fire",
                name = "Fire",
                channels = listOf(ChannelKey(1, 11))
            )
        )

        val updated = ScannerRepository.mergeScanListsIntoSettings(current, lists, systems)

        assertTrue(updated.getBoolean("autoLivefeed"))
        assertEquals(3, updated.getInt("livefeedBacklogMinutes"))
        assertEquals("dark", updated.getString("theme"))
        assertTrue(updated.isNull("activeScanListId"))
        assertEquals(0, updated.getJSONArray("activeScanListIds").length())

        val savedList = updated.getJSONArray("scanLists").getJSONObject(0)
        val savedChannel = savedList.getJSONArray("channels").getJSONObject(0)
        assertEquals("fire", savedList.getString("id"))
        assertEquals("Fire", savedList.getString("name"))
        assertEquals("1", savedChannel.getString("systemId"))
        assertEquals("11", savedChannel.getString("talkgroupId"))
        assertEquals("One", savedChannel.getString("systemLabel"))
        assertEquals("One-A", savedChannel.getString("talkgroupLabel"))
        assertTrue(savedChannel.getBoolean("isEnabled"))
    }

    @Test
    fun scanListSerializationDeduplicatesMembership() {
        val lists = listOf(
            ScanList(
                id = "dup",
                name = "Duplicate test",
                channels = listOf(ChannelKey(1, 11), ChannelKey(1, 11), ChannelKey(2, 21))
            )
        )

        val encoded = ScannerRepository.serializeScanLists(lists, systems)
        val channels = encoded.getJSONObject(0).getJSONArray("channels")

        assertEquals(2, channels.length())
    }

    @Test
    fun richAlertHistoryParsesAndSortsServerFields() {
        val raw = JSONArray(
            """
            [
              {
                "alertId": 10,
                "callId": 100,
                "alertType": "tone",
                "createdAt": 1000,
                "systemLabel": "County",
                "talkgroupLabel": "Fire Dispatch",
                "matchedToneSetNames": ["Station 1"],
                "keywordsMatched": "[]",
                "transcriptSnippet": "older alert"
              },
              {
                "alertId": 11,
                "callId": 101,
                "alertType": "keyword",
                "createdAt": 2000,
                "systemLabel": "County",
                "talkgroupLabel": "Law Dispatch",
                "keywordsMatched": "[\"pursuit\",\"vehicle\"]",
                "alertSummary": "Vehicle pursuit",
                "incidentAddress": "123 Main St",
                "incidentNature": "Pursuit",
                "incidentLat": 40.0,
                "incidentLon": -85.0
              }
            ]
            """.trimIndent()
        )

        val alerts = ScannerRepository.parseServerAlerts(profile, raw)

        assertEquals(listOf(11L, 10L), alerts.mapNotNull { it.alertId })
        assertEquals(listOf("pursuit", "vehicle"), alerts[0].keywords)
        assertEquals("123 Main St", alerts[0].incidentAddress)
        assertEquals("Vehicle pursuit", alerts[0].body)
        assertEquals(listOf("Station 1"), alerts[1].matchedToneSets)
        assertEquals(101L, alerts[0].callId)
    }

}
