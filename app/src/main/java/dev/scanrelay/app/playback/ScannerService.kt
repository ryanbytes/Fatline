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
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.scanrelay.app.MainActivity
import dev.scanrelay.app.R
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
import dev.scanrelay.app.ui.TagColors
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var lastSavedPlaybackQueue: SavedPlaybackQueue? = null
    private lateinit var volumeStore: PlaybackVolumeStore
    private var restoringQueue = true
    private var releasingPlayer = false
    // Repeated ACTION_ENQUEUE calls must not restart foreground notification
    // publication. These fields are only accessed by the service main thread.
    private var foregroundStarted = false
    private var lastPostedNotification: PlaybackNotificationText? = null
    private var lastPostedNotificationColor: Int? = null
    private var lastPostedNotificationMediaStyle: Boolean? = null
    // Only changed on the service main thread. Avoid reading SharedPreferences
    // whenever Media3 or scanner state emits a background update.
    private var monitoredProfileIds: Set<String> = emptySet()
    private val networkHandler = Handler(Looper.getMainLooper())
    private var currentNetworkHandle: Long? = null
    private val pausedProfiles = mutableSetOf<String>()
    private val callByMediaId = mutableMapOf<String, RadioCall>()
    private val playedLiveCallTracker = PlayedLiveCallTracker()
    // A short-lived check runs only until the current audio item has shown
    // real position movement; it does not poll while idle or already verified.
    private val playbackProgressHandler = Handler(Looper.getMainLooper())
    private val playbackProgressCheck = object : Runnable {
        override fun run() {
            if (!::player.isInitialized || !player.isPlaying) return
            val id = player.currentMediaItem?.mediaId
            playedLiveCallTracker.observedProgress(id, player.currentPosition)
            if (playedLiveCallTracker.awaitingProgress(id)) {
                playbackProgressHandler.postDelayed(this, 75L)
            }
        }
    }

    private fun checkPlaybackProgress() {
        playbackProgressHandler.removeCallbacks(playbackProgressCheck)
        if (!::player.isInitialized || !player.isPlaying) return
        val id = player.currentMediaItem?.mediaId
        playedLiveCallTracker.observedProgress(id, player.currentPosition)
        if (playedLiveCallTracker.awaitingProgress(id)) {
            playbackProgressHandler.postDelayed(playbackProgressCheck, 75L)
        }
    }
    // Keep recent accepted live IDs through socket reconnects. Bounded in memory.
    private val recentlyAcceptedLiveCalls = LinkedHashSet<CallKey>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val favoriteLibraryCache = AndroidAutoFavoritesCache()
    private var performanceProbe: PerformanceProbe? = null
    private var performanceJob: Job? = null

    private fun startPerformanceProbe(label: String) {
        if (activeProfileIds().isEmpty()) {
            _performanceCapture.value = PerformanceCaptureState(error = "Connect a scanner before measuring")
            return
        }
        stopPerformanceProbe()
        val probe = PerformanceProbe(label, PerformanceReader.read(this))
        performanceProbe = probe
        _performanceCapture.value = PerformanceCaptureState(running = true, label = label)
        performanceJob = serviceScope.launch {
            while (true) {
                delay(10_000L)
                // Collect snapshots on the service main thread; only emit the
                // completed report, avoiding periodic UI recompositions.
                performanceProbe?.sample(PerformanceReader.read(this@ScannerService))
            }
        }
    }

    private fun stopPerformanceProbe() {
        performanceJob?.cancel()
        performanceJob = null
        val probe = performanceProbe ?: return
        performanceProbe = null
        probe.sample(PerformanceReader.read(this))
        _performanceCapture.value = PerformanceCaptureState(report = probe.report(), label = probe.scenario)
    }

    private val networkLossCheck = Runnable {
        val active = connectivityManager.activeNetwork
        when (NetworkHandoffPolicy.transition(currentNetworkHandle, active?.networkHandle)) {
            NetworkHandoffTransition.NETWORK_LOST -> {
                currentNetworkHandle = null
                ScannerRepository.networkUnavailable()
                updatePlaybackNotification()
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
        monitoredProfileIds = activeProfileIds()
        playbackQueueStore = PlaybackQueueStore(this)
        volumeStore = PlaybackVolumeStore(this)
        _outputVolumePercent.value = volumeStore.percent()
        _audioEnabled.value = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ENABLED, true)
        weatherMonitor = NwsSevereWeatherMonitor(this).also { it.start() }
        startNetworkTracking()
        createChannel()
        player = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
            volume = PlaybackVolumePolicy.gain(_outputVolumePercent.value)
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
                    // The last call has no next-item transition; terminal END
                    // is its natural playback-completion signal.
                    if (playbackState == Player.STATE_ENDED) {
                        playedLiveCallTracker.observedProgress(
                            player.currentMediaItem?.mediaId, player.currentPosition
                        )
                        playedLiveCallTracker.ended()?.let(ScannerRepository::recordCompletedLiveCall)
                        playbackProgressHandler.removeCallbacks(playbackProgressCheck)
                    }
                    persistPlaybackQueue()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        val id = player.currentMediaItem?.mediaId
                        playedLiveCallTracker.started(
                            id, id?.let(callByMediaId::get), player.currentPosition
                        )
                    }
                    checkPlaybackProgress()
                    syncCurrentlyPlayingCall()
                    updatePlaybackNotification()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val nextId = mediaItem?.mediaId
                    val completed = playedLiveCallTracker.transitioned(
                        automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        nextMediaId = nextId,
                        nextCall = nextId?.let(callByMediaId::get),
                        nextPlaying = player.isPlaying,
                        nextPositionMs = player.currentPosition
                    )
                    completed?.let(ScannerRepository::recordCompletedLiveCall)
                    checkPlaybackProgress()
                    syncCurrentlyPlayingCall()
                    updatePlaybackNotification()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    // On a gapless automatic transition, the old item may have
                    // ended between progress checks. Use its final Media3 position.
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        playedLiveCallTracker.observedProgress(
                            oldPosition.mediaItem?.mediaId, oldPosition.positionMs
                        )
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    syncCurrentlyPlayingCall()
                    updatePlaybackNotification()
                }
            })
        }
        // Android 13+ media controls open the activity from the MediaSession,
        // not the NotificationCompat content intent alone.
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        restoreSavedPlaybackQueue()
        serviceScope.launch {
            ScannerRepository.state.collect { state ->
                refreshFavoriteLibraryChildren(state)
                // Show live scanning/connection status even while silent. The
                // duplicate-text guard avoids reposting on every new call.
                if (monitoredProfileIds.isNotEmpty()) updatePlaybackNotification()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /**
     * Media3's default notification manager can retract the foreground media
     * notification when its player is idle. That's the normal case for a live
     * scanner between calls. Own the notification throughout monitoring:
     * the scanner service, not the Media3 player, controls its lifecycle.
     *
     * Do not delegate to super (which uses the player-state-driven default)
     * and do not synthesize playback just to keep a card on the lock screen.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (foregroundStarted && ::player.isInitialized) updatePlaybackNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            val initialText = if (::player.isInitialized) currentPlaybackNotification()
                else PlaybackNotificationText("FatLine", "Scanner service active")
            val queued = if (::player.isInitialized) PlaybackQueuePolicy.queuedCount(
                player.mediaItemCount, player.currentMediaItemIndex
            ) else 0
            val subtitle = if (queued > 0) "${initialText.subtitle} · $queued queued" else initialText.subtitle
            val initialColor = currentPlayingTagColor()
            startForeground(NOTIFICATION_ID, notification(initialText.title, subtitle, initialColor))
            foregroundStarted = true
            lastPostedNotification = PlaybackNotificationText(initialText.title, subtitle)
            lastPostedNotificationColor = initialColor
            lastPostedNotificationMediaStyle = ScannerForegroundNotificationPolicy.usesMediaStyle(player.isPlaying)
        }
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::connectProfile)
            ACTION_RESUME_CONNECTIONS -> restoreConnections()
            ACTION_DISCONNECT -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::disconnectProfile)
            ACTION_DISCONNECT_ALL -> disconnectAll()
            ACTION_ENQUEUE -> addMediaFromIntent(intent)
            ACTION_START_PERFORMANCE_CAPTURE -> startPerformanceProbe(
                intent.getStringExtra(EXTRA_PERFORMANCE_SCENARIO).orEmpty().ifBlank { "General" }
            )
            ACTION_STOP_PERFORMANCE_CAPTURE -> stopPerformanceProbe()
            ACTION_SET_OUTPUT_VOLUME -> setOutputVolumeInternal(
                intent.getIntExtra(EXTRA_VOLUME_PERCENT, PlaybackVolumePolicy.DEFAULT_PERCENT)
            )
            ACTION_SET_PROFILE_PAUSED -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let { profileId ->
                setProfilePausedInternal(profileId, intent.getBooleanExtra(EXTRA_PAUSED, false))
            }
            ACTION_FILTER_PROFILE_MEDIA -> intent.getStringExtra(EXTRA_PROFILE_ID)?.let(::filterProfileMedia)
            ACTION_SET_AUDIO_ENABLED -> setAudioEnabledInternal(
                intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true)
            )
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
        stopPerformanceProbe()
        playbackProgressHandler.removeCallbacks(playbackProgressCheck)
        playedLiveCallTracker.cancel()
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
        val validIds = SessionRestorePolicy.valid(savedIds, profiles.keys)
        if (validIds != savedIds) persistActiveProfiles(validIds)
        pausedProfiles.clear()
        pausedProfiles.addAll(validIds.filter(pauseStore::isPaused))

        if (validIds.isEmpty()) {
            stopAudioInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Opening the Activity also sends RESUME when a foreground service is already
        // alive. Never reconnect an established session or disrupt its audio and alerts.
        SessionRestorePolicy.missing(validIds, ScannerRepository.state.value.servers.keys)
            .mapNotNull(profiles::get).forEach(ScannerRepository::connect)
        updateMonitoringNotification(validIds.size)
    }

    private fun activeProfileIds(): MutableSet<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_ACTIVE_PROFILES, emptySet())
            .orEmpty()
            .toMutableSet()

    private fun persistActiveProfiles(active: Set<String>) {
        monitoredProfileIds = active.toSet()
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (active.isEmpty()) editor.remove(KEY_ACTIVE_PROFILES)
        else editor.putStringSet(KEY_ACTIVE_PROFILES, active.toSet())
        editor.apply()
    }

    private fun updateMonitoringNotification(count: Int) {
        if (count > 0) updatePlaybackNotification()
    }

    private fun stopIfIdle() {
        if (activeProfileIds().isNotEmpty() || player.mediaItemCount > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        lastPostedNotification = null
        lastPostedNotificationColor = null
        lastPostedNotificationMediaStyle = null
        stopSelf()
    }

    private fun refreshFavoriteLibraryChildren(state: ScannerState) {
        // The main-thread cache itself decides which favorite configuration
        // changed. Most live call/queue/status updates require no allocations.
        favoriteLibraryCache.forEachChanged(state) { profileId, count ->
            session.notifyChildrenChanged("profile:$profileId", count, null)
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
        lastSavedPlaybackQueue = snapshot
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
            saveQueueSnapshot(null)
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
        if (snapshot.playWhenReady && _audioEnabled.value) player.play()
        restoringQueue = false
        syncCurrentlyPlayingCall()
    }

    private fun saveQueueSnapshot(snapshot: SavedPlaybackQueue?) {
        // Never debounce a changed queue, playback intent or recovery position.
        // Only repeated, byte-equivalent logical snapshots can skip disk writes.
        if (!PlaybackQueueJournalPolicy.shouldWrite(lastSavedPlaybackQueue, snapshot)) return
        playbackQueueStore.save(snapshot)
        lastSavedPlaybackQueue = snapshot
    }

    private fun persistPlaybackQueue() {
        if (restoringQueue || releasingPlayer || !::playbackQueueStore.isInitialized) return
        if (player.playbackState == Player.STATE_ENDED) {
            saveQueueSnapshot(null)
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
        saveQueueSnapshot(
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
        if (liveFeed && !_audioEnabled.value) {
            // Keep live scanner and transcript monitoring connected, but never
            // accumulate an unheard backlog during intentional audio Stop.
            pendingCalls.remove(token)
            return
        }
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
                mediaCount = player.mediaItemCount,
                mediaIdAt = { index -> player.getMediaItemAt(index).mediaId }
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
        // An explicit replay selection is also an intentional request to hear audio.
        if (!liveFeed && !_audioEnabled.value) setAudioEnabledInternal(true)
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
                playedLiveCallTracker.cancelIf(player.getMediaItemAt(index).mediaId)
                player.removeMediaItem(index)
            }
        }
    }
    private fun setAudioEnabledInternal(enabled: Boolean) {
        _audioEnabled.value = enabled
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_ENABLED, enabled).apply()
        if (enabled) {
            if (player.mediaItemCount > 0) player.play()
        } else {
            player.pause()
        }
        syncCurrentlyPlayingCall()
        updatePlaybackNotification()
    }

    private fun setOutputVolumeInternal(percent: Int) {
        val normalized = PlaybackVolumePolicy.clamp(percent)
        volumeStore.save(normalized)
        _outputVolumePercent.value = normalized
        player.volume = PlaybackVolumePolicy.gain(normalized)
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
        // Only one walk of Media3's timeline. Avoid constructing temporary
        // media-ID and queued-sublist arrays on each player callback.
        val projection = PlaybackQueueProjectionPolicy.project(
            mediaCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex,
            isPlaying = player.isPlaying,
            mediaIdAt = { index -> player.getMediaItemAt(index).mediaId },
            callsByMediaId = callByMediaId
        )
        callByMediaId.keys.retainAll(projection.activeMediaIds)
        _currentlyPlayingCall.value = projection.playingCall
        _queuedCalls.value = projection.queuedCalls
        persistPlaybackQueue()
    }

    private fun trimQueueForIncomingCall(liveFeed: Boolean) {
        val removeIndex = PlaybackQueuePolicy.removalIndex(
            mediaCount = player.mediaItemCount,
            currentIndex = player.currentMediaItemIndex,
            incomingLiveFeed = liveFeed,
            mediaIdAt = { index -> player.getMediaItemAt(index).mediaId }
        )
        if (removeIndex >= 0) player.removeMediaItem(removeIndex)
    }

    private fun skipInternal() {
        if (player.mediaItemCount <= 0) return
        val index = player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount } ?: 0
        playedLiveCallTracker.cancelIf(player.getMediaItemAt(index).mediaId)
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
        playbackProgressHandler.removeCallbacks(playbackProgressCheck)
        playedLiveCallTracker.cancel()
        player.stop()
        player.clearMediaItems()
        callByMediaId.clear()
        _currentlyPlayingCall.value = null
    }

    private fun removeLiveProfileMedia(profileId: String) {
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (PlaybackQueuePolicy.isLiveCallForProfile(player.getMediaItemAt(index).mediaId, profileId)) {
                playedLiveCallTracker.cancelIf(player.getMediaItemAt(index).mediaId)
                player.removeMediaItem(index)
            }
        }
    }

    private fun removeProfileMedia(profileId: String) {
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId.startsWith("call:$profileId:")) {
                playedLiveCallTracker.cancelIf(player.getMediaItemAt(index).mediaId)
                player.removeMediaItem(index)
            }
        }
    }

    private fun createChannel() {
        // Low-importance service notifications can collapse to a tiny icon on
        // Pixel's lock screen. A new, soundless DEFAULT channel requests a
        // visible card without pinging or vibrating on every call.
        val channel = NotificationChannel(CHANNEL_ID, "Scanner playback", NotificationManager.IMPORTANCE_DEFAULT)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.enableLights(false)
        // Background playback is ongoing, not an unread alert: it must not
        // produce a permanent home-screen launcher notification dot.
        channel.setShowBadge(false)
        // The user can still override lock-screen privacy in Android Settings.
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(title: String, text: String, tagColor: Int? = null): Notification {
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fatline_talkgroup)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(
                if (ScannerForegroundNotificationPolicy.usesMediaStyle(player.isPlaying)) NotificationCompat.CATEGORY_TRANSPORT
                else NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (ScannerForegroundNotificationPolicy.usesMediaStyle(player.isPlaying)) {
            // Real playback: let SystemUI render its media surface and buttons.
            builder.addAction(R.drawable.ic_fatline_skip, "Skip call", skip)
                .addAction(R.drawable.ic_fatline_clear, "Clear queue", clearQueue)
                .addAction(R.drawable.ic_fatline_stop, "Disconnect all", stop)
                .setStyle(
                    MediaStyleNotificationHelper.MediaStyle(session)
                        .setShowActionsInCompactView(0, 1)
                )
        } else {
            // Idle scanning, paused audio and reconnecting are NOT playback.
            // MediaStyle can vanish because Media3 has no active media item.
            // Show an ordinary ongoing high-visibility service notification,
            // with no media session token for SystemUI to suppress.
            builder.addAction(R.drawable.ic_fatline_stop, "Disconnect all", stop)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        // Android owns the final lock-screen card rendering and may restrict
        // background tint. Colorized foreground service notifications request
        // the tag color wherever the system supports it.
        if (tagColor != null) builder.setColor(tagColor).setColorized(true)
        return builder.build()
    }

    private fun currentPlayingTagColor(): Int? {
        if (!::player.isInitialized || !player.isPlaying) return null
        val call = player.currentMediaItem?.mediaId?.let(callByMediaId::get) ?: return null
        val server = ScannerRepository.state.value.servers[call.profileId]
        val rgb = TagColors.playingCallColor(call, server) ?: return null
        return android.graphics.Color.rgb(rgb.red, rgb.green, rgb.blue)
    }

    private fun currentPlaybackNotification(): PlaybackNotificationText {
        val metadata = player.currentMediaItem?.mediaMetadata
        val playing = PlaybackNotificationPolicy.display(
            isPlaying = player.isPlaying,
            title = metadata?.title?.toString(),
            artist = metadata?.artist?.toString()
        )
        return ScannerLockScreenPolicy.display(
            activeProfiles = monitoredProfileIds,
            state = ScannerRepository.state.value,
            pausedProfiles = pausedProfiles,
            playing = playing,
            isPlaying = player.isPlaying
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
        val current = PlaybackNotificationText(title, queueText)
        val color = currentPlayingTagColor()
        val mediaStyle = ScannerForegroundNotificationPolicy.usesMediaStyle(player.isPlaying)
        if (!foregroundStarted ||
            !ScannerForegroundNotificationPolicy.needsUpdate(
                lastPostedNotification, lastPostedNotificationColor, lastPostedNotificationMediaStyle,
                current, color, mediaStyle
            )
        ) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(title, queueText, color))
        lastPostedNotification = current
        lastPostedNotificationColor = color
        lastPostedNotificationMediaStyle = mediaStyle
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
        private val _audioEnabled = MutableStateFlow(true)
        val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()
        private val _outputVolumePercent = MutableStateFlow(PlaybackVolumePolicy.DEFAULT_PERCENT)
        val outputVolumePercent: StateFlow<Int> = _outputVolumePercent.asStateFlow()
        private val _queuedCallCount = MutableStateFlow(0)
        val queuedCallCount: StateFlow<Int> = _queuedCallCount.asStateFlow()
        private val _currentlyPlayingCall = MutableStateFlow<RadioCall?>(null)
        val currentlyPlayingCall: StateFlow<RadioCall?> = _currentlyPlayingCall.asStateFlow()
        private val _queuedCalls = MutableStateFlow<List<QueuedCall>>(emptyList())
        val queuedCalls: StateFlow<List<QueuedCall>> = _queuedCalls.asStateFlow()
        private val pendingCalls = ConcurrentHashMap<String, RadioCall>()
        private val _performanceCapture = MutableStateFlow(PerformanceCaptureState())
        val performanceCapture: StateFlow<PerformanceCaptureState> = _performanceCapture.asStateFlow()

        // Channel configuration is immutable after Android creates it. Use a new ID
        // so existing installations inherit showBadge=false on the media channel.
        private const val CHANNEL_ID = "fatline_scanner_media_card_no_badge_v3"
        private const val NOTIFICATION_ID = 8101
        private const val PREFS = "fatline_session"
        private const val KEY_ACTIVE_PROFILES = "active_profiles"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val ROOT_ID = "fatline_root"
        private const val NETWORK_LOSS_GRACE_MS = 650L

        @Volatile private var suppressRepositoryServiceCallbacks = false

        const val ACTION_RESUME_CONNECTIONS = "dev.scanrelay.RESUME_CONNECTIONS"
        const val ACTION_CONNECT = "dev.scanrelay.CONNECT"
        const val ACTION_DISCONNECT = "dev.scanrelay.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.scanrelay.DISCONNECT_ALL"
        const val ACTION_ENQUEUE = "dev.scanrelay.ENQUEUE"
        const val ACTION_SET_PROFILE_PAUSED = "dev.scanrelay.SET_PROFILE_PAUSED"
        const val ACTION_SET_OUTPUT_VOLUME = "dev.scanrelay.SET_OUTPUT_VOLUME"
        const val EXTRA_VOLUME_PERCENT = "volume_percent"
        const val ACTION_FILTER_PROFILE_MEDIA = "dev.scanrelay.FILTER_PROFILE_MEDIA"
        const val ACTION_SET_AUDIO_ENABLED = "dev.scanrelay.SET_AUDIO_ENABLED"
        const val ACTION_SKIP = "dev.scanrelay.SKIP"
        const val ACTION_CLEAR_QUEUE = "dev.scanrelay.CLEAR_QUEUE"
        const val ACTION_STOP_AUDIO = "dev.scanrelay.STOP_AUDIO"
        const val ACTION_REMOVE_PROFILE = "dev.scanrelay.REMOVE_PROFILE"
        const val ACTION_START_PERFORMANCE_CAPTURE = "dev.scanrelay.START_PERFORMANCE_CAPTURE"
        const val ACTION_STOP_PERFORMANCE_CAPTURE = "dev.scanrelay.STOP_PERFORMANCE_CAPTURE"
        const val EXTRA_PERFORMANCE_SCENARIO = "performance_scenario"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_SYSTEM_REF = "system_ref"
        const val EXTRA_TALKGROUP_REF = "talkgroup_ref"
        const val EXTRA_LIVE_FEED = "live_feed"
        const val EXTRA_PLAY_IMMEDIATELY = "play_immediately"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_AUDIO_ENABLED = "audio_enabled"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_CALL_TOKEN = "call_token"

        /** Open the user's existing foreground scanner notification channel. */
        fun lockScreenNotificationSettingsIntent(context: Context): Intent {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            val intent = if (channel != null) {
                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            } else {
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            return intent
        }

        /** FGS visibility does not mean lock-screen visibility was allowed. */
        fun lockScreenNotificationStatus(context: Context): String {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            return ScannerLockScreenAvailability.description(
                appNotificationsEnabled = manager.areNotificationsEnabled(),
                channelEnabled = channel?.importance?.let { it != NotificationManager.IMPORTANCE_NONE }
            )
        }

        /**
         * Called only from a visible Activity. Android may not restart a media-playback
         * foreground service after process death, so recover sessions on next app launch.
         * This deliberately does not run from BOOT_COMPLETED or after explicit disconnect.
         */
        fun resumeActiveConnections(context: Context) {
            val active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_ACTIVE_PROFILES, emptySet()).orEmpty()
            if (active.isEmpty()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScannerService::class.java).setAction(ACTION_RESUME_CONNECTIONS)
            )
        }

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
            PendingAudioDispatchPolicy.dispatch(
                tryStart = { context.startService(intent) },
                tryForegroundStart = { ContextCompat.startForegroundService(context, intent) },
                onUnrecoverableFailure = { pendingCalls.remove(token) }
            )
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

        /** Explicitly requested, in-process sample capture; no telemetry upload. */
        fun startPerformanceCapture(context: Context, scenario: String) {
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_START_PERFORMANCE_CAPTURE)
                .putExtra(EXTRA_PERFORMANCE_SCENARIO, scenario)
            runCatching { context.startService(intent) }
                .onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopPerformanceCapture(context: Context) {
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_STOP_PERFORMANCE_CAPTURE)
            runCatching { context.startService(intent) }
                .onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun skip(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_SKIP)
            runCatching { context.startService(intent) }.onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun setOutputVolume(context: Context, percent: Int) {
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_SET_OUTPUT_VOLUME)
                .putExtra(EXTRA_VOLUME_PERCENT, PlaybackVolumePolicy.clamp(percent))
            runCatching { context.startService(intent) }
                .onFailure { ContextCompat.startForegroundService(context, intent) }
        }

        fun setAudioEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, ScannerService::class.java)
                .setAction(ACTION_SET_AUDIO_ENABLED)
                .putExtra(EXTRA_AUDIO_ENABLED, enabled)
            runCatching { context.startService(intent) }
                .onFailure { ContextCompat.startForegroundService(context, intent) }
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
