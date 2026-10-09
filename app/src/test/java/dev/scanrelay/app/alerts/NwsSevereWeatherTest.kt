package dev.scanrelay.app.alerts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwsSevereWeatherTest {
    @Test fun duplicateWeatherZipsShareAFeedOncePerCycle() {
        val cache = NwsAlertPollCache()
        var requests = 0
        val fetch: () -> List<NwsSevereAlert>? = {
            requests++
            listOf(NwsSevereAlert("warning-1", "Tornado Warning", "", "", "Severe"))
        }
        assertEquals("warning-1", cache.getOrFetch("46992", fetch)?.single()?.id)
        assertEquals("warning-1", cache.getOrFetch("46992-1234", fetch)?.single()?.id)
        assertEquals(1, requests)

        // Each cycle must fetch again, so real new warnings are not delayed.
        val nextCycle = NwsAlertPollCache()
        nextCycle.getOrFetch("46992", fetch)
        assertEquals(2, requests)
    }

    @Test fun failedWeatherFetchesCanRetryAndEmptySuccessIsCached() {
        val cache = NwsAlertPollCache()
        var requests = 0
        val fetch: () -> List<NwsSevereAlert>? = {
            requests++
            if (requests == 1) null else emptyList()
        }
        assertEquals(null, cache.getOrFetch("46992", fetch))
        assertEquals(emptyList<NwsSevereAlert>(), cache.getOrFetch("46992", fetch))
        assertEquals(emptyList<NwsSevereAlert>(), cache.getOrFetch("46992", fetch))
        assertEquals(2, requests)
    }

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
