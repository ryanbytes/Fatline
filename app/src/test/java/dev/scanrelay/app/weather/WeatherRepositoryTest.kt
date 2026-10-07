package dev.scanrelay.app.weather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class WeatherRepositoryTest {
    @Test fun parsesForecastPeriodsAndSkipsIncompleteEntries() {
        val periods = parseForecastPeriods(JSONObject(
            """{"properties":{"periods":[
                {"name":"Tonight","temperature":48,"temperatureUnit":"F","shortForecast":"Clear","detailedForecast":"Clear skies.","windSpeed":"5 mph","windDirection":"NW","isDaytime":false},
                {"name":"Tomorrow","temperature":70,"temperatureUnit":"F","shortForecast":"Sunny"},
                {"name":"","temperature":12,"shortForecast":"Missing name"},
                {"name":"Broken","shortForecast":"Missing temperature"}
            ]}}"""
        ))

        assertEquals(2, periods.size)
        assertEquals("Tonight", periods.first().name)
        assertEquals("5 mph NW", periods.first().wind)
        assertTrue(!periods.first().isDaytime)
    }

    @Test fun radarUrlCentersOnTheRequestedCoordinates() {
        val url = nwsRadarUrl(latitude = 40.99, longitude = -85.99)
        assertTrue(url.startsWith("https://radar.weather.gov/?settings=v1_"))
        val token = url.substringAfter("settings=v1_")
        val settings = String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
        assertTrue(settings.contains("\"center\":[-85.99,40.99]"))
        assertTrue(settings.contains("\"layer\":\"bref_qcd\""))
        assertTrue(settings.contains("\"alertsOverlay\":true"))
    }

    @Test fun emptyOrMalformedForecastHasNoPeriods() {
        assertTrue(parseForecastPeriods(JSONObject("{}")).isEmpty())
        assertTrue(parseForecastPeriods(JSONObject("""{"properties":{"periods":[]}}""")).isEmpty())
    }
}
