package dev.scanrelay.app.alerts

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object AlertSoundPreferences {
    const val SILENT = "__silent__"
    private const val PREFS = "fatline_alert_sounds"
    private const val ALERT_PREFIX = "alert:"
    private const val DISCONNECT_PREFIX = "disconnect:"

    fun get(context: Context, profileId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ALERT_PREFIX + profileId, null)

    fun getDisconnect(context: Context, profileId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DISCONNECT_PREFIX + profileId, null)

    fun set(context: Context, profileId: String, uri: Uri?) {
        val value = uri?.toString() ?: SILENT
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ALERT_PREFIX + profileId, value)
            .apply()
    }

    fun setDisconnect(context: Context, profileId: String, uri: Uri?) {
        val value = uri?.toString() ?: SILENT
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(DISCONNECT_PREFIX + profileId, value)
            .apply()
    }

    fun useSystemDefault(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ALERT_PREFIX + profileId)
            .apply()
    }

    fun useSystemDefaultDisconnect(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DISCONNECT_PREFIX + profileId)
            .apply()
    }

    fun clear(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ALERT_PREFIX + profileId)
            .remove(DISCONNECT_PREFIX + profileId)
            .apply()
    }

    fun displayName(context: Context, profileId: String): String =
        displayName(context, get(context, profileId))

    fun displayDisconnectName(context: Context, profileId: String): String =
        displayName(context, getDisconnect(context, profileId))

    private fun displayName(context: Context, setting: String?): String {
        if (setting == null) return "System default"
        if (setting == SILENT) return "Silent"

        val uri = runCatching { Uri.parse(setting) }.getOrNull() ?: return "Custom sound"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Custom sound"
    }
}
