package dev.scanrelay.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.scanrelay.app.MainActivity
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.net.ScannerRepository

class ScannerService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        ScannerRepository.initialize(this)
        createChannel()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val title = mediaItem?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "Listening" }
                    val subtitle = mediaItem?.mediaMetadata?.artist?.toString().orEmpty().ifBlank { "Waiting for traffic" }
                    updateNotification(title, subtitle)
                }
            })
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("FatLine", "Scanner service active"))
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::connectProfile)
            ACTION_DISCONNECT -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::disconnectProfile)
            ACTION_DISCONNECT_ALL -> disconnectAll()
            ACTION_ENQUEUE -> addMediaFromIntent(intent)
            ACTION_SKIP -> skipInternal()
            ACTION_STOP_AUDIO -> stopAudioInternal()
            ACTION_REMOVE_PROFILE -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::removeProfileMedia)
            null -> restoreConnections()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session.release()
        player.release()
        ScannerRepository.disconnectAll()
        super.onDestroy()
    }

    private fun connectProfile(profileId: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val active = prefs.getStringSet(KEY_ACTIVE_PROFILES, emptySet()).orEmpty().toMutableSet()
        active += profileId
        prefs.edit().putStringSet(KEY_ACTIVE_PROFILES, active).apply()
        ProfileStore(this).load().firstOrNull { it.id == profileId }?.let(ScannerRepository::connect)
        updateNotification("FatLine", "Monitoring ${active.size} server${if (active.size == 1) "" else "s"}")
    }

    private fun disconnectProfile(profileId: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val active = prefs.getStringSet(KEY_ACTIVE_PROFILES, emptySet()).orEmpty().toMutableSet()
        active -= profileId
        prefs.edit().putStringSet(KEY_ACTIVE_PROFILES, active).apply()
        ScannerRepository.disconnect(profileId)
        removeProfileMedia(profileId)
        if (active.isEmpty()) {
            stopAudioInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else updateNotification("FatLine", "Monitoring ${active.size} servers")
    }

    private fun disconnectAll() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ACTIVE_PROFILES).apply()
        ScannerRepository.disconnectAll()
        stopAudioInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreConnections() {
        val ids = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_ACTIVE_PROFILES, emptySet()).orEmpty()
        val profiles = ProfileStore(this).load().associateBy { it.id }
        ids.mapNotNull(profiles::get).forEach(ScannerRepository::connect)
    }

    private fun addMediaFromIntent(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_AUDIO_PATH) ?: return
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val callId = intent.getLongExtra(EXTRA_CALL_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Radio traffic" }
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val item = MediaItem.Builder()
            .setMediaId("call:$profileId:$callId:${System.nanoTime()}")
            .setUri(path.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
        if (player.mediaItemCount >= 30) player.removeMediaItem(0)
        player.addMediaItem(item)
        player.prepare()
        if (!player.isPlaying) player.play()
    }

    private fun skipInternal() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        else if (player.mediaItemCount > 0) {
            player.removeMediaItem(player.currentMediaItemIndex.coerceAtLeast(0))
            if (player.mediaItemCount > 0) player.play()
        }
    }

    private fun stopAudioInternal() {
        player.stop()
        player.clearMediaItems()
    }

    private fun removeProfileMedia(profileId: String) {
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId.startsWith("call:$profileId:")) player.removeMediaItem(index)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scanner playback", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScannerService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect all", stop)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(browsableItem(ROOT_ID, "FatLine", "Scanner servers"), params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = when {
                parentId == ROOT_ID -> ProfileStore(this@ScannerService).load().map { profile ->
                    browsableItem("profile:${profile.id}", profile.name, profile.baseUrl)
                }
                parentId.startsWith("profile:") -> {
                    val profileId = parentId.removePrefix("profile:")
                    val state = ScannerRepository.state.value.servers[profileId]
                    state?.systems.orEmpty().flatMap { system ->
                        system.talkgroups.filter { it.favorite }.map { tg ->
                            playableItem("channel:$profileId:${tg.systemRef}:${tg.talkgroupRef}", tg.displayName, system.label)
                        }
                    }
                }
                else -> emptyList()
            }
            val safeSize = pageSize.coerceIn(1, 100)
            val from = (page.toLong().coerceAtLeast(0) * safeSize.toLong()).coerceAtMost(all.size.toLong()).toInt()
            val to = (from + safeSize).coerceAtMost(all.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(all.subList(from, to), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            mediaItems.forEach { item ->
                val parts = item.mediaId.split(':')
                when (parts.firstOrNull()) {
                    "profile" -> parts.getOrNull(1)?.let { connect(this@ScannerService, it) }
                    "channel" -> if (parts.size >= 4) {
                        val profileId = parts[1]
                        val system = parts[2].toLongOrNull()
                        val talkgroup = parts[3].toLongOrNull()
                        if (system != null && talkgroup != null) ScannerRepository.setHold(profileId, ChannelKey(system, talkgroup))
                    }
                }
            }
            return Futures.immediateFuture(mutableListOf())
        }
    }

    private fun browsableItem(id: String, title: String, subtitle: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    private fun playableItem(id: String, title: String, subtitle: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(false).setIsPlayable(true).build())
        .build()

    companion object {
        private const val CHANNEL_ID = "fatline_playback"
        private const val NOTIFICATION_ID = 8101
        private const val PREFS = "fatline_session"
        private const val KEY_ACTIVE_PROFILES = "active_profiles"
        private const val ROOT_ID = "fatline_root"

        const val ACTION_CONNECT = "dev.scanrelay.CONNECT"
        const val ACTION_DISCONNECT = "dev.scanrelay.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.scanrelay.DISCONNECT_ALL"
        const val ACTION_ENQUEUE = "dev.scanrelay.ENQUEUE"
        const val ACTION_SKIP = "dev.scanrelay.SKIP"
        const val ACTION_STOP_AUDIO = "dev.scanrelay.STOP_AUDIO"
        const val ACTION_REMOVE_PROFILE = "dev.scanrelay.REMOVE_PROFILE"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"

        fun connect(context: Context, profileId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScannerService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE_ID, profileId)
            )
        }

        fun disconnect(context: Context, profileId: String) {
            context.startService(Intent(context, ScannerService::class.java).setAction(ACTION_DISCONNECT).putExtra(EXTRA_PROFILE_ID, profileId))
        }

        fun disconnectAll(context: Context) {
            context.startService(Intent(context, ScannerService::class.java).setAction(ACTION_DISCONNECT_ALL))
        }

        fun enqueue(context: Context, call: RadioCall) {
            val path = call.audioPath ?: return
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_PROFILE_ID, call.profileId)
                .putExtra(EXTRA_CALL_ID, call.id)
                .putExtra(EXTRA_AUDIO_PATH, path)
                .putExtra(EXTRA_TITLE, call.talkgroupLabel)
                .putExtra(EXTRA_SUBTITLE, "${call.serverName} · ${call.systemLabel}")
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun skip(context: Context) {
            context.startService(Intent(context, ScannerService::class.java).setAction(ACTION_SKIP))
        }

        fun stopAudio(context: Context) {
            context.startService(Intent(context, ScannerService::class.java).setAction(ACTION_STOP_AUDIO))
        }

        fun removeProfile(context: Context, profileId: String) {
            context.startService(Intent(context, ScannerService::class.java).setAction(ACTION_REMOVE_PROFILE).putExtra(EXTRA_PROFILE_ID, profileId))
        }
    }
}
