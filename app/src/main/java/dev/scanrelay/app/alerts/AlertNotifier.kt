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

object AlertNotifier {
    private const val ALERT_CHANNEL_PREFIX = "fatline_alerts_"
    private const val CONNECTION_CHANNEL_PREFIX = "fatline_connection_"

    fun post(context: Context, profileId: String, profileName: String, title: String, body: String, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val soundSetting = AlertSoundPreferences.get(context, profileId)
        val profileChannelPrefix = ALERT_CHANNEL_PREFIX + profileId.hashCode() + "_"
        val channelId = profileChannelPrefix + (soundSetting ?: "default").hashCode()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val channel = NotificationChannel(channelId, "$profileName alerts", NotificationManager.IMPORTANCE_HIGH)
        when (soundSetting) {
            AlertSoundPreferences.SILENT -> channel.setSound(null, null)
            null -> channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            else -> channel.setSound(Uri.parse(soundSetting), audioAttributes)
        }
        manager.createNotificationChannel(channel)
        manager.notificationChannels
            .filter { it.id.startsWith(profileChannelPrefix) && it.id != channelId }
            .forEach { manager.deleteNotificationChannel(it.id) }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }

    fun postConnectionLoss(
        context: Context,
        profileId: String,
        serverName: String,
        detail: String,
        notificationId: Int
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val soundSetting = AlertSoundPreferences.getDisconnect(context, profileId)
        val profileChannelPrefix = CONNECTION_CHANNEL_PREFIX + profileId.hashCode() + "_"
        val channelId = profileChannelPrefix + (soundSetting ?: "default").hashCode()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val channel = NotificationChannel(
            channelId,
            "$serverName disconnects",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        when (soundSetting) {
            AlertSoundPreferences.SILENT -> channel.setSound(null, null)
            null -> channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            else -> channel.setSound(Uri.parse(soundSetting), audioAttributes)
        }
        manager.createNotificationChannel(channel)
        manager.notificationChannels
            .filter { it.id.startsWith(profileChannelPrefix) && it.id != channelId }
            .forEach { manager.deleteNotificationChannel(it.id) }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$serverName disconnected")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
    }
}
