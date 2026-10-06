package dev.scanrelay.app.ui

import dev.scanrelay.app.model.ScannerAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncidentMapUriTest {
    private fun alert(
        address: String? = null,
        lat: Double? = null,
        lon: Double? = null
    ) = ScannerAlert(
        profileId = "p1",
        serverName = "Scanner",
        title = "Alert",
        body = "Body",
        incidentAddress = address,
        incidentLat = lat,
        incidentLon = lon
    )

    @Test
    fun coordinatesArePreferredWhenPresent() {
        assertEquals(
            "geo:40.765432,-85.812345?q=40.765432,-85.812345",
            incidentMapUri(alert(address = "123 Main St", lat = 40.7654321, lon = -85.8123454))
        )
    }

    @Test
    fun addressFallsBackToEncodedGeoQuery() {
        assertEquals(
            "geo:0,0?q=123%20Main%20St%2C%20Wabash%2C%20IN",
            incidentMapUri(alert(address = "123 Main St, Wabash, IN"))
        )
    }

    @Test
    fun missingLocationReturnsNull() {
        assertNull(incidentMapUri(alert()))
    }

    @Test
    fun serverMappingSwitchHidesMapAction() {
        assertNull(
            incidentMapUri(
                alert(address = "123 Main St", lat = 40.7654321, lon = -85.8123454),
                mappingEnabled = false
            )
        )
    }

}
