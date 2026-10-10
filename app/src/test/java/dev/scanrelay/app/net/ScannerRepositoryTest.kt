package dev.scanrelay.app.net

import dev.scanrelay.app.model.AlertPreference
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.CallSource
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScannerAlert
import dev.scanrelay.app.model.ScannerState
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScannerRepositoryTest {
    private fun historyCall(id: Long, time: String, label: String = "Call $id"): RadioCall =
        RadioCall(
            profileId = "one", serverName = "Scanner", id = id,
            systemRef = 1, talkgroupRef = 42, systemLabel = "Law",
            talkgroupLabel = label, dateTime = time
        )

    // Keep an independent, deliberately slower reference to the previous
    // associateBy+stable-sort behavior for an exact equivalence check.
    private fun oldHistoryInsertion(
        history: List<RadioCall>, call: RadioCall, newestFirst: Boolean
    ): List<RadioCall> {
        val merged = (history + call).associateBy { it.id }.values
        val sortKey: (RadioCall) -> Long = {
            runCatching { Instant.parse(it.dateTime).toEpochMilli() }.getOrDefault(0L)
        }
        return if (newestFirst) merged.sortedByDescending(sortKey) else merged.sortedBy(sortKey)
    }

    @Test
    fun historyTimestampSortMatchesLegacyStableOrderForLargeArchives() {
        val base = Instant.parse("2026-10-09T09:00:00Z").toEpochMilli()
        val calls = (0 until 600).map { index ->
            val time = when (index % 13) {
                0 -> "invalid-date"
                1 -> ""
                else -> Instant.ofEpochMilli(base + (index % 41) * 1_000L).toString()
            }
            historyCall(index.toLong(), time)
        }.reversed()
        val referenceKey: (RadioCall) -> Long = {
            runCatching { Instant.parse(it.dateTime).toEpochMilli() }.getOrDefault(0L)
        }
        for (newestFirst in listOf(true, false)) {
            val legacy = if (newestFirst) calls.sortedByDescending(referenceKey)
                else calls.sortedBy(referenceKey)
            assertEquals(
                "newestFirst=$newestFirst",
                legacy,
                HistoryTimestampSortPolicy.sort(calls, newestFirst)
            )
        }
        assertEquals(emptyList<RadioCall>(), HistoryTimestampSortPolicy.sort(emptyList(), true))
        assertEquals(calls.take(1), HistoryTimestampSortPolicy.sort(calls.take(1), false))
    }

    @Test
    fun archiveSortCalculatesTimestampOncePerCallNotPerComparator() {
        val base = Instant.parse("2026-10-09T09:00:00Z").toEpochMilli()
        val calls = (0 until 420).map { index ->
            historyCall(index.toLong(), Instant.ofEpochMilli(base + (index % 31) * 1_000L).toString())
        }.reversed()
        for (descending in listOf(true, false)) {
            var parsedCount = 0
            val sorted = HistoryTimestampSortPolicy.sort(calls, newestFirst = descending) {
                parsedCount++
                Instant.parse(it.dateTime).toEpochMilli()
            }
            assertEquals("parse count", calls.size, parsedCount)
            val expected = if (descending) calls.sortedByDescending { Instant.parse(it.dateTime) }
                else calls.sortedBy { Instant.parse(it.dateTime) }
            assertEquals(expected, sorted)
        }
    }

    @Test
    fun archiveStableTiesAndInvalidDatesPreserveInsertionOrder() {
        val calls = listOf(
            historyCall(1, "bad"),
            historyCall(2, "2026-10-09T09:00:00Z"),
            historyCall(3, ""),
            historyCall(4, "2026-10-09T09:00:00Z"),
            historyCall(5, "bad")
        )
        assertEquals(
            listOf(2L, 4L, 1L, 3L, 5L),
            HistoryTimestampSortPolicy.sort(calls, true).map { it.id }
        )
        assertEquals(
            listOf(1L, 3L, 5L, 2L, 4L),
            HistoryTimestampSortPolicy.sort(calls, false).map { it.id }
        )
    }

    @Test
    fun audioCacheFilenamesPreserveOldAsciiSanitizingRules() {
        val profiles = listOf(
            "", "p1", "0d5421bc-935e-4ca9-bd7f-20aa3f1937ca",
            "one/server", "name spaces", "dots.and_underscores--",
            "../unsafe", "é", "日本語", "\\path\\name", "star*pipe|colon:here"
        )
        for (id in profiles) {
            val legacy = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            assertEquals(id, legacy, AudioCacheNamePolicy.safeProfile(id))
        }
    }

    @Test
    fun audioCacheExtensionsPreserveOldFilenameAndMimeFallbacks() {
        val names = listOf(
            null, "", "call.mp3", "call.MP3", "call.ogg", "call.m4a",
            "call.", "foo.name-too-long", "foo.é", "foo.mp3?", "noextension",
            "call...MP3", "call.0", "call.abcdefgh", "call.abcdefghi"
        )
        val types = listOf(null, "audio/mpeg", "AUDIO/MPEG", "audio/mp4",
            "audio/aac", "audio/wav", "audio/ogg", "audio/unknown")
        for (name in names) {
            for (mime in types) {
                val legacy = name?.substringAfterLast('.', "")
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                    ?: when (mime?.lowercase()) {
                        "audio/mpeg", "audio/mp3" -> "mp3"
                        "audio/mp4", "audio/m4a" -> "m4a"
                        "audio/aac" -> "aac"
                        "audio/wav", "audio/x-wav" -> "wav"
                        "audio/ogg" -> "ogg"
                        else -> "bin"
                    }
                assertEquals("name=$name, mime=$mime", legacy, AudioCacheNamePolicy.extension(name, mime))
            }
        }
    }

    @Test
    fun incrementalHistoryInsertPreservesChronologyBothDirections() {
        val base = listOf(
            historyCall(1, "2026-10-09T10:00:00Z"),
            historyCall(2, "2026-10-09T10:10:00Z"),
            historyCall(3, "2026-10-09T10:20:00Z")
        )
        val cases = listOf(
            historyCall(4, "2026-10-09T09:50:00Z"), // older
            historyCall(4, "2026-10-09T10:15:00Z"), // between
            historyCall(4, "2026-10-09T10:25:00Z"), // newer
            historyCall(4, "2026-10-09T10:10:00Z"), // stable equal timestamp
            historyCall(4, ""),                     // invalid date = epoch 0
            historyCall(2, "2026-10-09T10:40:00Z", "Updated"),
            historyCall(2, "2026-10-09T09:00:00Z", "Updated")
        )
        for (newestFirst in listOf(true, false)) {
            val sorted = if (newestFirst) base.asReversed() else base
            for (incoming in cases) {
                assertEquals(
                    "sort=$newestFirst, id=${incoming.id}, time=${incoming.dateTime}",
                    oldHistoryInsertion(sorted, incoming, newestFirst),
                    LiveHistoryMergePolicy.insert(sorted, incoming, newestFirst)
                )
            }
        }
    }

    @Test
    fun incrementalHistoryDuplicateKeepsStablePositionAmongEqualTimes() {
        val sameTime = "2026-10-09T10:10:00Z"
        for (newestFirst in listOf(true, false)) {
            val existing = (1L..4L).map { historyCall(it, sameTime) }
            val updated = historyCall(2, sameTime, "Updated transcript")
            val result = LiveHistoryMergePolicy.insert(existing, updated, newestFirst)
            assertEquals(listOf(1L, 2L, 3L, 4L), result.map { it.id })
            assertEquals("Updated transcript", result[1].talkgroupLabel)
            assertEquals(oldHistoryInsertion(existing, updated, newestFirst), result)
            // Identical duplicate should not trigger an archive recomputation.
            assertSame(existing, LiveHistoryMergePolicy.insert(existing, existing[1], newestFirst))
        }
    }

    @Test
    fun incrementalHistoryMatchesStableSortForLargePagedArchiveAndUpdates() {
        val origin = (1..180).map { i ->
            historyCall(
                i.toLong(),
                if (i % 19 == 0) "not-an-instant"
                else "2026-10-09T10:${(i % 13).toString().padStart(2, '0')}:00Z"
            )
        }
        val sortKey: (RadioCall) -> Long = {
            runCatching { Instant.parse(it.dateTime).toEpochMilli() }.getOrDefault(0L)
        }
        for (newestFirst in listOf(true, false)) {
            // Multiple same-time records and invalid times stress stable sorting.
            var history = if (newestFirst) origin.sortedByDescending(sortKey) else origin.sortedBy(sortKey)
            for (i in 0..230) {
                val incoming = historyCall(
                    if (i % 3 == 0) (i % 180 + 1).toLong() else (181 + i).toLong(),
                    if (i % 17 == 0) ""
                    else "2026-10-09T10:${(i % 13).toString().padStart(2, '0')}:00Z",
                    "Replacement $i"
                )
                val expected = oldHistoryInsertion(history, incoming, newestFirst)
                history = LiveHistoryMergePolicy.insert(history, incoming, newestFirst)
                assertEquals("step $i, newestFirst=$newestFirst", expected, history)
                assertEquals(history.map { it.id }.distinct().size, history.size)
            }
        }
    }

    @Test
    fun binaryHistoryInsertionScalesLogarithmicallyWithLoadedArchiveSize() {
        // Stable timestamp ties are intentionally common on busy scanner feeds.
        // Count real timestamp-key comparisons, not elapsed CI wall time.
        val baseMillis = Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        val ascending = (0 until 4_096).map { index ->
            historyCall(index.toLong() + 1L, Instant.ofEpochMilli(baseMillis + (index / 4) * 1_000L).toString())
        }
        for (newestFirst in listOf(true, false)) {
            val history = if (newestFirst) ascending.asReversed() else ascending
            for (updatedIndex in listOf(-1, 0, 1, 1_024, 2_047, 4_094, 4_095)) {
                for (seconds in listOf(-10L, 0L, 64L, 512L, 1_024L, 1_050L)) {
                    val incoming = historyCall(
                        if (updatedIndex >= 0) history[updatedIndex].id else 9_999L,
                        Instant.ofEpochMilli(baseMillis + seconds * 1_000L).toString(),
                        "Changed"
                    )
                    var timestampsExamined = 0
                    val position = LiveHistoryMergePolicy.insertionIndex(
                        history = history,
                        newTime = Instant.parse(incoming.dateTime).toEpochMilli(),
                        originalIndex = updatedIndex,
                        newestFirst = newestFirst,
                        timestampOf = { call ->
                            timestampsExamined++
                            Instant.parse(call.dateTime).toEpochMilli()
                        }
                    )
                    assertTrue("O(log n) comparisons required: $timestampsExamined", timestampsExamined <= 13)
                    val actual = LiveHistoryMergePolicy.insert(history, incoming, newestFirst)
                    val reference = oldHistoryInsertion(history, incoming, newestFirst)
                    assertEquals("sort=$newestFirst, updated=$updatedIndex, sec=$seconds", reference, actual)
                    assertEquals(position, actual.indexOfFirst { it.id == incoming.id })
                }
            }
        }
    }

    @Test
    fun binaryHistoryInsertionKeepsEqualTimestampOrderWhenReplacingAnyPosition() {
        val timestamp = "2026-10-09T10:10:00Z"
        for (newestFirst in listOf(true, false)) {
            val existing = (1L..50L).map { historyCall(it, timestamp) }
            for (index in existing.indices) {
                val incoming = existing[index].copy(talkgroupLabel = "Updated")
                val actual = LiveHistoryMergePolicy.insert(existing, incoming, newestFirst)
                assertEquals(existing.map { it.id }, actual.map { it.id })
                assertEquals("Updated", actual[index].talkgroupLabel)
            }
            val appended = historyCall(99L, timestamp)
            val result = LiveHistoryMergePolicy.insert(existing, appended, newestFirst)
            assertEquals(99L, result.last().id)
            assertEquals(emptyList<RadioCall>(),
                LiveHistoryMergePolicy.insert(emptyList(), appended, newestFirst).drop(1))
        }
    }

    @Test
    fun unchangedHistoryAndAlertsAreReusedAcrossStatusAndTranscriptUpdates() {
        val profile = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val call = RadioCall(profileId = "one", serverName = "One", id = 1,
            systemRef = 1, talkgroupRef = 101, systemLabel = "System",
            talkgroupLabel = "Dispatch", dateTime = "2026-10-09T10:00:00Z")
        val history = listOf(call)
        val alerts = listOf(ScannerAlert("one", "One", "Alert", "Body"))
        val initial = ServerScannerState(profile = profile, history = history, alerts = alerts)
        val previous = ScannerState(servers = mapOf("one" to initial), history = history, alerts = alerts)
        val later = initial.copy(statusText = "Connected", transcriptsLoading = true)
        assertTrue(ScannerStateAggregationPolicy.reuseHistory(previous, mapOf("one" to later)))
        assertTrue(ScannerStateAggregationPolicy.reuseAlerts(previous, mapOf("one" to later)))
    }

    @Test
    fun identicalPublishedServerReferencesNeedNoNewStateEmission() {
        val one = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val two = ServerProfile(id = "two", name = "Two", baseUrl = "https://scanner.invalid")
        val serverOne = ServerScannerState(profile = one, statusText = "Connected")
        val serverTwo = ServerScannerState(profile = two, statusText = "Connected")
        val previous = ScannerState(servers = linkedMapOf("one" to serverOne, "two" to serverTwo))

        // Fresh map instances and different insertion order still represent
        // the same published snapshot, provided the per-server objects match.
        assertFalse(ScannerStateAggregationPolicy.hasChanges(previous,
            linkedMapOf("two" to serverTwo, "one" to serverOne)))
        assertFalse(ScannerStateAggregationPolicy.hasChanges(previous, previous.servers))
    }

    @Test
    fun anyChangedServerOrProfileMembershipForcesPublication() {
        val one = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val two = ServerProfile(id = "two", name = "Two", baseUrl = "https://scanner.invalid")
        val serverOne = ServerScannerState(profile = one, statusText = "Connected")
        val serverTwo = ServerScannerState(profile = two, statusText = "Connected")
        val previous = ScannerState(servers = mapOf("one" to serverOne, "two" to serverTwo))

        assertTrue(ScannerStateAggregationPolicy.hasChanges(previous,
            mapOf("one" to serverOne.copy(statusText = "Reconnecting"), "two" to serverTwo)))
        assertTrue(ScannerStateAggregationPolicy.hasChanges(previous,
            mapOf("one" to serverOne, "two" to serverTwo.copy(listenerCount = 5))))
        assertTrue(ScannerStateAggregationPolicy.hasChanges(previous, mapOf("one" to serverOne)))
        assertTrue(ScannerStateAggregationPolicy.hasChanges(previous,
            mapOf("one" to serverOne, "two" to serverTwo, "three" to serverTwo)))
        assertTrue(ScannerStateAggregationPolicy.hasChanges(previous,
            mapOf("one" to serverOne, "three" to serverTwo)))

        // Even an equivalent replacement must proceed through the existing
        // StateFlow equality behavior, rather than being dropped by identity.
        assertTrue(ScannerStateAggregationPolicy.hasChanges(previous,
            mapOf("one" to serverOne.copy(), "two" to serverTwo)))
        assertFalse(ScannerStateAggregationPolicy.hasChanges(
            ScannerState(), emptyMap()))
    }

    @Test
    fun singleServerNewestFirstAggregateReusesHistoryListWithoutSorting() {
        val profile = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val calls = (1..100).map { index ->
            historyCall(
                index.toLong(),
                Instant.ofEpochMilli((1_000 - index).toLong() * 1_000L).toString()
            )
        }
        val server = ServerScannerState(profile = profile, history = calls, historySort = -1)
        val result = ScannerStateAggregationPolicy.aggregateHistory(mapOf(profile.id to server))
        assertSame(calls, result)
        assertEquals(calls, result)
    }

    @Test
    fun singleServerLargeArchiveKeepsNewest500AndDoesNotMutateOriginal() {
        val profile = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val calls = (0..1_000).map { index ->
            historyCall(
                index.toLong(),
                Instant.ofEpochMilli((1_000 - index).toLong() * 1_000L).toString()
            )
        }
        val server = ServerScannerState(profile = profile, history = calls, historySort = -1)
        val result = ScannerStateAggregationPolicy.aggregateHistory(mapOf("one" to server))
        assertEquals(calls.take(500), result)
        assertEquals(1_001, calls.size)
    }

    @Test
    fun ascendingAndMultiServerArchivesRetainGlobalDescendingSortAndStableTies() {
        val one = ServerProfile(id = "one", name = "One", baseUrl = "https://one.invalid")
        val two = ServerProfile(id = "two", name = "Two", baseUrl = "https://two.invalid")
        val a = listOf(
            historyCall(1, "2026-10-09T10:00:00Z"),
            historyCall(2, "2026-10-09T10:01:00Z"),
            historyCall(3, "not-an-instant")
        )
        val b = listOf(
            historyCall(4, "2026-10-09T10:01:00Z"),
            historyCall(5, "2026-10-09T10:05:00Z")
        )
        val scenarios = listOf(
            mapOf("one" to ServerScannerState(profile = one, history = a, historySort = 1)),
            linkedMapOf(
                "one" to ServerScannerState(profile = one, history = a, historySort = 1),
                "two" to ServerScannerState(profile = two, history = b, historySort = -1)
            ),
            linkedMapOf(
                "one" to ServerScannerState(profile = one, history = a.asReversed(), historySort = -1),
                "two" to ServerScannerState(profile = two, history = b.asReversed(), historySort = -1)
            )
        )
        for (servers in scenarios) {
            val expected = servers.values.flatMap { it.history }
                .sortedByDescending {
                    runCatching { Instant.parse(it.dateTime).toEpochMilli() }.getOrDefault(0L)
                }.take(500)
            assertEquals(expected, ScannerStateAggregationPolicy.aggregateHistory(servers))
        }
        assertEquals(emptyList<RadioCall>(), ScannerStateAggregationPolicy.aggregateHistory(emptyMap()))
    }

    @Test
    fun changedArchiveOrAlertsInvalidateOnlyTheirOwnAggregatedCache() {
        val profile = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val call = RadioCall(profileId = "one", serverName = "One", id = 1,
            systemRef = 1, talkgroupRef = 101, systemLabel = "System",
            talkgroupLabel = "Dispatch", dateTime = "2026-10-09T10:00:00Z")
        val original = ServerScannerState(
            profile = profile, history = listOf(call),
            alerts = listOf(ScannerAlert("one", "One", "Alert", "Body"))
        )
        val old = ScannerState(servers = mapOf("one" to original),
            history = original.history, alerts = original.alerts)
        val newHistory = original.copy(history = listOf(call.copy(id = 2)))
        assertFalse(ScannerStateAggregationPolicy.reuseHistory(old, mapOf("one" to newHistory)))
        assertTrue(ScannerStateAggregationPolicy.reuseAlerts(old, mapOf("one" to newHistory)))

        val newAlerts = original.copy(alerts = listOf(ScannerAlert("one", "One", "New", "Body")))
        assertTrue(ScannerStateAggregationPolicy.reuseHistory(old, mapOf("one" to newAlerts)))
        assertFalse(ScannerStateAggregationPolicy.reuseAlerts(old, mapOf("one" to newAlerts)))
        assertFalse(ScannerStateAggregationPolicy.reuseHistory(old, emptyMap()))
        assertFalse(ScannerStateAggregationPolicy.reuseAlerts(old, emptyMap()))
    }

    @Test
    fun passiveStatusEventsSkipIdenticalValuesWithoutReallocatingState() {
        val profile = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val original = ServerScannerState(profile = profile, listenerCount = 17, serverVersion = "1.2.3")
        assertEquals(null, ScannerPassiveEventPolicy.versionUpdate(original, "1.2.3"))
        assertEquals(null, ScannerPassiveEventPolicy.listenerCountUpdate(original, 17))
        val newVersion = ScannerPassiveEventPolicy.versionUpdate(original, "2.0")
        assertEquals("2.0", newVersion?.serverVersion)
        assertEquals(17, newVersion?.listenerCount)
        val newCount = ScannerPassiveEventPolicy.listenerCountUpdate(original, 18)
        assertEquals(18, newCount?.listenerCount)
        assertEquals("1.2.3", newCount?.serverVersion)
        assertEquals(null, ScannerPassiveEventPolicy.versionUpdate(original.copy(serverVersion = null), null))
        assertEquals(null, ScannerPassiveEventPolicy.versionUpdate(original.copy(serverVersion = null), null))
    }

    @Test
    fun incomingCallHistoryFilterMatchesIndependentLegacyPolicy() {
        val sampleSystems = listOf(
            SystemConfig(
                systemRef = 1, label = "Dispatch",
                talkgroups = listOf(
                    TalkgroupConfig(1, 11, "Police", tag = "LAW", groups = listOf("North", "Primary")),
                    TalkgroupConfig(1, 12, "Fire", tag = "FIRE", groups = listOf("South"))
                )
            ),
            SystemConfig(
                systemRef = 2, label = "Other",
                talkgroups = listOf(TalkgroupConfig(2, 21, "EMS", tag = "EMS", groups = emptyList()))
            )
        )
        val profile = ServerProfile(id = "one", name = "One", baseUrl = "https://scanner.invalid")
        val validTime = "2026-10-09T10:00:00Z"
        val timestamps = listOf(validTime, "2026-10-09T09:00:00Z", "invalid")
        val calls = listOf(
            historyCall(1, validTime).copy(systemRef = 1, talkgroupRef = 11),
            historyCall(2, validTime).copy(systemRef = 1, talkgroupRef = 12),
            historyCall(3, validTime).copy(systemRef = 2, talkgroupRef = 21),
            historyCall(4, validTime).copy(systemRef = 3, talkgroupRef = 99)
        ).flatMap { call -> timestamps.map { call.copy(dateTime = it) } }
        // Deliberately independent representation of the previous implementation.
        fun oldPolicy(state: ServerScannerState, call: RadioCall): Boolean {
            val systemMatches = state.historySystemRef?.let { call.systemRef == it } ?: true
            val talkgroupMatches = (state.historyTalkgroupRef?.let { call.talkgroupRef == it } ?: true) &&
                (state.historyTalkgroupRefs.isEmpty() || call.talkgroupRef in state.historyTalkgroupRefs)
            val talkgroup = state.systems.firstOrNull { it.systemRef == call.systemRef }
                ?.talkgroups?.firstOrNull { it.talkgroupRef == call.talkgroupRef }
            val groupMatches = state.historyGroup?.let { selected ->
                talkgroup?.groups?.any { it.equals(selected, ignoreCase = true) } == true
            } ?: true
            val tagMatches = state.historyTag?.let { selected ->
                talkgroup?.tag?.equals(selected, ignoreCase = true) == true
            } ?: true
            val dateMatches = state.historyDate?.let { selected ->
                val cutoff = runCatching { Instant.parse(selected).toEpochMilli() }.getOrNull()
                val callTime = runCatching { Instant.parse(call.dateTime).toEpochMilli() }.getOrNull()
                cutoff != null && callTime != null && callTime >= cutoff
            } ?: true
            return systemMatches && talkgroupMatches && groupMatches && tagMatches && dateMatches
        }
        var assertions = 0
        for (systemRef in listOf<Long?>(null, 1, 2, 3)) {
            for (talkgroupRef in listOf<Long?>(null, 11, 12, 21, 99)) {
                for (group in listOf<String?>(null, "north", "SOUTH", "", "bogus")) {
                    for (tag in listOf<String?>(null, "law", "FIRE", "EMS", "")) {
                        for (date in listOf<String?>(null, validTime, "invalid")) {
                            val state = ServerScannerState(
                                profile = profile, systems = sampleSystems,
                                historySystemRef = systemRef, historyTalkgroupRef = talkgroupRef,
                                historyTalkgroupRefs = if (talkgroupRef == 11L) listOf(11, 12) else emptyList(),
                                historyGroup = group, historyTag = tag, historyDate = date
                            )
                            for (call in calls) {
                                assertEquals(
                                    "system=$systemRef tg=$talkgroupRef group=$group tag=$tag date=$date call=${call.id}/${call.dateTime}",
                                    oldPolicy(state, call),
                                    ScannerRepository.matchesHistoryFilter(state, call)
                                )
                                assertions++
                            }
                        }
                    }
                }
            }
        }
        assertTrue(assertions >= 10_000)
    }

    @Test
    fun singleSourceDisplaySkipsListAllocationsWithoutChangingFallbacks() {
        val original = historyCall(1L, "2026-10-09T10:00:00Z").copy(
            sourceRef = 42L, sourceLabel = "Console"
        )
        val cases = listOf(
            original,
            original.copy(sources = listOf(CallSource(sourceRef = 42, display = "Engine 2"))),
            original.copy(sources = listOf(CallSource(sourceRef = 42, display = ""))),
            original.copy(sources = listOf(CallSource(sourceRef = 42, display = "   "))),
            original.copy(sources = listOf(CallSource(sourceRef = 42, display = "Medic 3"), CallSource(sourceRef = 43, display = "Medic 3"))),
            original.copy(sources = listOf(CallSource(sourceRef = 42, display = "Medic 3"), CallSource(sourceRef = 43, display = "Engine 2"))),
            original.copy(sourceLabel = null, sourceRef = 99L),
            original.copy(sourceLabel = null, sourceRef = null)
        )
        for (call in cases) {
            val oldDisplay = call.sources.mapNotNull { it.display?.takeIf(String::isNotBlank) }
                .distinct().joinToString(", ").takeIf { it.isNotBlank() }
                ?: call.sourceLabel
                ?: call.sourceRef?.toString()
            assertEquals(oldDisplay, call.sourceDisplay)
        }
    }

    @Test
    fun explicitSourceTagsOverrideConfiguredUnitAliases() {
        val configured = listOf(
            SystemConfig(1, "System", emptyList(), units = listOf(
                UnitAlias(label = "Configured alias", unitRef = 420)
            ))
        )
        val cases = mapOf(
            """{"sources":[{"pos":0,"src":420,"tag":"Portable 5"}]}""" to "Portable 5 | 420",
            """{"sources":[{"pos":0,"src":420,"tag":"420"}]}""" to "Configured alias | 420",
            """{"sources":[{"pos":0,"src":420,"tag":"  "}]}""" to "Configured alias | 420"
        )
        for ((raw, expected) in cases) {
            val result = ScannerRepository.resolveCallSources(configured, 1, JSONObject(raw))
            assertEquals(expected, result.single().display)
        }
    }

    @Test
    fun hotPathChannelLookupAvoidsFlatteningWithoutChangingRouting() {
        val scannerSystems = listOf(
            SystemConfig(1, "First", listOf(
                TalkgroupConfig(1, 100, "Law Dispatch", enabled = true),
                TalkgroupConfig(1, 101, "Fire", enabled = false)
            )),
            SystemConfig(2, "Second", listOf(TalkgroupConfig(2, 100, "EMS", enabled = true))),
            SystemConfig(1, "Duplicate system ref", listOf(TalkgroupConfig(1, 102, "Mutual Aid", enabled = true)))
        )
        assertTrue(ScannerCallRoutingPolicy.channelEnabled(scannerSystems, ChannelKey(1, 100)))
        assertFalse(ScannerCallRoutingPolicy.channelEnabled(scannerSystems, ChannelKey(1, 101)))
        assertTrue(ScannerCallRoutingPolicy.channelEnabled(scannerSystems, ChannelKey(2, 100)))
        assertTrue(ScannerCallRoutingPolicy.channelEnabled(scannerSystems, ChannelKey(1, 102)))
        assertFalse(ScannerCallRoutingPolicy.channelEnabled(scannerSystems, ChannelKey(2, 101)))
        assertFalse(ScannerCallRoutingPolicy.channelEnabled(emptyList(), ChannelKey(1, 100)))
    }

    @Test
    fun duplicateTalkgroupKeyKeepsFirstMatchingEntrySemantics() {
        val scannerSystems = listOf(
            SystemConfig(1, "A", listOf(TalkgroupConfig(1, 42, "Disabled first", enabled = false))),
            SystemConfig(1, "B", listOf(TalkgroupConfig(1, 42, "Enabled duplicate", enabled = true)))
        )
        assertFalse(ScannerCallRoutingPolicy.channelEnabled(scannerSystems, ChannelKey(1, 42)))
    }

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
    fun recentlyPlayedIsBoundedDeduplicatedAndNewestCompletedFirst() {
        val source = (1L..12L).map { id ->
            RadioCall(profileId = profile.id, serverName = profile.name, id = id,
                systemRef = 1, talkgroupRef = 11, systemLabel = "Law",
                talkgroupLabel = "Dispatch $id", dateTime = "")
        }
        var recent = emptyList<RadioCall>()
        for (call in source) {
            recent = RecentlyPlayedCallsPolicy.complete(recent, call)
        }
        assertEquals(10, recent.size)
        assertEquals(listOf(12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L), recent.map { it.id })

        recent = RecentlyPlayedCallsPolicy.complete(recent, source[4].copy(talkgroupLabel = "Updated"))
        assertEquals(10, recent.size)
        assertEquals(5L, recent.first().id)
        assertEquals("Updated", recent.first().talkgroupLabel)
        assertEquals(1, recent.count { it.id == 5L })
        assertEquals(10, recent.map { it.id }.distinct().size)
    }

    @Test
    fun invalidCallIdsCannotEnterRecent() {
        val known = RadioCall(profileId = profile.id, serverName = profile.name, id = 42,
            systemRef = 1, talkgroupRef = 11, systemLabel = "Law",
            talkgroupLabel = "Call", dateTime = "")
        val original = listOf(known)
        assertTrue(original === RecentlyPlayedCallsPolicy.complete(original, known.copy(id = 0)))
        assertTrue(original === RecentlyPlayedCallsPolicy.complete(original, known.copy(id = -1)))
    }

    @Test
    fun replayLastChoosesMostRecentlyCompletedLiveCall() {
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
    fun replayLastNeverTargetsReceivedButUnplayedCalls() {
        val call = RadioCall(
            profileId = profile.id, serverName = profile.name, id = 99L,
            systemRef = 1, talkgroupRef = 11, systemLabel = "One",
            talkgroupLabel = "One-A", dateTime = ""
        )
        assertEquals(null, ScannerRepository.lastReplayCandidate(ServerScannerState(profile = profile, lastCall = call)))
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
    fun emptyAndSingleSourcePayloadsMatchLegacyParsingAndFallbacks() {
        // Independent expected values from the previous general-purpose
        // sorted/deduplicated parser, with no unit aliases configured.
        fun src(ref: Long, pos: Int = 0, tag: String? = null): List<CallSource> =
            listOf(CallSource(
                position = pos, sourceRef = ref, tag = tag,
                display = if (tag != null && tag != ref.toString()) "$tag | $ref" else ref.toString()
            ))
        fun tag(value: String, pos: Int = 0): List<CallSource> =
            listOf(CallSource(position = pos, tag = value, display = value))
        val cases = listOf(
            """{}""" to emptyList(),
            """{"sources":[]}""" to emptyList(),
            """{"sources":[null]}""" to emptyList(),
            """{"sources":[3]}""" to emptyList(),
            """{"source":0}""" to emptyList(),
            """{"source":21}""" to src(21L),
            """{"source":21,"sources":[]}""" to src(21L),
            """{"source":21,"sources":[null]}""" to src(21L),
            """{"source":21,"sources":[{}]}""" to src(21L),
            """{"source":21,"sources":[{"src":0}]}""" to src(21L),
            """{"source":21,"sources":[{"src":42}]}""" to src(42L),
            """{"source":21,"sources":[{"pos":8,"src":42,"tag":"Truck"}]}""" to src(42L, 8, "Truck"),
            """{"sources":[{"src":42,"tag":"42"}]}""" to src(42L, 0, "42"),
            """{"sources":[{"src":42,"tag":"  "}]}""" to src(42L),
            """{"sources":[{"pos":6,"src":-10,"tag":"Medic"}]}""" to tag("Medic", 6),
            """{"sources":[{"tag":"  Medic  "}]}""" to tag("Medic"),
            """{"source":33,"sources":[{"src":0,"tag":"Medic"}]}""" to tag("Medic"),
            """{"sources":[{"src":33,"tag":"33"}]}""" to src(33L, 0, "33")
        )
        for ((json, expected) in cases) {
            assertEquals(json, expected, ScannerRepository.resolveCallSources(
                emptyList(), 1, JSONObject(json)
            ))
        }
    }

    @Test
    fun singleSourceFastPathStillRespectsConfiguredAliasesAndTags() {
        val configured = listOf(SystemConfig(
            systemRef = 1, label = "Law", talkgroups = emptyList(),
            units = listOf(UnitAlias(label = "Truck 5", unitRef = 420))
        ))
        val examples = listOf(
            """{"sources":[{"pos":3,"src":420}]}""" to "Truck 5 | 420",
            """{"sources":[{"src":420,"tag":"Portable 7"}]}""" to "Portable 7 | 420",
            """{"sources":[{"src":420,"tag":"420"}]}""" to "Truck 5 | 420",
            """{"source":420,"sources":[]}""" to "Truck 5 | 420",
            """{"source":420,"sources":[{"tag":"only tag"}]}""" to "only tag"
        )
        for ((json, expected) in examples) {
            assertEquals(json, expected, ScannerRepository.resolveCallSources(
                configured, 1, JSONObject(json)
            ).single().display)
        }
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
