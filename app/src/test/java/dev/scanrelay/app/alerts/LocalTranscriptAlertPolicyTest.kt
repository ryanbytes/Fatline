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
