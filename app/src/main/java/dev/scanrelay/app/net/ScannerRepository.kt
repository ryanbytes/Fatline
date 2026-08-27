package dev.scanrelay.app.net

import android.content.Context
import dev.scanrelay.app.alerts.AlertNotifier
import dev.scanrelay.app.data.ChannelStore
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScannerAlert
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.playback.ScannerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

object ScannerRepository {
    private class Session(val profile: ServerProfile) {
        @Volatile var state = ServerScannerState(profile = profile, status = ConnectionStatus.CONNECTING, statusText = "Connecting")
        @Volatile var socket: ThinLineSocket? = null
        @Volatile var socketGeneration = 0L
        @Volatile var reconnectJob: Job? = null
        @Volatile var handshakeJob: Job? = null
        @Volatile var reconnectAttempt = 0
        @Volatile var stopped = false
        @Volatile var masterKey: ByteArray? = null
        @Volatile var keyJob: Job? = null
        @Volatile var relayUrl: String? = null
        @Volatile var clientToken: String? = null
        var historyOffset = 0
        val pendingEncrypted = ArrayDeque<JSONObject>()
        val pendingReplay = mutableSetOf<Long>()
        val callMutex = Mutex()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val keyHttpClient = OkHttpClient()
    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var channelStore: ChannelStore? = null
    @Volatile private var networkAvailable = true

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
                channelStore = ChannelStore(context.applicationContext)
            }
        }
    }

    @Synchronized
    fun connect(profile: ServerProfile) {
        require(profile.baseUrl.isNotBlank()) { "Server URL is required" }
        sessions.remove(profile.id)?.let(::stopSession)
        val session = Session(profile)
        sessions[profile.id] = session
        publish()
        openSocket(session)
    }

    @Synchronized
    fun disconnect(profileId: String) {
        sessions.remove(profileId)?.let(::stopSession)
        publish()
    }

    @Synchronized
    fun disconnectAll() {
        val old = sessions.values.toList()
        sessions.clear()
        old.forEach(::stopSession)
        publish()
    }

    fun setTalkgroupEnabled(profileId: String, systemRef: Long, talkgroupRef: Long, enabled: Boolean) {
        val session = sessions[profileId] ?: return
        val key = ChannelKey(systemRef, talkgroupRef)
        channelStore?.setEnabled(profileId, key, enabled)
        synchronized(session) {
            val systems = session.state.systems.map { system ->
                if (system.systemRef != systemRef) system
                else system.copy(talkgroups = system.talkgroups.map { tg ->
                    if (tg.talkgroupRef == talkgroupRef) tg.copy(enabled = enabled) else tg
                })
            }
            session.state = session.state.copy(systems = systems)
            if (!session.state.paused) session.socket?.sendLivefeed(systems)
        }
        publish()
    }

    fun setSystemEnabled(profileId: String, systemRef: Long, enabled: Boolean) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val target = session.state.systems.firstOrNull { it.systemRef == systemRef } ?: return
            val keys = target.talkgroups.map { it.key }
            channelStore?.setMany(profileId, keys, enabled)
            val systems = session.state.systems.map { system ->
                if (system.systemRef != systemRef) system
                else system.copy(talkgroups = system.talkgroups.map { it.copy(enabled = enabled) })
            }
            session.state = session.state.copy(systems = systems)
            if (!session.state.paused) session.socket?.sendLivefeed(systems)
        }
        publish()
    }

    fun setAllEnabled(profileId: String, enabled: Boolean) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val all = session.state.systems.flatMap { it.talkgroups }.map { it.key }.toSet()
            channelStore?.setAll(profileId, all, enabled)
            val systems = session.state.systems.map { system ->
                system.copy(talkgroups = system.talkgroups.map { it.copy(enabled = enabled) })
            }
            session.state = session.state.copy(systems = systems)
            if (!session.state.paused) session.socket?.sendLivefeed(systems)
        }
        publish()
    }

    fun setPaused(profileId: String, paused: Boolean) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            if (session.state.paused == paused) return
            session.state = session.state.copy(
                paused = paused,
                statusText = when {
                    session.state.status != ConnectionStatus.CONNECTED -> session.state.statusText
                    paused -> "Connected — paused"
                    else -> "Connected"
                }
            )
            if (session.state.status == ConnectionStatus.CONNECTED) {
                if (paused) session.socket?.stopLivefeed()
                else session.socket?.sendLivefeed(session.state.systems)
            }
        }
        publish()
    }

    fun setFavorite(profileId: String, systemRef: Long, talkgroupRef: Long, favorite: Boolean) {
        val session = sessions[profileId] ?: return
        val key = ChannelKey(systemRef, talkgroupRef)
        channelStore?.setFavorite(profileId, key, favorite)
        synchronized(session) {
            session.state = session.state.copy(
                systems = session.state.systems.map { system ->
                    if (system.systemRef != systemRef) system
                    else system.copy(talkgroups = system.talkgroups.map { tg ->
                        if (tg.talkgroupRef == talkgroupRef) tg.copy(favorite = favorite) else tg
                    })
                }
            )
        }
        publish()
    }

    fun setHold(profileId: String, key: ChannelKey?) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            session.state = session.state.copy(
                hold = key,
                holdSystemRef = if (key != null) null else session.state.holdSystemRef
            )
        }
        publish()
    }

    fun setSystemHold(profileId: String, systemRef: Long?) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            session.state = session.state.copy(
                holdSystemRef = systemRef,
                hold = if (systemRef != null) null else session.state.hold
            )
        }
        publish()
    }

    fun clearHold(profileId: String) {
        val session = sessions[profileId] ?: return
        synchronized(session) { session.state = session.state.copy(hold = null, holdSystemRef = null) }
        publish()
    }

    fun avoid(profileId: String, key: ChannelKey, avoided: Boolean = true) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val set = session.state.avoided.toMutableSet()
            if (avoided) set += key else set -= key
            session.state = session.state.copy(avoided = set)
        }
        publish()
    }

    fun clearAvoids(profileId: String) {
        val session = sessions[profileId] ?: return
        synchronized(session) { session.state = session.state.copy(avoided = emptySet()) }
        publish()
    }

    fun requestHistory(profileId: String, reset: Boolean = true, systemRef: Long? = null, talkgroupRef: Long? = null) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            if (reset) {
                session.historyOffset = 0
                session.state = session.state.copy(history = emptyList(), historyHasMore = false)
            }
            session.socket?.requestHistory(
                limit = 100,
                offset = session.historyOffset,
                systemRef = systemRef,
                talkgroups = talkgroupRef?.let(::listOf).orEmpty()
            )
        }
        publish()
    }

    fun replay(profileId: String, callId: Long) {
        val session = sessions[profileId] ?: return
        val existing = session.state.history.firstOrNull { it.id == callId }
        val path = existing?.audioPath
        if (path != null && File(path).isFile) {
            appContext?.let { ScannerService.enqueue(it, existing) }
            return
        }
        synchronized(session) { session.pendingReplay += callId }
        session.socket?.requestCall(callId)
    }

    fun skip() {
        appContext?.let(ScannerService::skip)
    }

    fun networkUnavailable() {
        networkAvailable = false
        sessions.values.forEach { session ->
            val oldSocket = synchronized(session) {
                if (!isCurrent(session)) return@forEach
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.handshakeJob?.cancel()
                session.handshakeJob = null
                session.socketGeneration++
                val old = session.socket
                session.socket = null
                session.state = session.state.copy(
                    status = ConnectionStatus.CONNECTING,
                    statusText = "Waiting for network",
                    error = null
                )
                old
            }
            oldSocket?.abort()
        }
        publish()
    }

    fun networkChanged(reason: String = "Network changed") {
        networkAvailable = true
        sessions.values.forEach { restartSession(it, "$reason; reconnecting", resetBackoff = true) }
    }

    fun connectedProfiles(): List<ServerProfile> = sessions.values.map { it.profile }.sortedBy { it.name.lowercase() }

    private fun stopSession(session: Session) {
        session.stopped = true
        session.reconnectJob?.cancel()
        session.handshakeJob?.cancel()
        session.keyJob?.cancel()
        session.socketGeneration++
        val old = session.socket
        session.socket = null
        old?.close()
        synchronized(session) {
            session.pendingEncrypted.clear()
            session.pendingReplay.clear()
            session.masterKey?.fill(0)
            session.masterKey = null
            session.keyJob = null
            session.handshakeJob = null
            session.relayUrl = null
            session.clientToken = null
        }
    }

    private fun openSocket(session: Session) {
        if (!isCurrent(session)) return
        if (!networkAvailable) {
            synchronized(session) {
                session.state = session.state.copy(status = ConnectionStatus.CONNECTING, statusText = "Waiting for network", error = null)
            }
            publish()
            return
        }

        val generation = synchronized(session) {
            session.socketGeneration++
            session.state = session.state.copy(
                status = ConnectionStatus.CONNECTING,
                statusText = "Connecting to ${session.profile.name}",
                error = null
            )
            session.socketGeneration
        }
        publish()

        val socket = ThinLineSocket(session.profile, object : ThinLineSocket.Listener {
            override fun onOpen() {
                if (!isCurrent(session, generation)) return
                synchronized(session) {
                    session.state = session.state.copy(
                        status = ConnectionStatus.CONNECTING,
                        statusText = "Connected; negotiating",
                        error = null
                    )
                }
                publish()
                armHandshakeWatchdog(session, generation, authSubmitted = false)
            }

            override fun onSavedPinSubmitted() {
                if (!isCurrent(session, generation)) return
                synchronized(session) {
                    session.state = session.state.copy(
                        status = ConnectionStatus.CONNECTING,
                        statusText = "Authenticating",
                        error = null
                    )
                }
                publish()
                armHandshakeWatchdog(session, generation, authSubmitted = true)
            }

            override fun onEnvelope(envelope: ThinLineProtocol.Envelope) {
                if (isCurrent(session, generation)) handleEnvelope(session, envelope, generation)
            }

            override fun onFailure(message: String, cause: Throwable?) {
                if (!isCurrent(session, generation)) return
                synchronized(session) {
                    session.handshakeJob?.cancel()
                    session.handshakeJob = null
                    session.socket = null
                    session.state = session.state.copy(
                        status = if (networkAvailable) ConnectionStatus.ERROR else ConnectionStatus.CONNECTING,
                        statusText = if (networkAvailable) "Connection lost" else "Waiting for network",
                        error = if (networkAvailable) message else null
                    )
                }
                publish()
                scheduleReconnect(session, generation)
            }

            override fun onClosed(reason: String) {
                if (!isCurrent(session, generation)) return
                synchronized(session) {
                    session.handshakeJob?.cancel()
                    session.handshakeJob = null
                    session.socket = null
                    session.state = session.state.copy(
                        status = ConnectionStatus.CONNECTING,
                        statusText = if (networkAvailable) "Disconnected; reconnecting" else "Waiting for network",
                        error = if (networkAvailable) reason else null
                    )
                }
                publish()
                scheduleReconnect(session, generation)
            }
        })

        synchronized(session) {
            if (!isCurrent(session, generation)) return
            session.socket = socket
        }

        runCatching { socket.connect() }.onFailure { error ->
            if (!isCurrent(session, generation)) return@onFailure
            synchronized(session) {
                session.socket = null
                session.state = session.state.copy(
                    status = if (networkAvailable) ConnectionStatus.ERROR else ConnectionStatus.CONNECTING,
                    statusText = if (networkAvailable) "Connection failed" else "Waiting for network",
                    error = if (networkAvailable) error.message else null
                )
            }
            publish()
            scheduleReconnect(session, generation)
        }
    }

    private fun armHandshakeWatchdog(session: Session, generation: Long, authSubmitted: Boolean) {
        synchronized(session) {
            session.handshakeJob?.cancel()
            session.handshakeJob = scope.launch {
                delay(if (authSubmitted) 5_000L else 3_000L)
                if (!isCurrent(session, generation)) return@launch
                val status = session.state.status
                if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.AUTH_REQUIRED) return@launch

                session.socket?.requestConfig()
                synchronized(session) {
                    if (isCurrent(session, generation)) {
                        session.state = session.state.copy(statusText = "Negotiating; retrying config")
                    }
                }
                publish()

                delay(4_000L)
                if (!isCurrent(session, generation)) return@launch
                val currentStatus = session.state.status
                if (currentStatus != ConnectionStatus.CONNECTED && currentStatus != ConnectionStatus.AUTH_REQUIRED) {
                    restartSession(session, "Handshake stalled; reconnecting", resetBackoff = false)
                }
            }
        }
    }

    private fun scheduleReconnect(session: Session, generation: Long) {
        synchronized(session) {
            if (!isCurrent(session, generation) || session.reconnectJob?.isActive == true) return
            if (!networkAvailable) {
                session.state = session.state.copy(status = ConnectionStatus.CONNECTING, statusText = "Waiting for network", error = null)
                publish()
                return
            }
            session.reconnectAttempt = (session.reconnectAttempt + 1).coerceAtMost(6)
            val baseDelay = (1_000L shl (session.reconnectAttempt - 1)).coerceAtMost(30_000L)
            val jitter = session.profile.id.hashCode().toLong().absoluteValue % 350L
            session.reconnectJob = scope.launch {
                delay(baseDelay + jitter)
                synchronized(session) { session.reconnectJob = null }
                if (isCurrent(session, generation) && networkAvailable) openSocket(session)
            }
        }
    }

    private fun restartSession(session: Session, reason: String, resetBackoff: Boolean) {
        if (!isCurrent(session)) return
        val oldSocket = synchronized(session) {
            if (!isCurrent(session)) return
            session.reconnectJob?.cancel()
            session.reconnectJob = null
            session.handshakeJob?.cancel()
            session.handshakeJob = null
            session.socketGeneration++
            if (resetBackoff) session.reconnectAttempt = 0
            val old = session.socket
            session.socket = null
            session.state = session.state.copy(
                status = ConnectionStatus.CONNECTING,
                statusText = reason,
                error = null
            )
            old
        }
        publish()
        oldSocket?.abort()
        if (networkAvailable && isCurrent(session)) openSocket(session)
    }

    private fun isCurrent(session: Session): Boolean = !session.stopped && sessions[session.profile.id] === session

    private fun isCurrent(session: Session, generation: Long): Boolean =
        isCurrent(session) && session.socketGeneration == generation

    private fun handleEnvelope(session: Session, envelope: ThinLineProtocol.Envelope, generation: Long) {
        when (envelope.command) {
            ThinLineProtocol.VERSION -> {
                val version = (envelope.payload as? JSONObject)?.optString("version")?.takeIf { it.isNotBlank() }
                synchronized(session) { session.state = session.state.copy(serverVersion = version) }
                publish()
            }
            ThinLineProtocol.PIN -> {
                synchronized(session) {
                    session.handshakeJob?.cancel()
                    session.handshakeJob = null
                    val attempted = session.profile.pin
                    session.state = session.state.copy(
                        status = ConnectionStatus.AUTH_REQUIRED,
                        statusText = if (attempted.isBlank()) "PIN required" else "PIN rejected",
                        error = if (attempted.isBlank()) null else "Server rejected the saved PIN"
                    )
                }
                publish()
            }
            ThinLineProtocol.CONFIG -> {
                synchronized(session) {
                    session.handshakeJob?.cancel()
                    session.handshakeJob = null
                    session.reconnectAttempt = 0
                }
                handleConfig(session, envelope.payload as? JSONObject ?: return)
            }
            ThinLineProtocol.CALL -> (envelope.payload as? JSONObject)?.let { payload ->
                scope.launch { session.callMutex.withLock { processCall(session, payload, generation) } }
            }
            ThinLineProtocol.LIST_CALL -> handleHistory(session, envelope.payload as? JSONObject ?: return)
            ThinLineProtocol.ALERT -> handleAlert(session, envelope.payload)
            ThinLineProtocol.ERROR -> {
                synchronized(session) { session.state = session.state.copy(error = envelope.payload?.toString() ?: "Server error") }
                publish()
            }
            ThinLineProtocol.EXPIRED -> {
                synchronized(session) {
                    session.handshakeJob?.cancel()
                    session.handshakeJob = null
                    session.state = session.state.copy(
                        status = ConnectionStatus.AUTH_REQUIRED,
                        statusText = "PIN expired",
                        error = "The server reports that this PIN has expired"
                    )
                }
                publish()
            }
            ThinLineProtocol.MAX -> {
                synchronized(session) {
                    session.handshakeJob?.cancel()
                    session.handshakeJob = null
                    session.state = session.state.copy(
                        status = ConnectionStatus.ERROR,
                        statusText = "Connection limit reached",
                        error = "Server connection limit: ${envelope.payload}"
                    )
                }
                publish()
            }
        }
    }

    private fun handleConfig(session: Session, payload: JSONObject) {
        val parsed = ThinLineProtocol.parseSystems(payload)
        val systems = channelStore?.apply(session.profile.id, parsed) ?: parsed
        val options = payload.optJSONObject("options")
        val encrypted = options?.optBoolean("audioEncryptionEnabled", false) == true
        val relayUrl = options?.optString("relayServerURL")?.takeIf { it.isNotBlank() }
        val token = options?.optString("audioClientToken")?.takeIf { it.isNotBlank() }
        var needsKeyExchange = false

        synchronized(session) {
            val detailsChanged = session.relayUrl != relayUrl || session.clientToken != token
            if (!encrypted || detailsChanged) {
                session.keyJob?.cancel()
                session.keyJob = null
                session.masterKey?.fill(0)
                session.masterKey = null
            }
            if (!encrypted) session.pendingEncrypted.clear()

            session.relayUrl = relayUrl
            session.clientToken = token
            needsKeyExchange = encrypted && session.masterKey == null
            session.state = session.state.copy(
                status = ConnectionStatus.CONNECTED,
                statusText = when {
                    session.state.paused -> "Connected — paused"
                    needsKeyExchange -> "Connected — securing audio"
                    else -> "Connected"
                },
                systems = systems,
                audioEncryptionEnabled = encrypted,
                encryptionReady = !encrypted || session.masterKey != null,
                error = null
            )
            if (session.state.paused) session.socket?.stopLivefeed()
            else session.socket?.sendLivefeed(systems)
        }
        publish()
        if (needsKeyExchange) startKeyExchange(session)
    }

    private fun startKeyExchange(session: Session) {
        val relay: String
        val token: String
        synchronized(session) {
            if (session.keyJob?.isActive == true || session.masterKey != null) return
            val currentRelay = session.relayUrl
            val currentToken = session.clientToken
            if (currentRelay.isNullOrBlank() || currentToken.isNullOrBlank()) {
                session.state = session.state.copy(
                    statusText = "Connected — encrypted audio unavailable",
                    encryptionReady = false,
                    error = "Server did not provide relay key-exchange details"
                )
                publish()
                return
            }
            relay = currentRelay
            token = currentToken
            session.keyJob = scope.launch {
                try {
                    val key = AudioCrypto(keyHttpClient).fetchMasterKey(relay, token)
                    if (!isCurrent(session) || session.relayUrl != relay || session.clientToken != token) {
                        key.fill(0)
                        return@launch
                    }

                    session.callMutex.withLock {
                        if (!isCurrent(session) || session.relayUrl != relay || session.clientToken != token) {
                            key.fill(0)
                            return@withLock
                        }
                        val buffered: List<JSONObject>
                        synchronized(session) {
                            session.masterKey = key
                            session.state = session.state.copy(
                                statusText = if (session.state.paused) "Connected — paused" else "Connected",
                                encryptionReady = true,
                                error = null
                            )
                            buffered = session.pendingEncrypted.toList()
                            session.pendingEncrypted.clear()
                            session.keyJob = null
                        }
                        publish()
                        buffered.forEach { processCall(session, it) }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session) || session.relayUrl != relay || session.clientToken != token) return@launch
                    synchronized(session) {
                        session.state = session.state.copy(
                            statusText = "Connected — encrypted audio unavailable",
                            encryptionReady = false,
                            error = error.message ?: "Audio key exchange failed"
                        )
                        session.keyJob = null
                    }
                    publish()
                }
            }
        }
    }

    private fun handleHistory(session: Session, payload: JSONObject) {
        val results = payload.optJSONArray("results") ?: JSONArray()
        val calls = buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                add(metadataCall(session, item))
            }
        }
        synchronized(session) {
            val merged = (session.state.history + calls).associateBy { it.id }.values.sortedByDescending(::callSortKey).take(500)
            session.historyOffset += calls.size
            session.state = session.state.copy(history = merged, historyHasMore = payload.optBoolean("hasMore", false))
        }
        publish()
    }

    private fun metadataCall(session: Session, payload: JSONObject): RadioCall {
        val systemRef = payload.optLong("system")
        val talkgroupRef = payload.optLong("talkgroup")
        val (systemLabel, talkgroupLabel) = labels(session.state.systems, systemRef, talkgroupRef)
        return RadioCall(
            profileId = session.profile.id,
            serverName = session.profile.name,
            id = payload.optLong("id"),
            systemRef = systemRef,
            talkgroupRef = talkgroupRef,
            systemLabel = systemLabel,
            talkgroupLabel = talkgroupLabel,
            dateTime = payload.optString("dateTime"),
            sourceRef = payload.optLong("source").takeIf { it > 0 },
            frequency = payload.optLong("frequency").takeIf { it > 0 },
            durationSeconds = payload.optDouble("duration").takeIf { !it.isNaN() && it > 0 }
        )
    }

    private fun processCall(session: Session, payload: JSONObject, generation: Long? = null) {
        if (generation != null) {
            if (!isCurrent(session, generation)) return
        } else if (!isCurrent(session)) return

        val context = appContext ?: return
        val id = payload.optLong("id")
        val systemRef = payload.optLong("system")
        val talkgroupRef = payload.optLong("talkgroup")
        val key = ChannelKey(systemRef, talkgroupRef)
        val audio = payload.optJSONObject("audio")
        val encrypted = audio?.optString("type") == "EncryptedBuffer" || payload.optString("audioType") == "EncryptedAES256GCM"

        val audioBytes = if (encrypted) {
            val data = audio?.optString("data").orEmpty()
            if (data.isBlank()) {
                synchronized(session) { session.state = session.state.copy(error = "Encrypted audio payload is missing ciphertext") }
                publish()
                return
            }

            var decrypted: ByteArray? = null
            var needsKeyExchange = false
            synchronized(session) {
                val master = session.masterKey
                if (master == null) {
                    bufferEncryptedCallLocked(session, payload)
                    needsKeyExchange = true
                } else {
                    try {
                        decrypted = AudioCrypto().decryptCall(master, data)
                    } catch (error: Throwable) {
                        if (session.masterKey === master) {
                            session.masterKey = null
                            master.fill(0)
                        }
                        session.state = session.state.copy(
                            statusText = "Connected — refreshing audio key",
                            encryptionReady = false,
                            error = "Audio decrypt failed; refreshing key: ${error.message ?: "authentication failed"}"
                        )
                        bufferEncryptedCallLocked(session, payload)
                        needsKeyExchange = true
                    }
                }
            }
            if (needsKeyExchange) {
                publish()
                startKeyExchange(session)
                return
            }
            decrypted ?: return
        } else {
            decodeBuffer(audio?.opt("data"))
        }

        val mime = payload.optString("audioType").takeIf { it.isNotBlank() && it != "EncryptedAES256GCM" }
        val audioName = payload.optString("audioName").takeIf { it.isNotBlank() }
        val path = if (audioBytes.isNotEmpty()) writeAudio(context, session.profile.id, id, audioName, mime, audioBytes) else null
        val (systemLabel, talkgroupLabel) = labels(session.state.systems, systemRef, talkgroupRef)
        val call = RadioCall(
            profileId = session.profile.id,
            serverName = session.profile.name,
            id = id,
            systemRef = systemRef,
            talkgroupRef = talkgroupRef,
            systemLabel = systemLabel,
            talkgroupLabel = talkgroupLabel,
            dateTime = payload.optString("dateTime"),
            transcript = payload.optString("transcript").takeIf { it.isNotBlank() },
            audioPath = path,
            audioMime = mime,
            audioName = audioName,
            sourceRef = payload.optLong("source").takeIf { it > 0 },
            frequency = payload.optLong("frequency").takeIf { it > 0 },
            encryptedAudio = encrypted
        )

        val shouldPlay: Boolean
        synchronized(session) {
            val replayRequested = session.pendingReplay.remove(id)
            val enabled = session.state.systems.flatMap { it.talkgroups }.firstOrNull { it.key == key }?.enabled == true
            val talkgroupHoldAllows = session.state.hold?.let { it == key } ?: true
            val systemHoldAllows = session.state.holdSystemRef?.let { it == systemRef } ?: true
            val avoided = key in session.state.avoided
            shouldPlay = replayRequested || (!session.state.paused && enabled && talkgroupHoldAllows && systemHoldAllows && !avoided)
            val merged = (session.state.history + call).associateBy { it.id }.values.sortedByDescending(::callSortKey).take(500)
            session.state = session.state.copy(history = merged, lastCall = call)
        }
        publish()
        if (shouldPlay && path != null) ScannerService.enqueue(context, call)
    }

    private fun bufferEncryptedCallLocked(session: Session, payload: JSONObject) {
        if (session.pendingEncrypted.size >= 20) session.pendingEncrypted.removeFirst()
        session.pendingEncrypted.addLast(JSONObject(payload.toString()))
    }

    private fun handleAlert(session: Session, raw: Any?) {
        val payload = raw as? JSONObject
        val title = payload?.optString("title")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("type")?.takeIf { it.isNotBlank() }
            ?: "Scanner alert"
        val body = payload?.optString("message")?.takeIf { it.isNotBlank() }
            ?: payload?.optString("summary")?.takeIf { it.isNotBlank() }
            ?: payload?.toString()
            ?: raw?.toString().orEmpty().ifBlank { "Alert received" }
        val alert = ScannerAlert(session.profile.id, session.profile.name, title, body, payload?.optString("dateTime"))
        synchronized(session) {
            session.state = session.state.copy(alerts = (listOf(alert) + session.state.alerts).take(100))
        }
        publish()
        appContext?.let {
            val notificationId = (session.profile.id.hashCode() * 31 + body.hashCode()).absoluteValue
            AlertNotifier.post(it, "${session.profile.name}: $title", body, notificationId)
        }
    }

    private fun decodeBuffer(raw: Any?): ByteArray {
        val array = raw as? JSONArray ?: return byteArrayOf()
        return ByteArray(array.length()) { index -> (array.optInt(index) and 0xff).toByte() }
    }

    private fun writeAudio(context: Context, profileId: String, callId: Long, audioName: String?, mime: String?, bytes: ByteArray): String {
        val extension = audioName?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?: when (mime?.lowercase()) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/ogg" -> "ogg"
                else -> "bin"
            }
        val safeProfile = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.cacheDir, "fatline_audio/$safeProfile").apply { mkdirs() }
        val file = File(dir, "$callId-${System.nanoTime()}.$extension")
        file.writeBytes(bytes)
        pruneCache(dir)
        return file.absolutePath
    }

    private fun pruneCache(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(150).forEach { it.delete() }
    }

    private fun labels(systems: List<SystemConfig>, systemRef: Long, talkgroupRef: Long): Pair<String, String> {
        val system = systems.firstOrNull { it.systemRef == systemRef }
        val talkgroup = system?.talkgroups?.firstOrNull { it.talkgroupRef == talkgroupRef }
        return (system?.label ?: "System $systemRef") to (talkgroup?.displayName ?: "TG $talkgroupRef")
    }

    private fun callSortKey(call: RadioCall): Long = runCatching { Instant.parse(call.dateTime).toEpochMilli() }.getOrDefault(0L)

    private fun publish() {
        val serverMap = sessions.values.associate { it.profile.id to it.state }
        val history = serverMap.values.flatMap { it.history }.sortedByDescending(::callSortKey).take(500)
        val alerts = serverMap.values.flatMap { it.alerts }.take(200)
        _state.value = ScannerState(serverMap, history, alerts)
    }
}
