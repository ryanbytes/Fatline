package dev.scanrelay.app.weather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NwsWeatherRepositoryTest {
    @Test
    fun zipValidationAcceptsUsFiveDigitAndExtendedForms() {
        assertTrue(isValidUsZip("02139"))
        assertTrue(isValidUsZip("02139-1234"))
        assertFalse(isValidUsZip("2139"))
        assertFalse(isValidUsZip("ABCDE"))
        assertFalse(isValidUsZip("12345-"))
    }

    @Test
    fun forecastBundleUsesFiveHourlyPeriodsAndThreeDistinctDays() {
        val hourly = parseNwsForecastPeriods(
            JSONObject(
                """{"properties":{"periods":[
                    {"name":"Today","startTime":"2026-10-07T12:00:00-04:00","temperature":68,"temperatureUnit":"F","shortForecast":"Cloudy","probabilityOfPrecipitation":{"value":20}},
                    {"name":"1 PM","startTime":"2026-10-07T13:00:00-04:00","temperature":69,"temperatureUnit":"F","shortForecast":"Cloudy","probabilityOfPrecipitation":{"value":30}},
                    {"name":"2 PM","startTime":"2026-10-07T14:00:00-04:00","temperature":70,"temperatureUnit":"F","shortForecast":"Rain","probabilityOfPrecipitation":{"value":60}},
                    {"name":"3 PM","startTime":"2026-10-07T15:00:00-04:00","temperature":71,"temperatureUnit":"F","shortForecast":"Rain","probabilityOfPrecipitation":{"value":70}},
                    {"name":"4 PM","startTime":"2026-10-07T16:00:00-04:00","temperature":72,"temperatureUnit":"F","shortForecast":"Rain","probabilityOfPrecipitation":{"value":80}},
                    {"name":"5 PM","startTime":"2026-10-07T17:00:00-04:00","temperature":73,"temperatureUnit":"F","shortForecast":"Rain","probabilityOfPrecipitation":{"value":90}}
                ]}}"""
            )
        )
        val daily = parseNwsForecastPeriods(
            JSONObject(
                """{"properties":{"periods":[
                    {"name":"Today","startTime":"2026-10-07T06:00:00-04:00","temperature":68,"temperatureUnit":"F","shortForecast":"Cloudy"},
                    {"name":"Tonight","startTime":"2026-10-07T18:00:00-04:00","temperature":55,"temperatureUnit":"F","shortForecast":"Clear"},
                    {"name":"Thursday","startTime":"2026-10-08T06:00:00-04:00","temperature":70,"temperatureUnit":"F","shortForecast":"Sunny"},
                    {"name":"Friday","startTime":"2026-10-09T06:00:00-04:00","temperature":71,"temperatureUnit":"F","shortForecast":"Clear"},
                    {"name":"Saturday","startTime":"2026-10-10T06:00:00-04:00","temperature":72,"temperatureUnit":"F","shortForecast":"Sunny"}
                ]}}"""
            )
        )

        val bundle = buildNwsWeatherBundle("02139", "Cambridge, MA", 42.36, -71.1, hourly, daily)

        assertEquals(5, bundle.hourly.size)
        assertEquals(68, bundle.currentTempF)
        assertEquals("Cloudy", bundle.currentConditions)
        assertEquals(3, bundle.daily.size)
        assertEquals(listOf("Today", "Thursday", "Friday"), bundle.daily.map { it.name })
        assertEquals(60, bundle.hourly[2].probabilityOfPrecipitation)
    }
}
