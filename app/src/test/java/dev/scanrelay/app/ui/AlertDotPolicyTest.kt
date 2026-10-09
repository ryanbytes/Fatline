package dev.scanrelay.app.ui

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

    @Test fun repaintAndReconnectDoNotMarkUnseenAlertsRead() {
        val baseline = mapOf("one" to setOf("old"))
        val current = mapOf("one" to setOf("old", "new"))
        val unchanged = AlertDotPolicy.markViewedOrBaseline(baseline, current, false)
        assertTrue(AlertDotPolicy.hasNewAlerts(unchanged, current))
        assertFalse(AlertDotPolicy.hasNewAlerts(unchanged, emptyMap()))
    }
}
