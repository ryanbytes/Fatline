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
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.scanrelay.app.MainActivity
import dev.scanrelay.app.alerts.NwsSevereWeatherMonitor
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.data.ScannerPausePolicy
import dev.scanrelay.app.data.ScannerPauseStore
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.CallKey
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.net.NetworkHandoffPolicy
import dev.scanrelay.app.net.NetworkHandoffTransition
import dev.scanrelay.app.net.ScannerRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueuedCall(val call: RadioCall, val liveFeed: Boolean)

@UnstableApi
class ScannerService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var weatherMonitor: NwsSevereWeatherMonitor
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var pauseStore: ScannerPauseStore
    private lateinit var playbackQueueStore: PlaybackQueueStore
    private var restoringQueue = true
    private var releasingPlayer = false
    private val networkHandler = Handler(Looper.getMainLooper())
    private var currentNetworkHandle: Long? = null
    private val pausedProfiles = mutableSetOf<String>()
    private val callByMediaId = mutableMapOf<String, RadioCall>()
    // Keep recent accepted live IDs through socket reconnects. Bounded in memory.
    private val recentlyAcceptedLiveCalls = LinkedHashSet<CallKey>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val libraryChildren = mutableMapOf<String, List<AndroidAutoFavorite>>()

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
        pauseStore = ScannerPauseStore(this)
        playbackQueueStore = PlaybackQueueStore(this)
        weatherMonitor = NwsSevereWeatherMonitor(this).also { it.start() }
        startNetworkTracking()
        createChannel()
        player = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                // Do not request audio focus: mix scanner traffic with music, navigation,
                // podcasts, and other apps without pausing or ducking their volume.
                false
            )
            addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    persistPlaybackQueue()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    persistPlaybackQueue()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    syncCurrentlyPlayingCall()
                    updatePlaybackNotification()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    syncCurrentlyPlayingCall()
                    updatePlaybackNotification()
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    syncCurrentlyPlayingCall()
                    updatePlaybackNotification()
                }
            })
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        restoreSavedPlaybackQueue()
        serviceScope.launch {
            ScannerRepository.state.collect(::refreshFavoriteLibraryChildren)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialText = if (::player.isInitialized) currentPlaybackNotification()
            else PlaybackNotificationText("FatLine", "Scanner service active")
        val queued = if (::player.isInitialized) PlaybackQueuePolicy.queuedCount(
            player.mediaItemCount, player.currentMediaItemIndex
        ) else 0
        val subtitle = if (queued > 0) "${initialText.subtitle} · $queued queued" else initialText.subtitle
        startForeground(NOTIFICATION_ID, notification(initialText.title, subtitle))
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
                intent.getStringExtra(EXTRA_PROFILE_ID)?.let { profileId ->
                    removeProfileMedia(profileId)
                    recentlyAcceptedLiveCalls.removeAll { it.profileId == profileId }
                }
                stopIfIdle()
            }
            null -> restoreConnections()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        persistPlaybackQueue()
        releasingPlayer = true
        serviceScope.cancel()
        weatherMonitor.stop()
        stopNetworkTracking()
        session.release()
        player.release()
        _currentlyPlayingCall.value = null
        _queuedCallCount.value = 0
        _queuedCalls.value = emptyList()
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
        if (pauseStore.isPaused(profileId)) pausedProfiles += profileId
        else pausedProfiles -= profileId
        ScannerRepository.connect(profile)
        updateMonitoringNotification(active.size)
    }

    private fun disconnectProfile(profileId: String) {
        pausedProfiles -= profileId
        recentlyAcceptedLiveCalls.removeAll { it.profileId == profileId }
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
        recentlyAcceptedLiveCalls.clear()
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
        pausedProfiles.clear()
        pausedProfiles.addAll(validIds.filter(pauseStore::isPaused))

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
        if (::player.isInitialized && player.isPlaying) {
            updatePlaybackNotification()
        } else {
            updateNotification("FatLine", "Monitoring $count server${if (count == 1) "" else "s"}")
        }
    }

    private fun stopIfIdle() {
        if (activeProfileIds().isNotEmpty() || player.mediaItemCount > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshFavoriteLibraryChildren(state: ScannerState) {
        val profileIds = state.servers.keys + libraryChildren.keys
        profileIds.forEach { profileId ->
            val current = AndroidAutoLibraryPolicy.favoriteChannels(profileId, state)
            val previous = libraryChildren.put(profileId, current)
            if (AndroidAutoLibraryPolicy.childrenChanged(previous, current)) {
                session.notifyChildrenChanged("profile:$profileId", current.size, null)
            }
        }
    }

    private inline fun withRepositoryServiceCallbacksSuppressed(block: () -> Unit) {
        suppressRepositoryServiceCallbacks = true
        try {
            block()
        } finally {
            suppressRepositoryServiceCallbacks = false
        }
    }

    private fun mediaItemFor(call: RadioCall, mediaId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(requireNotNull(call.audioPath).toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(call.talkgroupLabel)
                    .setArtist("${call.serverName} · ${call.systemLabel}")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun restoreSavedPlaybackQueue() {
        val snapshot = playbackQueueStore.load()
        if (snapshot == null) {
            restoringQueue = false
            return
        }

        // Retain audio only for scanners that are still configured and connected
        // by the user; never resurrect a disconnected/deleted profile.
        val active = activeProfileIds()
        val configured = ProfileStore(this).load().mapTo(mutableSetOf()) { it.id }
        val recovered = snapshot.calls.filter { entry ->
            entry.call.profileId in active &&
                entry.call.profileId in configured &&
                !(entry.liveFeed && pauseStore.isPaused(entry.call.profileId)) &&
                entry.call.audioPath?.let { File(it).isFile } == true
        }
        if (recovered.isEmpty()) {
            playbackQueueStore.save(null)
            restoringQueue = false
            return
        }

        recovered.forEach { entry ->
            callByMediaId[entry.mediaId] = entry.call
            if (entry.liveFeed) {
                PlaybackQueuePolicy.liveCallKey(entry.mediaId)?.let(::rememberLiveCall)
            }
        }
        player.setMediaItems(recovered.map { mediaItemFor(it.call, it.mediaId) })
        if (snapshot.calls.first().mediaId == recovered.first().mediaId && snapshot.positionMs > 0L) {
            player.seekTo(0, snapshot.positionMs)
        }
        player.prepare()
        if (snapshot.playWhenReady) player.play()
        restoringQueue = false
        syncCurrentlyPlayingCall()
    }

    private fun persistPlaybackQueue() {
        if (restoringQueue || releasingPlayer || !::playbackQueueStore.isInitialized) return
        if (player.playbackState == Player.STATE_ENDED) {
            playbackQueueStore.save(null)
            return
        }
        val start = PlaybackQueuePolicy.recoveryStartIndex(
            player.mediaItemCount, player.currentMediaItemIndex
        )
        val entries = (start until player.mediaItemCount).mapNotNull { index ->
            val mediaId = player.getMediaItemAt(index).mediaId
            callByMediaId[mediaId]?.takeIf { !it.audioPath.isNullOrBlank() }?.let { call ->
                SavedPlaybackCall(
                    mediaId = mediaId,
                    call = call,
                    liveFeed = PlaybackQueuePolicy.mediaKind(mediaId) == "live"
                )
            }
        }
        playbackQueueStore.save(
            if (entries.isEmpty()) null else SavedPlaybackQueue(
                calls = entries,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady
            )
        )
    }

    private fun rememberLiveCall(key: CallKey) {
        // The same call may be resent during reconnect backlog; don't let repeats
        // fill the bounded queue and evict other waiting calls.
        recentlyAcceptedLiveCalls.remove(key)
        recentlyAcceptedLiveCalls.add(key)
        while (recentlyAcceptedLiveCalls.size > PlaybackQueuePolicy.RECENT_LIVE_ID_LIMIT) {
            val iterator = recentlyAcceptedLiveCalls.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun addMediaFromIntent(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_AUDIO_PATH) ?: return
        val token = intent.getStringExtra(EXTRA_CALL_TOKEN).orEmpty()
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val liveFeed = intent.getBooleanExtra(EXTRA_LIVE_FEED, true)
        if (ScannerPausePolicy.suppressIncomingAudio(
                liveFeed = liveFeed,
                paused = profileId in pausedProfiles || pauseStore.isPaused(profileId)
            )) {
            pendingCalls.remove(token)
            return
        }
        val callId = intent.getLongExtra(EXTRA_CALL_ID, 0L)
        if (liveFeed && !PlaybackQueuePolicy.shouldEnqueueLiveCall(
                profileId = profileId,
                callId = callId,
                recentlyAccepted = recentlyAcceptedLiveCalls,
                mediaIds = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
            )) {
            pendingCalls.remove(token)
            return
        }
        val systemRef = intent.getLongExtra(EXTRA_SYSTEM_REF, 0L)
        val talkgroupRef = intent.getLongExtra(EXTRA_TALKGROUP_REF, 0L)
        val playImmediately = intent.getBooleanExtra(EXTRA_PLAY_IMMEDIATELY, false)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Radio traffic" }
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val call = pendingCalls.remove(token) ?: RadioCall(
            profileId = profileId,
            serverName = subtitle.substringBefore(" · ").ifBlank { "Scanner" },
            id = callId,
            systemRef = systemRef,
            talkgroupRef = talkgroupRef,
            systemLabel = subtitle.substringAfter(" · ", "System $systemRef"),
            talkgroupLabel = title,
            dateTime = "",
            audioPath = path
        )
        val mediaKind = if (liveFeed) "live" else "replay"
        val item = mediaItemFor(call, "call:$profileId:$mediaKind:$callId:$systemRef:$talkgroupRef:$token")

        if (player.playbackState == Player.STATE_ENDED) player.clearMediaItems()
        trimQueueForIncomingCall(liveFeed)
        if (playImmediately) {
            val position = PlaybackQueuePolicy.immediateInsertIndex(
                player.mediaItemCount, player.currentMediaItemIndex
            )
            player.addMediaItem(position, item)
            player.seekTo(position, 0L)
        } else {
            player.addMediaItem(item)
        }
        callByMediaId[item.mediaId] = call
        if (liveFeed && callId > 0L) rememberLiveCall(CallKey(profileId, callId))
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
            // Pause suppresses live scanning, not recordings the user explicitly
            // chose to play. Preserve replays and traffic from other scanners.
            removeLiveProfileMedia(profileId)
        } else {
            pausedProfiles -= profileId
        }
    }

    private fun syncCurrentlyPlayingCall() {
        val activeIds = (0 until player.mediaItemCount)
            .mapTo(mutableSetOf()) { index -> player.getMediaItemAt(index).mediaId }
        callByMediaId.keys.retainAll(activeIds)
        val mediaIds = List(player.mediaItemCount) { index ->
            player.getMediaItemAt(index).mediaId
        }
        _currentlyPlayingCall.value = PlaybackQueuePolicy
            .playingMediaId(mediaIds, player.currentMediaItemIndex, player.isPlaying)
            ?.let(callByMediaId::get)
        _queuedCalls.value = PlaybackQueuePolicy
            .queuedMediaIds(mediaIds, player.currentMediaItemIndex)
            .mapNotNull { mediaId ->
                callByMediaId[mediaId]?.let { call ->
                    QueuedCall(call, liveFeed = PlaybackQueuePolicy.mediaKind(mediaId) == "live")
                }
            }
        persistPlaybackQueue()
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

    private fun removeLiveProfileMedia(profileId: String) {
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (PlaybackQueuePolicy.isLiveCallForProfile(player.getMediaItemAt(index).mediaId, profileId)) {
                player.removeMediaItem(index)
            }
        }
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

    private fun currentPlaybackNotification(): PlaybackNotificationText {
        val metadata = player.currentMediaItem?.mediaMetadata
        return PlaybackNotificationPolicy.display(
            isPlaying = player.isPlaying,
            title = metadata?.title?.toString(),
            artist = metadata?.artist?.toString()
        )
    }

    private fun updatePlaybackNotification() {
        val display = currentPlaybackNotification()
        updateNotification(display.title, display.subtitle)
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
                    AndroidAutoLibraryPolicy.favoriteChannels(profileId, ScannerRepository.state.value)
                        .map { favorite -> playableItem(favorite.mediaId, favorite.title, favorite.subtitle) }
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
        private val _queuedCalls = MutableStateFlow<List<QueuedCall>>(emptyList())
        val queuedCalls: StateFlow<List<QueuedCall>> = _queuedCalls.asStateFlow()
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
        const val EXTRA_PLAY_IMMEDIATELY = "play_immediately"
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

        fun enqueue(
            context: Context,
            call: RadioCall,
            liveFeed: Boolean = true,
            playImmediately: Boolean = false
        ) {
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
                .putExtra(EXTRA_PLAY_IMMEDIATELY, playImmediately)
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

        fun clearQueue(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_CLEAR_QUEUE)
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
