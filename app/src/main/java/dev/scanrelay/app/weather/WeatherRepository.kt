package dev.scanrelay.app.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ForecastPeriod(
    val name: String,
    val temperature: Int,
    val temperatureUnit: String,
    val shortForecast: String,
    val detailedForecast: String,
    val wind: String,
    val isDaytime: Boolean
)

data class WeatherSnapshot(
    val location: String,
    val periods: List<ForecastPeriod>,
    val severeAlerts: List<String>
)

internal fun parseForecastPeriods(json: JSONObject): List<ForecastPeriod> {
    val periods = json.optJSONObject("properties")?.optJSONArray("periods") ?: return emptyList()
    return buildList {
        for (index in 0 until periods.length()) {
            val period = periods.optJSONObject(index) ?: continue
            val name = period.optString("name").trim()
            val temperature = period.optInt("temperature", Int.MIN_VALUE)
            val short = period.optString("shortForecast").trim()
            if (name.isBlank() || temperature == Int.MIN_VALUE || short.isBlank()) continue
            add(
                ForecastPeriod(
                    name = name,
                    temperature = temperature,
                    temperatureUnit = period.optString("temperatureUnit").trim().ifBlank { "°" },
                    shortForecast = short,
                    detailedForecast = period.optString("detailedForecast").trim(),
                    wind = listOf(period.optString("windSpeed").trim(), period.optString("windDirection").trim())
                        .filter(String::isNotBlank).joinToString(" "),
                    isDaytime = period.optBoolean("isDaytime", true)
                )
            )
        }
    }
}

class WeatherRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    suspend fun forecastForZip(zip: String): WeatherSnapshot = withContext(Dispatchers.IO) {
        val normalized = zip.trim()
        require(Regex("^\\d{5}$").matches(normalized)) { "Enter a five-digit ZIP code." }

        val place = getJson("https://api.zippopotam.us/us/$normalized")
            .optJSONArray("places")?.optJSONObject(0)
            ?: error("That ZIP code could not be found.")
        val latitude = place.optString("latitude").toDoubleOrNull() ?: error("ZIP lookup returned no location.")
        val longitude = place.optString("longitude").toDoubleOrNull() ?: error("ZIP lookup returned no location.")
        val city = place.optString("place name").trim()
        val state = place.optString("state abbreviation").trim()

        val point = getJson("https://api.weather.gov/points/$latitude,$longitude")
        val properties = point.optJSONObject("properties") ?: error("The weather service has no forecast for this ZIP.")
        val forecastUrl = properties.optString("forecast").trim()
        require(forecastUrl.startsWith("https://api.weather.gov/")) { "The weather service returned an invalid forecast URL." }
        val forecast = getJson(forecastUrl)
        val periods = parseForecastPeriods(forecast)
        if (periods.isEmpty()) error("No forecast periods are available right now.")

        val alerts = runCatching {
            val alertJson = getJson("https://api.weather.gov/alerts/active?point=$latitude,$longitude&status=actual")
            val features = alertJson.optJSONArray("features") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until features.length()) {
                    val p = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
                    val severity = p.optString("severity")
                    if (severity.equals("Severe", true) || severity.equals("Extreme", true)) {
                        val event = p.optString("event").trim()
                        val headline = p.optString("headline").trim()
                        add(listOf(event, headline).filter(String::isNotBlank).distinct().joinToString(" — ").ifBlank { "Severe weather warning" })
                    }
                }
            }.distinct()
        }.getOrDefault(emptyList())

        WeatherSnapshot(
            location = listOf(city, state).filter(String::isNotBlank).joinToString(", ").ifBlank { normalized },
            periods = periods,
            severeAlerts = alerts
        )
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "FatLine Android (https://github.com/ryanbytes/Fatline)")
            .header("Accept", "application/geo+json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Weather service returned HTTP ${response.code}.")
            JSONObject(response.body?.string() ?: error("Weather service returned an empty response."))
        }
    }
}
