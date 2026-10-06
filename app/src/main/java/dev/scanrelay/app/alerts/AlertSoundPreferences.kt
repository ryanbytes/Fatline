package dev.scanrelay.app.alerts

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object AlertSoundPreferences {
    const val SILENT = "__silent__"
    private const val PREFS = "fatline_alert_sounds"
    private const val ALERT_PREFIX = "alert:"

    fun get(context: Context, profileId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ALERT_PREFIX + profileId, null)

    fun set(context: Context, profileId: String, uri: Uri?) {
        val value = uri?.toString() ?: SILENT
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ALERT_PREFIX + profileId, value)
            .apply()
    }

    fun useSystemDefault(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ALERT_PREFIX + profileId)
            .apply()
    }

    fun clear(context: Context, profileId: String) = useSystemDefault(context, profileId)

    fun displayName(context: Context, profileId: String): String {
        val setting = get(context, profileId)
        if (setting == null) return "System default"
        if (setting == SILENT) return "Silent"

        val uri = runCatching { Uri.parse(setting) }.getOrNull() ?: return "Custom sound"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Custom sound"
    }
}
