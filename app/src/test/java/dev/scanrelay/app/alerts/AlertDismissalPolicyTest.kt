package dev.scanrelay.app.alerts

import dev.scanrelay.app.model.ScannerAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDismissalPolicyTest {
    private fun alert(
        id: Long? = 1,
        profileId: String = "scanner-a",
        title: String = "Dispatch",
        body: String = "Incident"
    ) = ScannerAlert(
        profileId = profileId, serverName = "Scanner", title = title,
        body = body, dateTime = "2026-10-09T18:00:00Z", alertId = id, callId = 123
    )

    @Test
    fun deletedAlertStaysHiddenAfterHistoryIsReloaded() {
        val removed = alert()
        val keep = alert(id = 2)
        val dismissed = AlertDismissalPolicy.retain(emptySet(), AlertDismissalPolicy.key(removed))
        assertEquals(listOf(keep), AlertDismissalPolicy.visible(listOf(removed, keep), dismissed))
        assertEquals(listOf(keep), AlertDismissalPolicy.visible(listOf(removed.copy(body = "Updated"), keep), dismissed))
    }

    @Test
    fun deletionDoesNotHideOtherAlertsOrAnotherScanner() {
        val removed = alert(id = 7)
        val otherProfile = alert(id = 7, profileId = "scanner-b")
        val differentAlert = alert(id = 8)
        val keys = setOf(AlertDismissalPolicy.key(removed))
        assertNotEquals(AlertDismissalPolicy.key(removed), AlertDismissalPolicy.key(otherProfile))
        assertEquals(listOf(otherProfile, differentAlert),
            AlertDismissalPolicy.visible(listOf(removed, otherProfile, differentAlert), keys))
    }

    @Test
    fun alertsWithoutServerIdsUseContentAndDateAndCanBeDismissed() {
        val first = alert(id = null, title = "Local keyword", body = "Original transcript")
        val second = alert(id = null, title = "Local keyword", body = "Another transcript")
        val keys = setOf(AlertDismissalPolicy.key(first))
        assertNotEquals(AlertDismissalPolicy.key(first), AlertDismissalPolicy.key(second))
        assertEquals(listOf(second), AlertDismissalPolicy.visible(listOf(first, second), keys))
    }

    @Test
    fun tombstonesAreBoundedAndRedismissedAlertsMoveToNewest() {
        val keys = (1..AlertDismissalPolicy.LIMIT).map(Int::toString)
        val capped = AlertDismissalPolicy.retain(keys, "new")
        assertEquals(AlertDismissalPolicy.LIMIT, capped.size)
        assertFalse("1" in capped)
        assertTrue("new" in capped)
        val repeated = AlertDismissalPolicy.retain(capped, "2")
        assertEquals(AlertDismissalPolicy.LIMIT, repeated.size)
        assertEquals("2", repeated.last())
    }
}
