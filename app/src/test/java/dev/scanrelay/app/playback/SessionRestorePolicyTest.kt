package dev.scanrelay.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRestorePolicyTest {
    @Test
    fun ignoresStaleOrDeletedProfiles() {
        val valid = SessionRestorePolicy.valid(
            linkedSetOf("home", "deleted", "yard"),
            setOf("home", "yard", "not-selected")
        )
        assertEquals(listOf("home", "yard"), valid.toList())
    }

    @Test
    fun resumesOnlyMissingSessionsWithoutReconnectingWorkingSocket() {
        val missing = SessionRestorePolicy.missing(
            linkedSetOf("home", "yard"),
            setOf("home")
        )
        assertEquals(setOf("yard"), missing)
    }

    @Test
    fun explicitDisconnectDoesNotReopenAnyScanner() {
        val valid = SessionRestorePolicy.valid(emptySet(), setOf("home"))
        assertTrue(SessionRestorePolicy.missing(valid, emptySet()).isEmpty())
    }

    @Test
    fun freshProcessRestoresAllPersistedActiveScanners() {
        val valid = SessionRestorePolicy.valid(setOf("home", "yard"), setOf("home", "yard"))
        assertEquals(setOf("home", "yard"), SessionRestorePolicy.missing(valid, emptySet()))
    }
}
