package dev.scanrelay.app.alerts

import android.content.Context
import dev.scanrelay.app.model.ScannerAlert
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Deleting an alert is a device-local dismissal. Never modify server alert
 * history or transmit deletion requests to the scanner.
 */
internal object AlertDismissalPolicy {
    const val LIMIT = 2000

    fun key(alert: ScannerAlert): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(alert.stableKey.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
    }

    fun retain(previous: Collection<String>, key: String): LinkedHashSet<String> {
        val next = LinkedHashSet(previous)
        next.remove(key)
        next.add(key)
        while (next.size > LIMIT) next.remove(next.first())
        return next
    }

    fun visible(alerts: List<ScannerAlert>, dismissedKeys: Set<String>): List<ScannerAlert> =
        alerts.filterNot { key(it) in dismissedKeys }
}

internal class AlertDismissalStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_dismissed_alerts", Context.MODE_PRIVATE)

    private fun prefKey(profileId: String) = "dismissed_$profileId"

    private fun read(profileId: String): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        val rows = runCatching { JSONArray(prefs.getString(prefKey(profileId), "[]")) }.getOrNull()
        if (rows != null) for (i in 0 until rows.length()) {
            rows.optString(i).takeIf(String::isNotBlank)?.let(result::add)
        }
        return result
    }

    @Synchronized
    fun dismiss(alert: ScannerAlert) {
        val next = AlertDismissalPolicy.retain(read(alert.profileId), AlertDismissalPolicy.key(alert))
        prefs.edit().putString(prefKey(alert.profileId), JSONArray(next.toList()).toString()).apply()
    }

    @Synchronized
    fun visible(profileId: String, alerts: List<ScannerAlert>): List<ScannerAlert> =
        AlertDismissalPolicy.visible(alerts, read(profileId))

    @Synchronized
    fun clearProfile(profileId: String) {
        prefs.edit().remove(prefKey(profileId)).apply()
    }
}
