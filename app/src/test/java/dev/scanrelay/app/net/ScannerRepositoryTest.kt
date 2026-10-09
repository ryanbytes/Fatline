package dev.scanrelay.app.net

import dev.scanrelay.app.model.AlertPreference
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
    @Test
    fun historyContinueStartsAtSelectedAndMovesTowardNewest() {
        assertEquals(
            listOf(30L, 40L, 50L),
            ScannerRepository.continuationCallIds(
                historyIdsNewestFirst = listOf(50L, 40L, 30L, 20L),
                startId = 30L
            )
        )
    }

    @Test
    fun historyContinueIgnoresCallsOlderThanSelectionAndMissingSelection() {
        assertEquals(
            listOf(20L, 30L, 40L),
            ScannerRepository.continuationCallIds(
                historyIdsNewestFirst = listOf(40L, 30L, 20L, 10L),
                startId = 20L
            )
        )
        assertEquals(
            emptyList<Long>(),
            ScannerRepository.continuationCallIds(listOf(3L, 2L, 1L), 99L)
        )
    }

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
    fun replayLastChoosesMostRecentReceivedLiveCall() {
        val old = RadioCall(
            profileId = profile.id, serverName = profile.name, id = 25L,
            systemRef = 1, talkgroupRef = 11, systemLabel = "One",
            talkgroupLabel = "One-A", dateTime = ""
        )
        val newer = old.copy(id = 26L)
        val state = ServerScannerState(
            profile = profile,
            recentCalls = listOf(newer, old),
            lastCall = old
        )
        assertEquals(26L, ScannerRepository.lastReplayCandidate(state)?.id)
    }

    @Test
    fun replayLastFallsBackToLastReceivedWhenRecentListEmpty() {
        val call = RadioCall(
            profileId = profile.id, serverName = profile.name, id = 99L,
            systemRef = 1, talkgroupRef = 11, systemLabel = "One",
            talkgroupLabel = "One-A", dateTime = ""
        )
        assertEquals(99L, ScannerRepository.lastReplayCandidate(ServerScannerState(profile = profile, lastCall = call))?.id)
        assertEquals(null, ScannerRepository.lastReplayCandidate(ServerScannerState(profile = profile)))
    }

    @Test
    fun liveAlertPrefersTitleMessageAndCarriesTimestamp() {
        val alert = ScannerRepository.parseRealtimeAlert(
            profile,
            JSONObject("""{"title":"Fire alert","message":"Unit 123 on scene","dateTime":"2026-10-07T18:30:00Z"}""")
        )

        assertEquals("Fire alert", alert.title)
        assertEquals("Unit 123 on scene", alert.body)
        assertEquals("2026-10-07T18:30:00Z", alert.dateTime)
        assertEquals(profile.id, alert.profileId)
    }

    @Test
    fun liveAlertFallsBackToTypeSummaryAndRawPayload() {
        val structured = ScannerRepository.parseRealtimeAlert(
            profile,
            JSONObject("""{"type":"Tone alert","summary":"Second stage page"}""")
        )
        assertEquals("Tone alert", structured.title)
        assertEquals("Second stage page", structured.body)

        val raw = ScannerRepository.parseRealtimeAlert(profile, "signal received")
        assertEquals("Scanner alert", raw.title)
        assertEquals("signal received", raw.body)
    }

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
    fun archiveEntryRefreshClearsResultsButRetainsAllAppliedFilters() {
        val call = RadioCall(
            profileId = profile.id,
            serverName = profile.name,
            id = 101L,
            systemRef = 1,
            talkgroupRef = 11,
            systemLabel = "One",
            talkgroupLabel = "One-A",
            dateTime = ""
        )
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            history = listOf(call),
            historyHasMore = true,
            historySystemRef = 1,
            historyTalkgroupRefs = listOf(11, 12),
            historyDate = "2026-10-08T12:00:00Z",
            historyGroup = "Dispatch",
            historyTag = "Fire",
            historySort = 1,
            recentCalls = listOf(call),
            paused = true
        )
        val refreshed = ScannerRepository.clearedHistoryForRefresh(state)
        assertTrue(refreshed.history.isEmpty())
        assertFalse(refreshed.historyHasMore)
        assertEquals(state.historySystemRef, refreshed.historySystemRef)
        assertEquals(state.historyTalkgroupRefs, refreshed.historyTalkgroupRefs)
        assertEquals(state.historyDate, refreshed.historyDate)
        assertEquals(state.historyGroup, refreshed.historyGroup)
        assertEquals(state.historyTag, refreshed.historyTag)
        assertEquals(state.historySort, refreshed.historySort)
        assertEquals(state.recentCalls, refreshed.recentCalls)
        assertEquals(state.paused, refreshed.paused)
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
    fun multiTalkgroupHistoryFilterKeepsOnlySelectedCallsInSystem() {
        val state = ServerScannerState(
            profile = profile,
            systems = systems,
            historySystemRef = 1,
            historyTalkgroupRefs = listOf(11, 12)
        )
        fun call(system: Long, talkgroup: Long) = RadioCall(
            profileId = profile.id,
            serverName = profile.name,
            id = system * 100 + talkgroup,
            systemRef = system,
            talkgroupRef = talkgroup,
            systemLabel = "System",
            talkgroupLabel = "TG",
            dateTime = ""
        )
        assertTrue(ScannerRepository.matchesHistoryFilter(state, call(1, 11)))
        assertTrue(ScannerRepository.matchesHistoryFilter(state, call(1, 12)))
        assertFalse(ScannerRepository.matchesHistoryFilter(state, call(1, 13)))
        assertFalse(ScannerRepository.matchesHistoryFilter(state, call(2, 11)))
    }

    @Test
    fun liveCallsRespectAdvancedArchiveFilters() {
        val groupedSystems = listOf(
            SystemConfig(
                systemRef = 1,
                label = "County",
                talkgroups = listOf(
                    TalkgroupConfig(
                        systemRef = 1,
                        talkgroupRef = 11,
                        label = "Fire Dispatch",
                        tag = "Fire",
                        groups = listOf("Dispatch", "Public Safety")
                    ),
                    TalkgroupConfig(
                        systemRef = 1,
                        talkgroupRef = 12,
                        label = "Law Dispatch",
                        tag = "Law",
                        groups = listOf("Dispatch")
                    )
                )
            )
        )
        val state = ServerScannerState(
            profile = profile,
            systems = groupedSystems,
            historyGroup = "Public Safety",
            historyTag = "Fire",
            historyDate = "2026-10-07T12:00:00Z"
        )
        fun call(talkgroupRef: Long, dateTime: String) = RadioCall(
            profileId = profile.id,
            serverName = profile.name,
            id = talkgroupRef,
            systemRef = 1,
            talkgroupRef = talkgroupRef,
            systemLabel = "County",
            talkgroupLabel = "Talkgroup",
            dateTime = dateTime
        )

        assertTrue(
            ScannerRepository.matchesHistoryFilter(
                state,
                call(11, "2026-10-07T12:00:01Z")
            )
        )
        assertFalse(
            ScannerRepository.matchesHistoryFilter(
                state,
                call(12, "2026-10-07T12:00:01Z")
            )
        )
        assertFalse(
            ScannerRepository.matchesHistoryFilter(
                state,
                call(11, "2026-10-07T11:59:59Z")
            )
        )
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

    @Test
    fun alertPreferencesRoundTripPreservesServerExtras() {
        val raw = JSONArray(
            """
            [
              {
                "systemRef": 1,
                "talkgroupRef": 11,
                "alertEnabled": true,
                "toneAlerts": false,
                "keywordAlerts": true,
                "keywords": ["pursuit", "armed"],
                "keywordListIds": [7, "8"],
                "toneSetIds": ["station-1", "station-2"],
                "notificationSound": "alert.wav",
                "toneSetSounds": {"station-1":"chirp.wav"},
                "pagerAlert": true,
                "toneSetPagerAlerts": {"station-2":true}
              }
            ]
            """.trimIndent()
        )

        val parsed = ScannerRepository.parseAlertPreferences(raw)
        val pref = parsed.single()

        assertEquals(ChannelKey(1, 11), pref.key)
        assertTrue(pref.alertEnabled)
        assertFalse(pref.toneAlerts)
        assertTrue(pref.keywordAlerts)
        assertEquals(listOf("pursuit", "armed"), pref.keywords)
        assertEquals(listOf(7L, 8L), pref.keywordListIds)
        assertEquals(listOf("station-1", "station-2"), pref.toneSetIds)
        assertEquals("alert.wav", pref.notificationSound)
        assertEquals("chirp.wav", pref.toneSetSounds["station-1"])
        assertTrue(pref.pagerAlert)
        assertTrue(pref.toneSetPagerAlerts["station-2"] == true)

        val encoded = ScannerRepository.serializeAlertPreferences(parsed).getJSONObject(0)
        assertEquals(1L, encoded.getLong("systemRef"))
        assertEquals(11L, encoded.getLong("talkgroupRef"))
        assertEquals("alert.wav", encoded.getString("notificationSound"))
        assertEquals("chirp.wav", encoded.getJSONObject("toneSetSounds").getString("station-1"))
        assertTrue(encoded.getJSONObject("toneSetPagerAlerts").getBoolean("station-2"))
        assertEquals(2, encoded.getJSONArray("keywords").length())
        assertEquals(2, encoded.getJSONArray("keywordListIds").length())
        assertEquals(2, encoded.getJSONArray("toneSetIds").length())
    }

    @Test
    fun newAlertPreferenceUsesServerCompatibleDefaults() {
        val encoded = ScannerRepository.serializeAlertPreferences(
            listOf(AlertPreference(systemRef = 2, talkgroupRef = 21, alertEnabled = true))
        ).getJSONObject(0)

        assertTrue(encoded.getBoolean("alertEnabled"))
        assertTrue(encoded.getBoolean("toneAlerts"))
        assertTrue(encoded.getBoolean("keywordAlerts"))
        assertEquals(0, encoded.getJSONArray("keywords").length())
        assertEquals(0, encoded.getJSONArray("keywordListIds").length())
        assertEquals(0, encoded.getJSONArray("toneSetIds").length())
    }

    @Test
    fun keywordListsParseLabelsDescriptionsAndKeywords() {
        val raw = JSONArray(
            """
            [
              {
                "id": 9,
                "label": "Priority",
                "description": "High-priority phrases",
                "keywords": ["pursuit", "shots fired", "pursuit"]
              },
              {
                "id": 10,
                "label": "Medical",
                "keywords": ["cardiac arrest"]
              }
            ]
            """.trimIndent()
        )

        val lists = ScannerRepository.parseAlertKeywordLists(raw)

        assertEquals(listOf("Medical", "Priority"), lists.map { it.label })
        val priority = lists.first { it.id == 9L }
        assertEquals("High-priority phrases", priority.description)
        assertEquals(listOf("pursuit", "shots fired"), priority.keywords)
    }

    @Test
    fun systemAlertsParseAndSortVisibleServerAlerts() {
        val parsed = ScannerRepository.parseSystemAlerts(
            JSONObject(
                """
                {
                  "canViewSystemAlerts": true,
                  "alerts": [
                    {
                      "id": 4,
                      "alertType": "no_audio",
                      "severity": "warning",
                      "title": "No audio",
                      "message": "System 1 has not received audio",
                      "data": "{\"systemRef\":1}",
                      "createdAt": 100,
                      "dismissed": false
                    },
                    {
                      "id": 5,
                      "alertType": "manual",
                      "severity": "critical",
                      "title": "Maintenance",
                      "message": "Scanner maintenance",
                      "createdAt": 200
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(parsed.canViewSystemAlerts)
        assertEquals(listOf(5L, 4L), parsed.alerts.map { it.id })
        assertEquals("critical", parsed.alerts.first().severity)
        assertEquals("No audio", parsed.alerts.last().title)
    }

    @Test
    fun systemAlertUrlsUseCanonicalEndpoints() {
        assertEquals(
            "https://scanner.example.com:3000/api/system-alerts?limit=50&includeDismissed=false",
            ScannerRepository.systemAlertsUrl("wss://scanner.example.com:3000/path")
        )
        assertEquals(
            "https://scanner.example.com:3000/api/system-alerts/17",
            ScannerRepository.systemAlertsUrl("wss://scanner.example.com:3000/path", 17)
        )
    }

    @Test
    fun keywordListPayloadTrimsAndDeduplicatesFields() {
        val payload = ScannerRepository.keywordListPayload(
            "  Priority  ",
            "  High-priority phrases  ",
            " pursuit, shots fired\npursuit,  cardiac arrest "
        )

        assertEquals("Priority", payload.getString("label"))
        assertEquals("High-priority phrases", payload.getString("description"))
        val keywords = payload.getJSONArray("keywords")
        assertEquals(3, keywords.length())
        assertEquals("pursuit", keywords.getString(0))
        assertEquals("shots fired", keywords.getString(1))
        assertEquals("cardiac arrest", keywords.getString(2))
    }

    @Test
    fun keywordListUrlsUseCanonicalEndpoint() {
        assertEquals(
            "https://scanner.example.com:3000/api/keyword-lists",
            ScannerRepository.keywordListUrl("wss://scanner.example.com:3000/path")
        )
        assertEquals(
            "https://scanner.example.com:3000/api/keyword-lists/9",
            ScannerRepository.keywordListUrl("wss://scanner.example.com:3000/path", 9)
        )
    }

    @Test
    fun callDownloadUsesDedicatedAuthenticatedAudioEndpoint() {
        assertEquals(
            "https://scanner.example.com:3000/api/calls/42/audio",
            ScannerRepository.callAudioDownloadUrl("wss://scanner.example.com:3000/path", 42)
        )
    }

    @Test
    fun callDownloadFilenameUsesServerNameAndSanitizesPaths() {
        assertEquals(
            "dispatch 42.mp3",
            ScannerRepository.callDownloadFilename(
                "inline; filename=\"dispatch 42.mp3\"",
                42,
                "audio/mpeg"
            )
        )
        assertEquals(
            "evil.wav",
            ScannerRepository.callDownloadFilename(
                "inline; filename=\"../../evil.wav\"",
                43,
                "audio/wav"
            )
        )
        assertEquals(
            "FatLine-call-44.m4a",
            ScannerRepository.callDownloadFilename(null, 44, "audio/mp4")
        )
    }

    @Test
    fun alertToneSetIdsTrimDropBlanksAndDeduplicate() {
        val ids = ScannerRepository.normalizeAlertToneSetIds(
            listOf(" station-1 ", "", "station-2", "station-1")
        )

        assertEquals(listOf("station-1", "station-2"), ids)
    }

    @Test
    fun customAlertKeywordsNormalizeCommaNewlineWhitespaceAndDuplicates() {
        val keywords = ScannerRepository.normalizeAlertKeywords(
            " pursuit, shots fired\npursuit,  cardiac arrest  , "
        )

        assertEquals(listOf("pursuit", "shots fired", "cardiac arrest"), keywords)
    }

}
