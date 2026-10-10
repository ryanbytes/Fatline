package dev.scanrelay.app.alerts

import android.content.Context
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class NwsSevereAlert(
    val id: String,
    val event: String,
    val headline: String,
    val area: String,
    val severity: String
)

internal fun parseNwsSevereAlerts(json: JSONObject): List<NwsSevereAlert> {
    val features = json.optJSONArray("features") ?: return emptyList()
    return buildList {
        for (index in 0 until features.length()) {
            val properties = features.optJSONObject(index)?.optJSONObject("properties") ?: continue
            val severity = properties.optString("severity").trim()
            if (!severity.equals("severe", true) && !severity.equals("extreme", true)) continue
            val id = properties.optString("id").trim().ifBlank {
                features.optJSONObject(index)?.optString("id").orEmpty().trim()
            }
            if (id.isBlank()) continue
            add(
                NwsSevereAlert(
                    id = id,
                    event = properties.optString("event").trim().ifBlank { "Severe weather alert" },
                    headline = properties.optString("headline").trim(),
                    area = properties.optString("areaDesc").trim(),
                    severity = severity
                )
            )
        }
    }.distinctBy { it.id }
}

internal fun newlyActiveNwsAlerts(
    knownIds: Set<String>?,
    current: List<NwsSevereAlert>
): List<NwsSevereAlert> {
    if (knownIds == null) return emptyList()
    return current.filterNot { it.id in knownIds }
}

/** Persist the weather-warning baseline only when it changes (or on first use). */
internal fun weatherBaselineChanged(knownIds: Set<String>?, activeIds: Set<String>): Boolean =
    knownIds == null || knownIds != activeIds

/**
 * Share successful weather lookups for servers in the same ZIP during each
 * polling pass. Do not cache errors: a later profile can retry immediately.
 * A fresh cache is created every cycle, so new warnings are never held back.
 */
internal class NwsAlertPollCache {
    private val byZip = mutableMapOf<String, List<NwsSevereAlert>>()

    fun getOrFetch(zip: String, fetch: () -> List<NwsSevereAlert>?): List<NwsSevereAlert>? {
        val key = zip.take(5)
        byZip[key]?.let { return it }
        val result = fetch() ?: return null
        byZip[key] = result
        return result
    }
}

/**
 * Polls the same public NWS active-alert feed used by ThinLine's severe-weather ticker.
 * It runs only while the scanner foreground service is alive.
 */
class NwsSevereWeatherMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val profiles = ProfileStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                runCatching { pollProfiles() }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope.cancel()
    }

    private fun pollProfiles() {
        val cycleCache = NwsAlertPollCache()
        profiles.load()
            .filter { it.pin.isNotBlank() && it.baseUrl.isNotBlank() }
            .forEach { profile -> runCatching { pollProfile(profile, cycleCache) } }
    }

    private fun pollProfile(profile: ServerProfile, cycleCache: NwsAlertPollCache) {
        val origin = httpOrigin(profile.baseUrl)
        val account = getJson(
            "$origin/api/account?pin=" + URLEncoder.encode(profile.pin.trim(), Charsets.UTF_8.name())
        ) ?: return
        val zip = account.optString("zipCode").trim()
        if (!Regex(ZIP_PATTERN).matches(zip)) return
        val alerts = cycleCache.getOrFetch(zip) {
            val coords = resolveZip(zip) ?: return@getOrFetch null
            getJson(
                "https://api.weather.gov/alerts/active?point=${coords.first},${coords.second}&status=actual",
                nws = true
            )?.let(::parseNwsSevereAlerts)
        } ?: return

        val key = "known:${zip.take(5)}"
        val knownIds = prefs.getStringSet(key, null)
        if (knownIds == null) {
            alerts.forEach { alert ->
                WeatherAlertNotifier.post(appContext, profile, zip, alert, playSound = false)
            }
        } else {
            newlyActiveNwsAlerts(knownIds, alerts).forEach { alert ->
                WeatherAlertNotifier.post(appContext, profile, zip, alert)
            }
        }
        val activeIds = alerts.mapTo(HashSet(alerts.size)) { it.id }
        if (weatherBaselineChanged(knownIds, activeIds)) {
            prefs.edit().putStringSet(key, activeIds).apply()
        }
    }

    private fun resolveZip(zip: String): Pair<Double, Double>? {
        val key = "coords:${zip.take(5)}"
        val cached = prefs.getString(key, null)?.split(",")
        if (cached?.size == 2) {
            val lat = cached[0].toDoubleOrNull()
            val lon = cached[1].toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        val response = getJson("https://api.zippopotam.us/us/${zip.take(5)}") ?: return null
        val place = response.optJSONArray("places")?.optJSONObject(0) ?: return null
        val lat = place.optString("latitude").toDoubleOrNull() ?: return null
        val lon = place.optString("longitude").toDoubleOrNull() ?: return null
        prefs.edit().putString(key, "$lat,$lon").apply()
        return lat to lon
    }

    private fun getJson(url: String, nws: Boolean = false): JSONObject? {
        val builder = Request.Builder().url(url).get()
        if (nws) {
            builder.header("Accept", "application/geo+json")
            builder.header("User-Agent", "FatLine Android (https://github.com/ryanbytes/Fatline)")
        }
        return client.newCall(builder.build()).execute().use { response ->
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
        const val PREFS = "fatline_weather_alerts"
        const val ZIP_PATTERN = "^\\d{5}(-\\d{4})?$"
        const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    }
}
