package dev.scanrelay.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.scanrelay.app.MainActivity
import dev.scanrelay.app.alerts.NwsSevereWeatherMonitor
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.net.NetworkHandoffPolicy
import dev.scanrelay.app.net.NetworkHandoffTransition
import dev.scanrelay.app.net.ScannerRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScannerService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var weatherMonitor: NwsSevereWeatherMonitor
    private lateinit var connectivityManager: ConnectivityManager
    private val networkHandler = Handler(Looper.getMainLooper())
    private var currentNetworkHandle: Long? = null
    private val pausedProfiles = mutableSetOf<String>()
    private val callByMediaId = mutableMapOf<String, RadioCall>()

    private val networkLossCheck = Runnable {
        val active = connectivityManager.activeNetwork
        when (NetworkHandoffPolicy.transition(currentNetworkHandle, active?.networkHandle)) {
            NetworkHandoffTransition.NETWORK_LOST -> {
                currentNetworkHandle = null
                ScannerRepository.networkUnavailable()
                updateNotification("FatLine", "Waiting for network")
            }
            NetworkHandoffTransition.NETWORK_RESTORED,
            NetworkHandoffTransition.NETWORK_SWITCHED -> active?.let(::handleNetworkAvailable)
            NetworkHandoffTransition.NO_CHANGE -> Unit
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkHandler.post { handleNetworkAvailable(network) }
        }

        override fun onLost(network: Network) {
            networkHandler.post {
                if (!NetworkHandoffPolicy.isCurrentLoss(network.networkHandle, currentNetworkHandle)) return@post
                // Android often announces the replacement default network immediately before
                // or after this callback. Give the handoff a short window before declaring offline.
                networkHandler.removeCallbacks(networkLossCheck)
                networkHandler.postDelayed(networkLossCheck, NETWORK_LOSS_GRACE_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ScannerRepository.initialize(this)
        weatherMonitor = NwsSevereWeatherMonitor(this).also { it.start() }
        startNetworkTracking()
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
                    syncCurrentlyPlayingCall()
                    val title = mediaItem?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "Listening" }
                    val subtitle = mediaItem?.mediaMetadata?.artist?.toString().orEmpty().ifBlank { "Waiting for traffic" }
                    updateNotification(title, subtitle)
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    syncCurrentlyPlayingCall()
                    val current = player.currentMediaItem
                    val title = current?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "FatLine" }
                    val subtitle = current?.mediaMetadata?.artist?.toString().orEmpty()
                        .ifBlank { "Scanner service active" }
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
            ACTION_SET_PROFILE_PAUSED -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let { profileId ->
                setProfilePausedInternal(profileId, intent.getBooleanExtra(EXTRA_PAUSED, false))
            }
            ACTION_FILTER_PROFILE_MEDIA -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::filterProfileMedia)
            ACTION_SKIP -> {
                skipInternal()
                stopIfIdle()
            }
            ACTION_STOP_AUDIO -> {
                stopAudioInternal()
                stopIfIdle()
            }
            ACTION_CLEAR_QUEUE -> {
                clearQueueInternal()
                stopIfIdle()
            }
            ACTION_REMOVE_PROFILE -> {
                intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::removeProfileMedia)
                stopIfIdle()
            }
            null -> restoreConnections()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        weatherMonitor.stop()
        stopNetworkTracking()
        session.release()
        player.release()
        withRepositoryServiceCallbacksSuppressed { ScannerRepository.disconnectAll() }
        super.onDestroy()
    }

    private fun startNetworkTracking() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        currentNetworkHandle = connectivityManager.activeNetwork?.networkHandle
        if (currentNetworkHandle == null) ScannerRepository.networkUnavailable()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    private fun stopNetworkTracking() {
        networkHandler.removeCallbacks(networkLossCheck)
        if (::connectivityManager.isInitialized) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        networkHandler.removeCallbacks(networkLossCheck)
        val newHandle = network.networkHandle
        val transition = NetworkHandoffPolicy.transition(currentNetworkHandle, newHandle)
        currentNetworkHandle = newHandle
        when (transition) {
            NetworkHandoffTransition.NETWORK_RESTORED -> {
                ScannerRepository.networkChanged("Network restored")
                updateMonitoringNotification(activeProfileIds().size)
            }
            NetworkHandoffTransition.NETWORK_SWITCHED -> {
                ScannerRepository.networkChanged("Network switched")
                updateMonitoringNotification(activeProfileIds().size)
            }
            NetworkHandoffTransition.NO_CHANGE,
            NetworkHandoffTransition.NETWORK_LOST -> Unit
        }
    }

    private fun connectProfile(profileId: String) {
        val profile = ProfileStore(this).load().firstOrNull { it.id == profileId }
        if (profile == null) {
            val active = activeProfileIds().apply { remove(profileId) }
            persistActiveProfiles(active)
            if (active.isEmpty()) stopIfIdle() else updateMonitoringNotification(active.size)
            return
        }

        val active = activeProfileIds().apply { add(profileId) }
        persistActiveProfiles(active)
        ScannerRepository.connect(profile)
        updateMonitoringNotification(active.size)
    }

    private fun disconnectProfile(profileId: String) {
        pausedProfiles -= profileId
        val active = activeProfileIds().apply { remove(profileId) }
        persistActiveProfiles(active)
        withRepositoryServiceCallbacksSuppressed { ScannerRepository.disconnect(profileId) }
        removeProfileMedia(profileId)
        if (active.isEmpty()) {
            stopAudioInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else updateMonitoringNotification(active.size)
    }

    private fun disconnectAll() {
        pausedProfiles.clear()
        persistActiveProfiles(emptySet())
        withRepositoryServiceCallbacksSuppressed { ScannerRepository.disconnectAll() }
        stopAudioInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreConnections() {
        val savedIds = activeProfileIds()
        val profiles = ProfileStore(this).load().associateBy { it.id }
        val validIds = savedIds.filterTo(mutableSetOf()) { profiles.containsKey(it) }
        if (validIds != savedIds) persistActiveProfiles(validIds)

        if (validIds.isEmpty()) {
            stopAudioInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        validIds.mapNotNull(profiles::get).forEach(ScannerRepository::connect)
        updateMonitoringNotification(validIds.size)
    }

    private fun activeProfileIds(): MutableSet<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_ACTIVE_PROFILES, emptySet())
            .orEmpty()
            .toMutableSet()

    private fun persistActiveProfiles(active: Set<String>) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (active.isEmpty()) editor.remove(KEY_ACTIVE_PROFILES)
        else editor.putStringSet(KEY_ACTIVE_PROFILES, active.toSet())
        editor.apply()
    }

    private fun updateMonitoringNotification(count: Int) {
        if (count <= 0) return
        updateNotification("FatLine", "Monitoring $count server${if (count == 1) "" else "s"}")
    }

    private fun stopIfIdle() {
        if (activeProfileIds().isNotEmpty() || player.mediaItemCount > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private inline fun withRepositoryServiceCallbacksSuppressed(block: () -> Unit) {
        suppressRepositoryServiceCallbacks = true
        try {
            block()
        } finally {
            suppressRepositoryServiceCallbacks = false
        }
    }

    private fun addMediaFromIntent(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_AUDIO_PATH) ?: return
        val token = intent.getStringExtra(EXTRA_CALL_TOKEN).orEmpty()
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        if (profileId in pausedProfiles) {
            pendingCalls.remove(token)
            return
        }
        val call = pendingCalls.remove(token)
        val callId = intent.getLongExtra(EXTRA_CALL_ID, 0L)
        val systemRef = intent.getLongExtra(EXTRA_SYSTEM_REF, 0L)
        val talkgroupRef = intent.getLongExtra(EXTRA_TALKGROUP_REF, 0L)
        val liveFeed = intent.getBooleanExtra(EXTRA_LIVE_FEED, true)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Radio traffic" }
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val mediaKind = if (liveFeed) "live" else "replay"
        val item = MediaItem.Builder()
            .setMediaId("call:$profileId:$mediaKind:$callId:$systemRef:$talkgroupRef:$token")
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

        if (player.playbackState == Player.STATE_ENDED) player.clearMediaItems()
        trimQueueForIncomingCall(liveFeed)
        player.addMediaItem(item)
        if (call != null) callByMediaId[item.mediaId] = call
        syncCurrentlyPlayingCall()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (!player.playWhenReady) player.play()
    }

    private fun filterProfileMedia(profileId: String) {
        for (index in player.mediaItemCount - 1 downTo 0) {
            val parts = player.getMediaItemAt(index).mediaId.split(':')
            if (parts.size < 7 || parts[0] != "call" || parts[1] != profileId || parts[2] != "live") continue
            val systemRef = parts[4].toLongOrNull() ?: continue
            val talkgroupRef = parts[5].toLongOrNull() ?: continue
            if (!ScannerRepository.isChannelSubscribed(profileId, systemRef, talkgroupRef)) {
                player.removeMediaItem(index)
            }
        }
    }
    private fun setProfilePausedInternal(profileId: String, paused: Boolean) {
        if (paused) {
            pausedProfiles += profileId
            // A paused scanner should become silent immediately. Remove its current
            // and queued calls while leaving audio from other connected scanners alone.
            removeProfileMedia(profileId)
        } else {
            pausedProfiles -= profileId
        }
    }

    private fun syncCurrentlyPlayingCall() {
        val activeIds = (0 until player.mediaItemCount)
            .mapTo(mutableSetOf()) { index -> player.getMediaItemAt(index).mediaId }
        callByMediaId.keys.retainAll(activeIds)
        _currentlyPlayingCall.value = player.currentMediaItem?.mediaId?.let(callByMediaId::get)
    }

    private fun trimQueueForIncomingCall(liveFeed: Boolean) {
        val mediaIds = List(player.mediaItemCount) { index ->
            player.getMediaItemAt(index).mediaId
        }
        val removeIndex = PlaybackQueuePolicy.removalIndex(
            mediaIds = mediaIds,
            currentIndex = player.currentMediaItemIndex,
            incomingLiveFeed = liveFeed
        )
        if (removeIndex >= 0) player.removeMediaItem(removeIndex)
    }

    private fun skipInternal() {
        if (player.mediaItemCount <= 0) return
        val index = player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount } ?: 0
        player.removeMediaItem(index)
        if (player.mediaItemCount > 0 && !player.playWhenReady) player.play()
    }

    private fun clearQueueInternal() {
        val firstQueuedIndex = PlaybackQueuePolicy.firstQueuedIndex(
            mediaCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex
        )
        for (index in player.mediaItemCount - 1 downTo firstQueuedIndex) {
            player.removeMediaItem(index)
        }
    }

    private fun stopAudioInternal() {
        player.stop()
        player.clearMediaItems()
        callByMediaId.clear()
        _currentlyPlayingCall.value = null
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
        val skip = PendingIntent.getService(
            this,
            2,
            Intent(this, ScannerService::class.java).setAction(ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clearQueue = PendingIntent.getService(
            this,
            3,
            Intent(this, ScannerService::class.java).setAction(ACTION_CLEAR_QUEUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Skip", skip)
            .addAction(0, "Clear queue", clearQueue)
            .addAction(0, "Disconnect all", stop)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val queuedCount = PlaybackQueuePolicy.queuedCount(
            mediaCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex
        )
        _queuedCallCount.value = queuedCount
        val queueText = if (queuedCount > 0) "$text · $queuedCount queued" else text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, queueText))
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
                    serverItem("profile:${profile.id}", profile.name, profile.baseUrl)
                }
                parentId.startsWith("profile:") -> {
                    val profileId = parentId.removePrefix("profile:")
                    val state = ScannerRepository.state.value.servers[profileId]
                    state?.systems.orEmpty()
                        .filterNot { it.systemRef in state?.hiddenSystemRefs.orEmpty() }
                        .flatMap { system ->
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

    private fun serverItem(id: String, title: String, subtitle: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(true).setIsPlayable(true).build())
        .build()

    private fun playableItem(id: String, title: String, subtitle: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(false).setIsPlayable(true).build())
        .build()

    companion object {
        private val _queuedCallCount = MutableStateFlow(0)
        val queuedCallCount: StateFlow<Int> = _queuedCallCount.asStateFlow()
        private val _currentlyPlayingCall = MutableStateFlow<RadioCall?>(null)
        val currentlyPlayingCall: StateFlow<RadioCall?> = _currentlyPlayingCall.asStateFlow()
        private val pendingCalls = ConcurrentHashMap<String, RadioCall>()

        private const val CHANNEL_ID = "fatline_playback"
        private const val NOTIFICATION_ID = 8101
        private const val PREFS = "fatline_session"
        private const val KEY_ACTIVE_PROFILES = "active_profiles"
        private const val ROOT_ID = "fatline_root"
        private const val NETWORK_LOSS_GRACE_MS = 650L

        @Volatile private var suppressRepositoryServiceCallbacks = false

        const val ACTION_CONNECT = "dev.scanrelay.CONNECT"
        const val ACTION_DISCONNECT = "dev.scanrelay.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.scanrelay.DISCONNECT_ALL"
        const val ACTION_ENQUEUE = "dev.scanrelay.ENQUEUE"
        const val ACTION_SET_PROFILE_PAUSED = "dev.scanrelay.SET_PROFILE_PAUSED"
        const val ACTION_FILTER_PROFILE_MEDIA = "dev.scanrelay.FILTER_PROFILE_MEDIA"
        const val ACTION_SKIP = "dev.scanrelay.SKIP"
        const val ACTION_CLEAR_QUEUE = "dev.scanrelay.CLEAR_QUEUE"
        const val ACTION_STOP_AUDIO = "dev.scanrelay.STOP_AUDIO"
        const val ACTION_REMOVE_PROFILE = "dev.scanrelay.REMOVE_PROFILE"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_SYSTEM_REF = "system_ref"
        const val EXTRA_TALKGROUP_REF = "talkgroup_ref"
        const val EXTRA_LIVE_FEED = "live_feed"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_CALL_TOKEN = "call_token"

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

        fun enqueue(context: Context, call: RadioCall, liveFeed: Boolean = true) {
            val path = call.audioPath ?: return
            val token = UUID.randomUUID().toString()
            pendingCalls[token] = call
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_CALL_TOKEN, token)
                .putExtra(EXTRA_PROFILE_ID, call.profileId)
                .putExtra(EXTRA_CALL_ID, call.id)
                .putExtra(EXTRA_SYSTEM_REF, call.systemRef)
                .putExtra(EXTRA_TALKGROUP_REF, call.talkgroupRef)
                .putExtra(EXTRA_LIVE_FEED, liveFeed)
                .putExtra(EXTRA_AUDIO_PATH, path)
                .putExtra(EXTRA_TITLE, call.talkgroupLabel)
                .putExtra(EXTRA_SUBTITLE, "${call.serverName} · ${call.systemLabel}")
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun filterProfileMedia(context: Context, profileId: String) {
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_FILTER_PROFILE_MEDIA)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }
        fun setProfilePaused(context: Context, profileId: String, paused: Boolean) {
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_SET_PROFILE_PAUSED)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_PAUSED, paused)
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun skip(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_SKIP)
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopAudio(context: Context) {
            if (suppressRepositoryServiceCallbacks) return
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_STOP_AUDIO)
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun removeProfile(context: Context, profileId: String) {
            if (suppressRepositoryServiceCallbacks) return
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_REMOVE_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
