package dev.scanrelay.app.ui

import dev.scanrelay.app.model.ScannerAlert
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDotPolicyTest {
    @Test fun existingAlertsAreNotUnseenWhenAppFirstOpens() {
        val initial = mapOf("server" to setOf("old"))
        val seen = AlertDotPolicy.markViewedOrBaseline(emptyMap(), initial, false)
        assertFalse(AlertDotPolicy.hasNewAlerts(seen, initial))
    }

    @Test fun newAlertsAcrossMultipleServersShowDotUntilAlertsViewed() {
        val initial = mapOf("one" to setOf("old"), "two" to emptySet<String>())
        val baseline = AlertDotPolicy.markViewedOrBaseline(emptyMap(), initial, false)
        val updated = mapOf("one" to setOf("old", "new"), "two" to setOf("other"))
        assertTrue(AlertDotPolicy.hasNewAlerts(baseline, updated))
        val seen = AlertDotPolicy.markViewedOrBaseline(baseline, updated, true)
        assertFalse(AlertDotPolicy.hasNewAlerts(seen, updated))
    }

    private fun alertsServer(id: String, alerts: List<ScannerAlert>): ServerScannerState =
        ServerScannerState(
            profile = ServerProfile(id = id, name = id, baseUrl = "https://scanner.invalid"),
            alerts = alerts
        )

    @Test fun alertKeysAreReusedAcrossUnrelatedScannerStateUpdates() {
        val cache = AlertKeySnapshotCache()
        val alerts = listOf(
            ScannerAlert("p1", "Scanner", "Initial", "body", alertId = 1),
            ScannerAlert("p1", "Scanner", "Second", "body", alertId = 2)
        )
        val original = alertsServer("p1", alerts)
        val current = cache.snapshot(mapOf("p1" to original))
        val updatedStatus = cache.snapshot(mapOf("p1" to original.copy(statusText = "Receiving")))
        assertSame(current, updatedStatus)
        assertSame(current["p1"], updatedStatus["p1"])
        assertFalse(AlertDotPolicy.hasNewAlerts(current, updatedStatus))
    }

    @Test fun changedAlertListsInvalidateOnlyTheirProfile() {
        val cache = AlertKeySnapshotCache()
        val one = alertsServer("one", listOf(ScannerAlert("one", "One", "Old", "body", alertId = 1)))
        val two = alertsServer("two", listOf(ScannerAlert("two", "Two", "Other", "body", alertId = 2)))
        val baseline = cache.snapshot(mapOf("one" to one, "two" to two))
        val newAlert = ScannerAlert("one", "One", "New", "body", alertId = 3)
        val changed = cache.snapshot(mapOf("one" to one.copy(alerts = one.alerts + newAlert), "two" to two))
        assertNotSame(baseline, changed)
        assertSame(baseline["two"], changed["two"])
        assertTrue(AlertDotPolicy.hasNewAlerts(baseline, changed))
        assertEquals(setOf("alert-1", "alert-3"), changed["one"])
    }

    @Test fun disconnectedServersReleaseAlertSnapshotsAndCanReconnectCleanly() {
        val cache = AlertKeySnapshotCache()
        val original = alertsServer("one", listOf(ScannerAlert("one", "One", "Old", "body", alertId = 1)))
        cache.snapshot(mapOf("one" to original))
        assertEquals(emptyMap<String, Set<String>>(), cache.snapshot(emptyMap()))
        val returned = cache.snapshot(mapOf("one" to original))
        assertEquals(setOf("alert-1"), returned["one"])
        assertSame(returned, cache.snapshot(mapOf("one" to original)))
    }

    @Test fun repaintAndReconnectDoNotMarkUnseenAlertsRead() {
        val baseline = mapOf("one" to setOf("old"))
        val current = mapOf("one" to setOf("old", "new"))
        val unchanged = AlertDotPolicy.markViewedOrBaseline(baseline, current, false)
        assertTrue(AlertDotPolicy.hasNewAlerts(unchanged, current))
        assertFalse(AlertDotPolicy.hasNewAlerts(unchanged, emptyMap()))
    }
}
