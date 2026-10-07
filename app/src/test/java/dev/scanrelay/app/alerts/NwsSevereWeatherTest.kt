package dev.scanrelay.app.alerts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwsSevereWeatherTest {
    @Test fun keepsOnlyUniqueSevereAndExtremeAlerts() {
        val alerts = parseNwsSevereAlerts(JSONObject(
            """{"features":[
                {"id":"watch","properties":{"severity":"Moderate","event":"Watch"}},
                {"id":"severe","properties":{"severity":"Severe","event":"Tornado Warning","headline":"Take cover","areaDesc":"County"}},
                {"id":"extreme","properties":{"severity":"Extreme","event":"Flash Flood Warning"}},
                {"id":"severe","properties":{"severity":"Severe","event":"Duplicate"}},
                {"id":"minor","properties":{"severity":"Minor","event":"Minor"}}
            ]}"""
        ))

        assertEquals(listOf("severe", "extreme"), alerts.map { it.id })
        assertEquals("Take cover", alerts.first().headline)
    }

    @Test fun suppressesExistingAlertsAndInitialBaseline() {
        val current = listOf(
            NwsSevereAlert("old", "Warning", "", "", "Severe"),
            NwsSevereAlert("new", "Warning", "", "", "Extreme")
        )

        assertTrue(newlyActiveNwsAlerts(null, current).isEmpty())
        assertEquals(listOf("new"), newlyActiveNwsAlerts(setOf("old"), current).map { it.id })
    }
}
