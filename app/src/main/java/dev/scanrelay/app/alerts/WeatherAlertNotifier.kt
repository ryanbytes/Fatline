package dev.scanrelay.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.scanrelay.app.MainActivity
import dev.scanrelay.app.model.ServerProfile

object WeatherAlertNotifier {
    private const val CHANNEL_PREFIX = "fatline_weather_"

    fun post(context: Context, profile: ServerProfile, zip: String, alert: NwsSevereAlert) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val preference = if (AlertSoundPreferences.isWeatherSoundEnabled(context, profile.id)) {
            AlertSoundPreferences.getWeather(context, profile.id)
        } else {
            AlertSoundPreferences.SILENT
        }
        val channelPrefix = CHANNEL_PREFIX + profile.id.hashCode() + "_"
        val channelId = channelPrefix + (preference ?: "default").hashCode()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val channel = NotificationChannel(
            channelId,
            "${profile.name} severe weather",
            NotificationManager.IMPORTANCE_HIGH
        )
        when (preference) {
            AlertSoundPreferences.SILENT -> channel.setSound(null, null)
            null -> channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            else -> channel.setSound(Uri.parse(preference), audioAttributes)
        }
        manager.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = listOf(alert.headline, alert.area).filter(String::isNotBlank).joinToString(" · ")
            .ifBlank { "A new ${alert.severity.lowercase()} weather warning is active near ZIP $zip." }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(alert.event)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val id = ("weather:$zip:${alert.id}").hashCode().and(Int.MAX_VALUE)
        manager.notify(id, notification)
    }
}
