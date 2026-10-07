package dev.scanrelay.app.weather

import android.content.Context
import dev.scanrelay.app.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class NwsForecastPeriod(
    val name: String,
    val startTime: String,
    val temperature: Int?,
    val temperatureUnit: String,
    val shortForecast: String,
    val probabilityOfPrecipitation: Int?
)

data class NwsWeatherBundle(
    val zipCode: String,
    val locationLabel: String,
    val latitude: Double,
    val longitude: Double,
    val currentTempF: Int?,
    val currentConditions: String,
    val hourly: List<NwsForecastPeriod>,
    val daily: List<NwsForecastPeriod>
)

internal fun isValidUsZip(zip: String): Boolean = ZIP_REGEX.matches(zip.trim())

internal fun parseNwsForecastPeriods(json: JSONObject?): List<NwsForecastPeriod> {
    val periods = json?.optJSONObject("properties")?.optJSONArray("periods") ?: return emptyList()
    return buildList {
        for (index in 0 until periods.length()) {
            val item = periods.optJSONObject(index) ?: continue
            val precipitation = item.optJSONObject("probabilityOfPrecipitation")
                ?.opt("value")
                ?.let { value -> (value as? Number)?.toInt() }
            add(
                NwsForecastPeriod(
                    name = item.optString("name"),
                    startTime = item.optString("startTime"),
                    temperature = item.opt("temperature").let { value -> (value as? Number)?.toInt() },
                    temperatureUnit = item.optString("temperatureUnit", "F"),
                    shortForecast = item.optString("shortForecast"),
                    probabilityOfPrecipitation = precipitation
                )
            )
        }
    }
}

internal fun buildNwsWeatherBundle(
    zipCode: String,
    locationLabel: String,
    latitude: Double,
    longitude: Double,
    hourly: List<NwsForecastPeriod>,
    forecast: List<NwsForecastPeriod>
): NwsWeatherBundle {
    val daily = buildList {
        val dates = mutableSetOf<String>()
        for (period in forecast) {
            val date = period.startTime.take(10)
            if (date.isNotBlank() && dates.add(date)) add(period)
            if (size == 3) break
        }
    }
    val nextHours = hourly.take(5)
    return NwsWeatherBundle(
        zipCode = zipCode,
        locationLabel = locationLabel,
        latitude = latitude,
        longitude = longitude,
        currentTempF = nextHours.firstOrNull()?.temperature,
        currentConditions = nextHours.firstOrNull()?.shortForecast.orEmpty(),
        hourly = nextHours,
        daily = daily
    )
}

class NwsWeatherRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("fatline_weather", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun savedZip(profileId: String): String = prefs.getString("zip:$profileId", "").orEmpty()

    fun saveZip(profileId: String, zip: String) {
        prefs.edit().putString("zip:$profileId", zip.trim()).apply()
    }

    suspend fun accountZip(profile: ServerProfile): String? = withContext(Dispatchers.IO) {
        runCatching {
            val origin = httpOrigin(profile.baseUrl)
            val encodedPin = URLEncoder.encode(profile.pin.trim(), Charsets.UTF_8.name())
            getJson("$origin/api/account?pin=$encodedPin")
                ?.optString("zipCode")
                ?.trim()
                ?.takeIf(::isValidUsZip)
        }.getOrNull()
    }

    suspend fun load(zip: String): NwsWeatherBundle = withContext(Dispatchers.IO) {
        require(isValidUsZip(zip)) { "Enter a valid US ZIP code." }
        val baseZip = zip.take(5)
        val place = getJson("https://api.zippopotam.us/us/$baseZip")
            ?.optJSONArray("places")
            ?.optJSONObject(0)
            ?: error("Could not locate that ZIP code.")
        val latitude = place.optString("latitude").toDoubleOrNull()
            ?: error("Could not locate that ZIP code.")
        val longitude = place.optString("longitude").toDoubleOrNull()
            ?: error("Could not locate that ZIP code.")
        val city = place.optString("place name").trim()
        val state = place.optString("state abbreviation").trim()
        val location = listOf(city, state).filter(String::isNotBlank).joinToString(", ").ifBlank { zip }

        val points = getJson("https://api.weather.gov/points/$latitude,$longitude", nws = true)
            ?.optJSONObject("properties")
            ?: error("Weather data is temporarily unavailable.")
        val hourlyUrl = points.optString("forecastHourly").takeIf(String::isNotBlank)
            ?: error("Hourly forecast is unavailable for this location.")
        val forecastUrl = points.optString("forecast").takeIf(String::isNotBlank)
            ?: error("Forecast is unavailable for this location.")
        val hourly = getJson(hourlyUrl, nws = true)
            ?: error("Weather data is temporarily unavailable.")
        val forecast = getJson(forecastUrl, nws = true)
            ?: error("Weather data is temporarily unavailable.")
        buildNwsWeatherBundle(
            zipCode = zip,
            locationLabel = location,
            latitude = latitude,
            longitude = longitude,
            hourly = parseNwsForecastPeriods(hourly),
            forecast = parseNwsForecastPeriods(forecast)
        )
    }

    private fun getJson(url: String, nws: Boolean = false): JSONObject? {
        val request = Request.Builder().url(url).get().apply {
            if (nws) {
                header("Accept", "application/geo+json")
                header("User-Agent", "FatLine Android (https://github.com/ryanbytes/Fatline)")
            }
        }.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.let(::JSONObject)
        }
    }

    private fun httpOrigin(baseUrl: String): String {
        val uri = URI(baseUrl.trim())
        val scheme = when (uri.scheme?.lowercase()) {
            "http", "ws" -> "http"
            "https", "wss" -> "https"
            else -> throw IllegalArgumentException("Unsupported scanner URL")
        }
        val authority = uri.rawAuthority?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Scanner URL is invalid")
        return "$scheme://$authority"
    }

    private companion object {
        val ZIP_REGEX = Regex("^\\d{5}(-\\d{4})?$")
    }
}
