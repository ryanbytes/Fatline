package dev.scanrelay.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptAlertPolicyTest {
    @Test
    fun phrasesMatchRegardlessOfCaseAndSeparator() {
        val rules = LocalTranscriptAlertPolicy.terms("shots fired, working fire\nstructure fire")
        assertEquals(listOf("shots fired", "working fire", "structure fire"), rules)
        assertEquals(listOf("shots fired"), LocalTranscriptAlertPolicy.matches(
            "Units reported SHOTS, FIRED on arrival", rules
        ))
    }

    @Test
    fun wordBoundariesAvoidAccidentalMatches() {
        val rules = listOf("fire", "shots fired", "110")
        assertTrue(LocalTranscriptAlertPolicy.matches(
            "Firefighter responding to 1100 calls", rules
        ).isEmpty())
        assertEquals(listOf("fire"), LocalTranscriptAlertPolicy.matches(
            "Report of fire.", rules
        ))
    }

    @Test
    fun aCallNotMatchingDoesNotAlertAndPhrasesAreDeduplicated() {
        val rules = LocalTranscriptAlertPolicy.terms("Medical call,medical call\nCARDIAC ARREST")
        assertEquals(2, rules.size)
        assertTrue(LocalTranscriptAlertPolicy.matches("Unit en route", rules).isEmpty())
        assertEquals(listOf("CARDIAC ARREST"), LocalTranscriptAlertPolicy.matches(
            "Possible cardiac arrest, request ambulance", rules
        ))
    }

    @Test
    fun preparedRulesRetainPhraseMatchingAndWordBoundaries() {
        val rules = LocalTranscriptAlertPolicy.terms("shots fired, fire, cardiac arrest")
        val prepared = LocalTranscriptAlertPolicy.prepare(rules)
        for (text in listOf(
            "SHOTS, FIRED reported", "Firefighter answering for firefighter",
            "Working structure FIRE", "possible cardiac-arrest", "no matching words"
        )) {
            assertEquals(
                LocalTranscriptAlertPolicy.matches(text, rules),
                LocalTranscriptAlertPolicy.matchesPrepared(text, prepared)
            )
        }
        assertEquals(listOf("shots fired"),
            LocalTranscriptAlertPolicy.matchesPrepared("SHOTS, FIRED reported", prepared))
        assertTrue(LocalTranscriptAlertPolicy.matchesPrepared("firefighter", prepared).isEmpty())
    }

    @Test
    fun cachedRulesReusedAndRepreparedAsSoonAsRuleTextChanges() {
        val cache = TranscriptRuleCache()
        val initial = cache.get("one", "fire, pursuit")
        assertTrue(initial === cache.get("one", "fire, pursuit"))
        assertEquals(listOf("fire"), LocalTranscriptAlertPolicy.matchesPrepared("working fire", initial.prepared))

        val edited = cache.get("one", "shots fired, pursuit")
        assertTrue(edited !== initial)
        assertTrue(LocalTranscriptAlertPolicy.matchesPrepared("working fire", edited.prepared).isEmpty())
        assertEquals(listOf("shots fired"),
            LocalTranscriptAlertPolicy.matchesPrepared("Shots, fired!", edited.prepared))

        // Per-server caches are separate; deleting clears prior data.
        assertTrue(cache.get("two", "fire") !== edited)
        cache.clear("one")
        assertTrue(cache.get("one", "shots fired, pursuit") !== edited)
    }

    @Test
    fun batterySaverHalvesPeriodicNetworkChecks() {
        assertEquals(30_000L, LocalTranscriptAlertPolicy.pollIntervalMs(false))
        assertEquals(60_000L, LocalTranscriptAlertPolicy.pollIntervalMs(true))
    }

    @Test
    fun rulesAreBoundedAndCannotBeEmpty() {
        val rules = LocalTranscriptAlertPolicy.terms((1..90).joinToString(",") { "keyword$it" })
        assertEquals(50, rules.size)
        assertTrue(LocalTranscriptAlertPolicy.matches("", listOf("fire")).isEmpty())
        assertTrue(LocalTranscriptAlertPolicy.matches("fire", listOf("!!!")).isEmpty())
    }
}
