package dev.scanrelay.app.alerts

import android.content.Context
import dev.scanrelay.app.model.ScannerAlert
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/** Rules and transcript matching stay on the phone. */
internal object LocalTranscriptAlertPolicy {
    const val MAX_TERMS = 50
    const val FAST_POLL_MS = 30_000L
    const val SAVER_POLL_MS = 60_000L

    // WebSocket CALL transcripts are always matched as they arrive. Only the
    // fallback HTTP scan for delayed transcription uses the selected cadence.
    fun pollIntervalMs(batterySaver: Boolean): Long =
        if (batterySaver) SAVER_POLL_MS else FAST_POLL_MS

    private const val MAX_LENGTH = 100
    private val separator = Regex("[^\\p{L}\\p{N}]+")

    fun terms(raw: String): List<String> = raw.split(',', '\n', '\r')
        .map(String::trim)
        .filter { it.isNotBlank() && it.length <= MAX_LENGTH }
        .distinctBy { normalize(it) }
        .take(MAX_TERMS)

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(separator, " ").trim()

    /** Token boundaries protect "fire" from matching "firefighter". */
    fun matches(transcript: String, rules: List<String>): List<String> {
        val haystack = " " + normalize(transcript) + " "
        if (transcript.isBlank()) return emptyList()
        return rules.filter { candidate ->
            val needle = normalize(candidate)
            needle.isNotBlank() && haystack.contains(" " + needle + " ")
        }
    }
}

internal class LocalTranscriptAlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_local_transcript_alerts", Context.MODE_PRIVATE)

    fun rawRules(profileId: String): String = prefs.getString("rules_$profileId", "").orEmpty()
    fun rules(profileId: String): List<String> = LocalTranscriptAlertPolicy.terms(rawRules(profileId))
    fun enabled(profileId: String): Boolean = prefs.getBoolean("enabled_$profileId", false)
    fun batterySaver(profileId: String): Boolean = prefs.getBoolean("battery_saver_$profileId", true)
    fun active(profileId: String): Boolean = enabled(profileId) && rules(profileId).isNotEmpty()
    fun enabledAt(profileId: String): Long = prefs.getLong("since_$profileId", 0L)

    /** An initial poll ignores history older than when monitoring was switched on. */
    fun isNewSinceEnable(profileId: String, timestamp: Long?): Boolean {
        val enabledAt = enabledAt(profileId)
        if (timestamp == null || enabledAt <= 0) return false
        val timestampMs = if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
        return timestampMs >= enabledAt - 15_000L
    }

    @Synchronized
    fun configure(profileId: String, raw: String, enabled: Boolean, batterySaver: Boolean) {
        val normalized = LocalTranscriptAlertPolicy.terms(raw).joinToString("\n")
        val previous = rawRules(profileId)
        val restartBaseline = normalized != previous || (enabled && !this.enabled(profileId))
        prefs.edit().putString("rules_$profileId", normalized)
            .putBoolean("enabled_$profileId", enabled)
            .putBoolean("battery_saver_$profileId", batterySaver)
            .apply()
        if (restartBaseline) {
            prefs.edit().putLong("since_$profileId", System.currentTimeMillis()).apply()
        }
        if (restartBaseline) {
            prefs.edit().remove("seen_$profileId").remove("bootstrapped_$profileId").apply()
        }
    }

    @Synchronized
    fun bootstrapped(profileId: String): Boolean = prefs.getBoolean("bootstrapped_$profileId", false)

    @Synchronized
    fun accept(profileId: String, callId: Long): Boolean {
        if (callId <= 0) return false
        val existing = readSeen(profileId)
        if (callId in existing) return false
        existing.add(callId)
        persistSeen(profileId, existing)
        return true
    }

    /** First API page is a baseline, not a burst of old notifications. */
    @Synchronized
    fun baseline(profileId: String, ids: Collection<Long>) {
        val seen = readSeen(profileId)
        ids.filter { it > 0 }.forEach(seen::add)
        persistSeen(profileId, seen)
        prefs.edit().putBoolean("bootstrapped_$profileId", true).apply()
    }

    private fun readSeen(profileId: String): LinkedHashSet<Long> {
        val seen = LinkedHashSet<Long>()
        val rows = runCatching { JSONArray(prefs.getString("seen_$profileId", "[]")) }.getOrNull()
        if (rows != null) for (i in 0 until rows.length()) {
            rows.optLong(i).takeIf { it > 0 }?.let(seen::add)
        }
        return seen
    }

    private fun persistSeen(profileId: String, seen: LinkedHashSet<Long>) {
        while (seen.size > 500) seen.remove(seen.first())
        prefs.edit().putString("seen_$profileId", JSONArray(seen.toList()).toString()).apply()
    }

    @Synchronized
    fun addAlert(alert: ScannerAlert) {
        val key = "alerts_" + alert.profileId
        val saved = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrNull() ?: JSONArray()
        val row = JSONObject()
            .put("callId", alert.callId)
            .put("title", alert.title)
            .put("body", alert.body)
            .put("dateTime", alert.dateTime)
            .put("keywords", JSONArray(alert.keywords))
            .put("systemLabel", alert.systemLabel)
            .put("talkgroupLabel", alert.talkgroupLabel)
        val next = JSONArray().put(row)
        for (i in 0 until minOf(saved.length(), 99)) next.put(saved.get(i))
        prefs.edit().putString(key, next.toString()).apply()
    }

    fun alerts(profileId: String, serverName: String): List<ScannerAlert> {
        val rows = runCatching { JSONArray(prefs.getString("alerts_$profileId", "[]")) }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val callId = row.optLong("callId").takeIf { it > 0 } ?: continue
                val keywords = row.optJSONArray("keywords")
                add(ScannerAlert(
                    profileId = profileId,
                    serverName = serverName,
                    title = row.optString("title"),
                    body = row.optString("body"),
                    dateTime = row.optString("dateTime"),
                    callId = callId,
                    alertType = "Local transcript",
                    keywords = (0 until (keywords?.length() ?: 0)).map { keywords!!.optString(it) },
                    systemLabel = row.optString("systemLabel"),
                    talkgroupLabel = row.optString("talkgroupLabel")
                ))
            }
        }
    }

    fun clearProfile(profileId: String) {
        prefs.edit().remove("rules_$profileId").remove("enabled_$profileId")
            .remove("battery_saver_$profileId")
            .remove("seen_$profileId").remove("bootstrapped_$profileId")
            .remove("alerts_$profileId").remove("since_$profileId").apply()
    }
}

internal fun localTranscriptAlert(
    profileId: String, profileName: String, callId: Long,
    transcript: String, matched: List<String>,
    systemLabel: String? = null, talkgroupLabel: String? = null
): ScannerAlert = ScannerAlert(
    profileId = profileId,
    serverName = profileName,
    title = "Transcript match: " + matched.joinToString(", "),
    body = transcript.take(350),
    dateTime = Instant.now().toString(),
    callId = callId,
    alertType = "Local transcript",
    keywords = matched,
    transcript = transcript.take(350),
    systemLabel = systemLabel,
    talkgroupLabel = talkgroupLabel
)
