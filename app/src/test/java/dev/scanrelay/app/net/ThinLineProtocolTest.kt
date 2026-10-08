package dev.scanrelay.app.net

import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinLineProtocolTest {
    @Test fun pinIsBase64Encoded() {
        assertEquals("[\"PIN\",\"MTIzNA==\"]", ThinLineProtocol.pin("1234"))
    }

    @Test fun livefeedUses64BitRadioReferencesAndExplicitBooleans() {
        val systemRef = 4_294_967_299L
        val enabledRef = 8_589_934_599L
        val disabledRef = 8_589_934_600L
        val systems = listOf(
            SystemConfig(
                systemRef,
                "Large",
                listOf(
                    TalkgroupConfig(systemRef, enabledRef, "Dispatch", enabled = true),
                    TalkgroupConfig(systemRef, disabledRef, "Tac", enabled = false)
                )
            )
        )
        val parsed = ThinLineProtocol.parseEnvelope(ThinLineProtocol.livefeed(systems))
        val map = parsed.payload as JSONObject
        val talkgroups = map.getJSONObject(systemRef.toString())
        assertTrue(talkgroups.getBoolean(enabledRef.toString()))
        assertFalse(talkgroups.getBoolean(disabledRef.toString()))
    }

    @Test fun bareLivefeedCommandMatchesThinLinePauseWireType() {
        assertEquals("[\"LFM\"]", ThinLineProtocol.command(ThinLineProtocol.LIVEFEED_MAP))
    }

    @Test fun listCallUsesThinLineArchiveSearchFields() {
        val parsed = ThinLineProtocol.parseEnvelope(
            ThinLineProtocol.listCalls(
                limit = 200,
                offset = 400,
                sort = 1,
                systemRef = 1,
                talkgroupRef = 101,
                date = "2026-10-07T12:30:00Z",
                group = "County",
                tag = "Fire"
            )
        )
        val payload = parsed.payload as JSONObject
        assertEquals(200, payload.getInt("limit"))
        assertEquals(400, payload.getInt("offset"))
        assertEquals(1, payload.getInt("sort"))
        assertEquals(1L, payload.getLong("system"))
        assertEquals(101L, payload.getLong("talkgroup"))
        assertEquals("2026-10-07T12:30:00Z", payload.getString("date"))
        assertEquals("County", payload.getString("group"))
        assertEquals("Fire", payload.getString("tag"))
        assertFalse(payload.has("talkgroups"))
    }

    @Test fun multipleArchiveTalkgroupsUseUpstreamPluralField() {
        val payload = ThinLineProtocol.parseEnvelope(
            ThinLineProtocol.listCalls(systemRef = 1, talkgroupRefs = listOf(102, 101, 102, -1))
        ).payload as JSONObject
        assertFalse(payload.has("talkgroup"))
        val refs = payload.getJSONArray("talkgroups")
        assertEquals(2, refs.length())
        assertEquals(102L, refs.getLong(0))
        assertEquals(101L, refs.getLong(1))
    }

    @Test fun oneSelectedArchiveTalkgroupKeepsLegacySingularField() {
        val payload = ThinLineProtocol.parseEnvelope(
            ThinLineProtocol.listCalls(systemRef = 1, talkgroupRefs = listOf(101, 101))
        ).payload as JSONObject
        assertEquals(101L, payload.getLong("talkgroup"))
        assertFalse(payload.has("talkgroups"))
    }

    @Test fun callIdMatchesThinLineStringWireType() {
        assertEquals("[\"CAL\",\"42\"]", ThinLineProtocol.call(42))
    }

    @Test fun callDownloadFlagIsThirdEnvelopeElement() {
        assertEquals("[\"CAL\",\"42\",\"d\"]", ThinLineProtocol.call(42, true))
    }

    @Test fun playbackCallUsesPublicClientPlayFlag() {
        assertEquals("[\"CAL\",\"42\",\"p\"]", ThinLineProtocol.playbackCall(42))
    }


    @Test fun listenerCountAcceptsNumericAndStringPayloads() {
        assertEquals(7, ThinLineProtocol.parseListenerCount(7))
        assertEquals(12, ThinLineProtocol.parseListenerCount("12"))
        assertEquals(null, ThinLineProtocol.parseListenerCount("-1"))
        assertEquals(null, ThinLineProtocol.parseListenerCount("not-a-count"))
    }

    @Test fun pinSetAcceptsOnlyNonBlankStringPayloads() {
        assertEquals("123456", ThinLineProtocol.parsePinSet(" 123456 "))
        assertEquals(null, ThinLineProtocol.parsePinSet(""))
        assertEquals(null, ThinLineProtocol.parsePinSet(123456))
    }


    @Test fun scanListsParseFromUserSettingsWithStringAndNumericRefs() {
        val config = JSONObject(
            """
            {
              "userSettings": {
                "scanLists": [
                  {
                    "id": "fire",
                    "name": "Fire",
                    "channels": [
                      {"systemId": "1", "talkgroupId": "101"},
                      {"systemId": 1, "talkgroupId": 102},
                      {"systemId": "1", "talkgroupId": "101"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val lists = ThinLineProtocol.parseScanLists(config)

        assertEquals(1, lists.size)
        assertEquals("fire", lists.single().id)
        assertEquals("Fire", lists.single().name)
        assertEquals(2, lists.single().channels.size)
        assertEquals(1L, lists.single().channels[0].systemRef)
        assertEquals(101L, lists.single().channels[0].talkgroupRef)
        assertEquals(102L, lists.single().channels[1].talkgroupRef)
    }

    @Test fun systemsParseToneDetectionAndToneSets() {
        val config = JSONObject(
            """
            {
              "systems": [
                {
                  "systemRef": 1,
                  "label": "County",
                  "talkgroups": [
                    {
                      "talkgroupRef": 101,
                      "label": "Fire Dispatch",
                      "toneDetectionEnabled": true,
                      "toneSets": [
                        {"id": "station-1", "label": "Station 1"},
                        {"id": "station-2", "label": ""},
                        {"id": "station-1", "label": "Duplicate"}
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val talkgroup = ThinLineProtocol.parseSystems(config).single().talkgroups.single()

        assertTrue(talkgroup.toneDetectionEnabled)
        assertEquals(listOf("station-1", "station-2"), talkgroup.toneSets.map { it.id })
        assertEquals(listOf("Station 1", "station-2"), talkgroup.toneSets.map { it.label })
    }

    @Test fun systemsRetainDatabaseIdsForTranscriptFilters() {
        val config = JSONObject(
            """
            {
              "systems": [
                {
                  "id": 1001,
                  "systemId": 12,
                  "systemRef": 1001,
                  "label": "County",
                  "talkgroups": [
                    {
                      "id": 2002,
                      "talkgroupId": 34,
                      "talkgroupRef": 2002,
                      "label": "Dispatch"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val system = ThinLineProtocol.parseSystems(config).single()
        val talkgroup = system.talkgroups.single()

        assertEquals(12L, system.systemId)
        assertEquals(34L, talkgroup.talkgroupId)
        assertEquals(1001L, system.systemRef)
        assertEquals(2002L, talkgroup.talkgroupRef)
    }

    @Test fun systemsParseExactAndRangeUnitAliases() {
        val config = JSONObject(
            """
            {
              "systems": [
                {
                  "systemRef": 1,
                  "label": "County",
                  "talkgroups": [],
                  "units": [
                    {"id": 7, "unitRef": 12345, "unitFrom": 0, "unitTo": 0, "label": "Engine 3"},
                    {"id": 8, "unitRef": 0, "unitFrom": 20000, "unitTo": 20099, "label": "Portable"},
                    {"id": 45678, "unitRef": 0, "unitFrom": 0, "unitTo": 0, "label": "Legacy"}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val units = ThinLineProtocol.parseSystems(config).single().units

        assertEquals("Engine 3", units.first { it.matches(12345) }.label)
        assertEquals("Portable", units.first { it.matches(20042) }.label)
        assertEquals("Legacy", units.first { it.matches(45678) }.label)
    }


    @Test fun systemsRetainTalkgroupGroupsForArchiveFilters() {
        val config = JSONObject(
            """
            {
              "systems": [
                {
                  "systemRef": 1,
                  "label": "County",
                  "talkgroups": [
                    {
                      "talkgroupRef": 101,
                      "label": "Fire Dispatch",
                      "groups": ["Dispatch", "Public Safety", ""]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("Dispatch", "Public Safety"),
            ThinLineProtocol.parseSystems(config).single().talkgroups.single().groups
        )
    }

}
