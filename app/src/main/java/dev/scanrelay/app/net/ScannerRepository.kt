package dev.scanrelay.app.net

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import dev.scanrelay.app.alerts.AlertNotifier
import dev.scanrelay.app.data.ChannelStore
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.AlertKeywordList
import dev.scanrelay.app.model.AlertPreference
import dev.scanrelay.app.model.CallSource
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.FavoriteTagKey
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScannerAlert
import dev.scanrelay.app.model.ScanList
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.SystemHealthAlert
import dev.scanrelay.app.model.TranscriptRecord
import dev.scanrelay.app.playback.ScannerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

object ScannerRepository {
    private class Session(profile: ServerProfile) {
        @Volatile var profile: ServerProfile = profile
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
        val continueReplayQueue = ArrayDeque<Long>()
        var continueReplayPending: Long? = null
        var continueReplayRestoreLivefeed = false
        val callMutex = Mutex()
        val settingsMutex = Mutex()
        @Volatile var scanListSaveJob: Job? = null
        @Volatile var scanListRevision = 0L
        @Volatile var favoriteSaveJob: Job? = null
        @Volatile var favoriteRevision = 0L
        @Volatile var alertRefreshJob: Job? = null
        val alertPreferenceMutex = Mutex()
        val alertKeywordListMutex = Mutex()
        @Volatile var alertPreferenceSaveJob: Job? = null
        @Volatile var alertPreferenceRevision = 0L
        @Volatile var hasConnected = false
        @Volatile var disconnectNotified = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val keyHttpClient = OkHttpClient()
    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()
    private val _profileCredentialUpdates = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val profileCredentialUpdates: SharedFlow<String> = _profileCredentialUpdates.asSharedFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var channelStore: ChannelStore? = null
    @Volatile private var profileStore: ProfileStore? = null
    @Volatile private var networkAvailable = true

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
                channelStore = ChannelStore(context.applicationContext)
                profileStore = ProfileStore(context.applicationContext)
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
        synchronized(session) {
            if (enabled && systemRef in session.state.hiddenSystemRefs) return
            channelStore?.setEnabled(profileId, key, enabled)
            val systems = session.state.systems.map { system ->
                if (system.systemRef != systemRef) system
                else system.copy(talkgroups = system.talkgroups.map { tg ->
                    if (tg.talkgroupRef == talkgroupRef) tg.copy(enabled = enabled) else tg
                })
            }
            session.state = session.state.copy(systems = systems)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun setSystemEnabled(profileId: String, systemRef: Long, enabled: Boolean) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            if (enabled && systemRef in session.state.hiddenSystemRefs) return
            val target = session.state.systems.firstOrNull { it.systemRef == systemRef } ?: return
            val keys = target.talkgroups.map { it.key }
            channelStore?.setMany(profileId, keys, enabled)
            val systems = session.state.systems.map { system ->
                if (system.systemRef != systemRef) system
                else system.copy(talkgroups = system.talkgroups.map { it.copy(enabled = enabled) })
            }
            session.state = session.state.copy(systems = systems)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun setSystemHidden(profileId: String, systemRef: Long, hidden: Boolean) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val target = session.state.systems.firstOrNull { it.systemRef == systemRef } ?: return
            channelStore?.setSystemHidden(profileId, systemRef, hidden)
            if (hidden) channelStore?.setMany(profileId, target.talkgroups.map { it.key }, false)
            val hiddenRefs = session.state.hiddenSystemRefs.toMutableSet().apply {
                if (hidden) add(systemRef) else remove(systemRef)
            }
            val systems = session.state.systems.map { system ->
                if (!hidden || system.systemRef != systemRef) system
                else system.copy(talkgroups = system.talkgroups.map { it.copy(enabled = false) })
            }
            session.state = session.state.copy(systems = systems, hiddenSystemRefs = hiddenRefs)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun setAllEnabled(profileId: String, enabled: Boolean) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val visibleSystems = session.state.systems.filterNot { it.systemRef in session.state.hiddenSystemRefs }
            val visibleKeys = visibleSystems.flatMap { it.talkgroups }.map { it.key }.toSet()
            val selected = if (enabled) visibleKeys else emptySet()
            channelStore?.setAll(profileId, selected, enabled = true)
            val systems = session.state.systems.map { system ->
                if (system.systemRef in session.state.hiddenSystemRefs) {
                    system.copy(talkgroups = system.talkgroups.map { it.copy(enabled = false) })
                } else {
                    system.copy(talkgroups = system.talkgroups.map { it.copy(enabled = enabled) })
                }
            }
            session.state = session.state.copy(systems = systems)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun setChannelsEnabled(profileId: String, keys: Collection<ChannelKey>, enabled: Boolean) {
        if (keys.isEmpty()) return
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val hidden = session.state.hiddenSystemRefs
            val targetKeys = keys.filterNot { enabled && it.systemRef in hidden }.toSet()
            if (targetKeys.isEmpty()) return
            channelStore?.setMany(profileId, targetKeys, enabled)
            val systems = session.state.systems.map { system ->
                system.copy(
                    talkgroups = system.talkgroups.map { talkgroup ->
                        if (talkgroup.key in targetKeys) talkgroup.copy(enabled = enabled) else talkgroup
                    }
                )
            }
            session.state = session.state.copy(systems = systems)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun createScanList(profileId: String, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        mutateScanLists(profileId) { lists ->
            lists + ScanList(
                id = "list-" + System.currentTimeMillis(),
                name = cleanName,
                channels = emptyList()
            )
        }
    }

    fun renameScanList(profileId: String, listId: String, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        mutateScanLists(profileId) { lists ->
            lists.map { if (it.id == listId) it.copy(name = cleanName) else it }
        }
    }

    fun deleteScanList(profileId: String, listId: String) {
        mutateScanLists(profileId) { lists -> lists.filterNot { it.id == listId } }
    }

    internal fun reorderedScanLists(lists: List<ScanList>, fromIndex: Int, toIndex: Int): List<ScanList> {
        if (fromIndex !in lists.indices || toIndex !in lists.indices || fromIndex == toIndex) return lists
        return lists.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
    }

    fun reorderScanList(profileId: String, fromIndex: Int, toIndex: Int) {
        mutateScanLists(profileId) { lists -> reorderedScanLists(lists, fromIndex, toIndex) }
    }

    fun setScanListChannel(profileId: String, listId: String, key: ChannelKey, included: Boolean) {
        mutateScanLists(profileId) { lists ->
            lists.map { list ->
                if (list.id != listId) list
                else {
                    val channels = if (included) {
                        (list.channels + key).distinct()
                    } else {
                        list.channels.filterNot { it == key }
                    }
                    list.copy(channels = channels)
                }
            }
        }
    }

    private fun mutateScanLists(profileId: String, transform: (List<ScanList>) -> List<ScanList>) {
        val session = sessions[profileId] ?: return
        val changed = synchronized(session) {
            val updated = transform(session.state.scanLists)
            if (updated == session.state.scanLists) {
                false
            } else {
                session.scanListRevision++
                session.state = session.state.copy(
                    scanLists = updated,
                    scanListSyncing = true,
                    scanListError = null
                )
                true
            }
        }
        if (!changed) return
        publish()
        scheduleScanListSave(session)
    }

    private fun scheduleScanListSave(session: Session) {
        synchronized(session) {
            session.scanListSaveJob?.cancel()
            session.scanListSaveJob = scope.launch {
                delay(650L)
                persistScanLists(session)
            }
        }
    }

    private suspend fun persistScanLists(session: Session) {
        session.settingsMutex.withLock {
            while (isCurrent(session)) {
                val snapshot = synchronized(session) {
                    Triple(session.scanListRevision, session.state.scanLists, session.state.systems)
                }
                val revision = snapshot.first
                val lists = snapshot.second
                val systems = snapshot.third
                val pin = session.profile.pin.trim()
                if (pin.isBlank()) {
                    synchronized(session) {
                        session.state = session.state.copy(
                            scanListSyncing = false,
                            scanListError = "Sign in to sync Scan Lists with the server"
                        )
                        session.scanListSaveJob = null
                    }
                    publish()
                    return
                }

                try {
                    val origin = httpOrigin(session.profile.baseUrl)
                    val auth = "Bearer $pin"
                    val currentRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .get()
                        .build()
                    val current = executeSettingsJson(currentRequest)
                    val updated = mergeScanListsIntoSettings(current, lists, systems)
                    val saveRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .post(updated.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeSettingsJson(saveRequest)

                    val done = synchronized(session) {
                        if (session.scanListRevision == revision) {
                            session.state = session.state.copy(scanListSyncing = false, scanListError = null)
                            session.scanListSaveJob = null
                            true
                        } else false
                    }
                    publish()
                    if (done) return
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return
                    synchronized(session) {
                        session.state = session.state.copy(
                            scanListSyncing = false,
                            scanListError = error.message?.takeIf { it.isNotBlank() } ?: "Scan List sync failed"
                        )
                        session.scanListSaveJob = null
                    }
                    publish()
                    return
                }
            }
        }
    }

    private fun scheduleFavoriteSave(session: Session) {
        if (session.profile.pin.trim().isBlank()) return
        synchronized(session) {
            session.favoriteSaveJob?.cancel()
            session.favoriteSaveJob = scope.launch {
                delay(650L)
                persistFavorites(session)
            }
        }
    }

    private suspend fun persistFavorites(session: Session) {
        session.settingsMutex.withLock {
            while (isCurrent(session)) {
                val snapshot = synchronized(session) {
                    Triple(
                        session.favoriteRevision,
                        FavoriteSettingsSelection(
                            channels = session.state.systems
                                .flatMap { it.talkgroups }
                                .filter { it.favorite }
                                .map { it.key }
                                .toSet(),
                            systemRefs = session.state.favoriteSystemRefs,
                            tags = session.state.favoriteTags
                        ),
                        session.state.systems
                    )
                }
                val revision = snapshot.first
                val favoriteSelection = snapshot.second
                val systems = snapshot.third
                val pin = session.profile.pin.trim()
                if (pin.isBlank()) {
                    synchronized(session) { session.favoriteSaveJob = null }
                    return
                }

                try {
                    val origin = httpOrigin(session.profile.baseUrl)
                    val auth = "Bearer $pin"
                    val currentRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .get()
                        .build()
                    val current = executeSettingsJson(currentRequest)
                    val updated = mergeFavoritesIntoSettings(current, favoriteSelection, systems)
                    val saveRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .post(updated.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeSettingsJson(saveRequest)

                    val done = synchronized(session) {
                        if (session.favoriteRevision == revision) {
                            session.favoriteSaveJob = null
                            true
                        } else false
                    }
                    if (done) return
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return
                    synchronized(session) { session.favoriteSaveJob = null }
                    return
                }
            }
        }
    }

    private fun executeSettingsJson(request: Request): JSONObject =
        keyHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                val message = json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
                    ?: "Settings request failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
            json
        }

    private fun executeJsonArray(request: Request): JSONArray =
        keyHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                val message = json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
                    ?: "Request failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
            runCatching { JSONArray(text) }.getOrElse {
                throw IllegalStateException("Server returned invalid alert history")
            }
        }
    private fun executeJsonObject(request: Request): JSONObject =
        keyHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                val message = json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
                    ?: "Request failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
            runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("Server returned invalid JSON")
            }
        }

    private fun executeSuccess(request: Request) {
        keyHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                val message = json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
                    ?: "Request failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
        }
    }

    internal data class ParsedSystemAlerts(
        val alerts: List<SystemHealthAlert>,
        val canViewSystemAlerts: Boolean
    )

    internal fun parseSystemAlerts(raw: JSONObject): ParsedSystemAlerts {
        val parsed = buildList {
            val alerts = raw.optJSONArray("alerts") ?: JSONArray()
            for (i in 0 until alerts.length()) {
                val item = alerts.optJSONObject(i) ?: continue
                val id = item.optLong("id").takeIf { it > 0 } ?: continue
                add(
                    SystemHealthAlert(
                        id = id,
                        alertType = item.optString("alertType").trim(),
                        severity = item.optString("severity").trim(),
                        title = item.optString("title").trim().ifBlank {
                            item.optString("alertType").trim().ifBlank { "System alert" }
                        },
                        message = item.optString("message").trim(),
                        data = item.opt("data")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()
                            ?.takeIf { it.isNotBlank() },
                        createdAt = item.opt("createdAt")?.toString()?.toLongOrNull() ?: 0L,
                        dismissed = item.optBoolean("dismissed", false)
                    )
                )
            }
        }.sortedByDescending { it.createdAt }

        return ParsedSystemAlerts(
            alerts = parsed,
            canViewSystemAlerts = if (raw.has("canViewSystemAlerts")) {
                raw.optBoolean("canViewSystemAlerts", false)
            } else {
                parsed.isNotEmpty()
            }
        )
    }

    internal fun parseAlertKeywordLists(raw: JSONArray): List<AlertKeywordList> = buildList {
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val id = item.optLong("id").takeIf { it > 0 } ?: continue
            val keywords = item.optJSONArray("keywords")?.let { array ->
                buildList {
                    for (j in 0 until array.length()) {
                        array.optString(j).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.distinct()
            }.orEmpty()
            add(
                AlertKeywordList(
                    id = id,
                    label = item.optString("label").trim().ifBlank { "Keyword list $id" },
                    description = item.optString("description").trim(),
                    keywords = keywords
                )
            )
        }
    }.sortedBy { it.label.lowercase() }
    internal fun parseAlertPreferences(raw: JSONArray): List<AlertPreference> {
        fun strings(value: Any?): List<String> {
            val array = when (value) {
                is JSONArray -> value
                is String -> runCatching { JSONArray(value) }.getOrNull()
                else -> null
            } ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }
        fun longs(value: Any?): List<Long> {
            val array = when (value) {
                is JSONArray -> value
                is String -> runCatching { JSONArray(value) }.getOrNull()
                else -> null
            } ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val parsed = array.opt(i)?.toString()?.toLongOrNull() ?: continue
                    if (parsed > 0) add(parsed)
                }
            }.distinct()
        }
        fun stringMap(value: Any?): Map<String, String> {
            val obj = when (value) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            } ?: return emptyMap()
            return buildMap {
                obj.keys().forEach { key ->
                    obj.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                }
            }
        }
        fun boolMap(value: Any?): Map<String, Boolean> {
            val obj = when (value) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            } ?: return emptyMap()
            return buildMap {
                obj.keys().forEach { key -> if (obj.has(key)) put(key, obj.optBoolean(key, false)) }
            }
        }

        return buildList {
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val systemRef = item.optLong("systemRef").takeIf { it > 0 } ?: continue
                val talkgroupRef = item.optLong("talkgroupRef").takeIf { it > 0 } ?: continue
                add(
                    AlertPreference(
                        systemRef = systemRef,
                        talkgroupRef = talkgroupRef,
                        alertEnabled = item.optBoolean("alertEnabled", false),
                        toneAlerts = item.optBoolean("toneAlerts", true),
                        keywordAlerts = item.optBoolean("keywordAlerts", true),
                        keywords = strings(item.opt("keywords")),
                        keywordListIds = longs(item.opt("keywordListIds")),
                        toneSetIds = strings(item.opt("toneSetIds")),
                        notificationSound = item.optString("notificationSound"),
                        toneSetSounds = stringMap(item.opt("toneSetSounds")),
                        pagerAlert = item.optBoolean("pagerAlert", false),
                        toneSetPagerAlerts = boolMap(item.opt("toneSetPagerAlerts"))
                    )
                )
            }
        }.sortedWith(compareBy<AlertPreference> { it.systemRef }.thenBy { it.talkgroupRef })
    }

    internal fun serializeAlertPreferences(preferences: List<AlertPreference>): JSONArray =
        JSONArray().apply {
            preferences.forEach { pref ->
                put(
                    JSONObject()
                        .put("systemRef", pref.systemRef)
                        .put("talkgroupRef", pref.talkgroupRef)
                        .put("alertEnabled", pref.alertEnabled)
                        .put("toneAlerts", pref.toneAlerts)
                        .put("keywordAlerts", pref.keywordAlerts)
                        .put("keywords", JSONArray(pref.keywords))
                        .put("keywordListIds", JSONArray(pref.keywordListIds))
                        .put("toneSetIds", JSONArray(pref.toneSetIds))
                        .put("notificationSound", pref.notificationSound)
                        .put("toneSetSounds", JSONObject(pref.toneSetSounds))
                        .put("pagerAlert", pref.pagerAlert)
                        .put("toneSetPagerAlerts", JSONObject(pref.toneSetPagerAlerts))
                )
            }
        }
    internal fun mergeScanListsIntoSettings(
        current: JSONObject,
        scanLists: List<ScanList>,
        systems: List<SystemConfig>
    ): JSONObject {
        val updated = JSONObject(current.toString())
        updated.put("scanLists", serializeScanLists(scanLists, systems))
        updated.put("activeScanListIds", JSONArray())
        updated.put("activeScanListId", JSONObject.NULL)
        return updated
    }

    internal fun serializeScanLists(scanLists: List<ScanList>, systems: List<SystemConfig>): JSONArray {
        val output = JSONArray()
        scanLists.forEach { list ->
            val channels = JSONArray()
            list.channels.distinct().forEach { key ->
                val system = systems.firstOrNull { it.systemRef == key.systemRef }
                val talkgroup = system?.talkgroups?.firstOrNull { it.talkgroupRef == key.talkgroupRef }
                channels.put(
                    JSONObject()
                        .put("systemId", key.systemRef.toString())
                        .put("talkgroupId", key.talkgroupRef.toString())
                        .put("talkgroupLabel", talkgroup?.label.orEmpty())
                        .put("talkgroupName", talkgroup?.name.orEmpty())
                        .put("systemLabel", system?.label.orEmpty())
                        .put("tag", talkgroup?.tag.orEmpty())
                        .put("isEnabled", talkgroup?.enabled == true)
                )
            }
            output.put(
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("channels", channels)
            )
        }
        return output
    }

    private fun httpOrigin(baseUrl: String): String {
        val normalized = baseUrl.trim().let {
            if (
                it.startsWith("http://", true) ||
                it.startsWith("https://", true) ||
                it.startsWith("ws://", true) ||
                it.startsWith("wss://", true)
            ) it else "https://$it"
        }
        val uri = URI(normalized)
        val scheme = when (uri.scheme?.lowercase()) {
            "ws" -> "http"
            "wss" -> "https"
            "http", "https" -> uri.scheme.lowercase()
            else -> error("Unsupported server URL scheme: ${uri.scheme}")
        }
        require(!uri.host.isNullOrBlank()) { "Server URL must include a host" }
        return URI(scheme, null, uri.host, uri.port, null, null, null).toString().trimEnd('/')
    }
    fun setLivefeedBacklogMinutes(profileId: String, minutes: Int) {
        val session = sessions[profileId] ?: return
        val value = minutes.coerceAtLeast(0)
        val pin = session.profile.pin.trim()
        synchronized(session) {
            session.state = session.state.copy(
                livefeedBacklogMinutes = value,
                userSettingsSaving = pin.isNotBlank(),
                userSettingsError = if (pin.isBlank()) {
                    "Sign in to save live-feed backlog"
                } else {
                    null
                }
            )
        }
        publish()
        if (pin.isBlank()) return

        scope.launch {
            session.settingsMutex.withLock {
                if (!isCurrent(session)) return@withLock
                try {
                    val origin = httpOrigin(session.profile.baseUrl)
                    val auth = "Bearer $pin"
                    val currentRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .get()
                        .build()
                    val current = executeSettingsJson(currentRequest)
                    val updated = mergeLivefeedBacklogIntoSettings(current, value)
                    val saveRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .post(updated.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeSettingsJson(saveRequest)

                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            userSettingsSaving = false,
                            userSettingsError = null
                        )
                    }
                    publish()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            userSettingsSaving = false,
                            userSettingsError = error.message?.takeIf { it.isNotBlank() }
                                ?: "User settings save failed"
                        )
                    }
                    publish()
                }
            }
        }
    }

    fun setTagColor(profileId: String, tag: String, color: String?) {
        val session = sessions[profileId] ?: return
        val key = tag.trim().lowercase()
        if (key.isBlank()) return
        val pin = session.profile.pin.trim()

        synchronized(session) {
            val colors = session.state.tagColors.toMutableMap()
            val normalizedColor = color?.trim()?.takeIf { it.isNotBlank() }
            if (normalizedColor == null) colors.remove(key) else colors[key] = normalizedColor
            session.state = session.state.copy(
                tagColors = colors,
                userSettingsSaving = pin.isNotBlank(),
                userSettingsError = if (pin.isBlank()) {
                    "Sign in to save tag colors"
                } else {
                    null
                }
            )
        }
        publish()
        if (pin.isBlank()) return

        scope.launch {
            session.settingsMutex.withLock {
                if (!isCurrent(session)) return@withLock
                try {
                    val colors = synchronized(session) { session.state.tagColors }
                    val origin = httpOrigin(session.profile.baseUrl)
                    val auth = "Bearer $pin"
                    val currentRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .get()
                        .build()
                    val current = executeSettingsJson(currentRequest)
                    val updated = mergeTagColorsIntoSettings(current, colors)
                    val saveRequest = Request.Builder()
                        .url("$origin/api/settings")
                        .header("Authorization", auth)
                        .post(updated.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeSettingsJson(saveRequest)

                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            userSettingsSaving = false,
                            userSettingsError = null
                        )
                    }
                    publish()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            userSettingsSaving = false,
                            userSettingsError = error.message?.takeIf { it.isNotBlank() }
                                ?: "User settings save failed"
                        )
                    }
                    publish()
                }
            }
        }
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
                else sendEffectiveLivefeedLocked(session)
            }
        }
        appContext?.let { ScannerService.setProfilePaused(it, profileId, paused) }
        publish()
    }

    fun setFavorite(profileId: String, systemRef: Long, talkgroupRef: Long, favorite: Boolean) {
        val session = sessions[profileId] ?: return
        val key = ChannelKey(systemRef, talkgroupRef)
        val tagKey = synchronized(session) {
            session.state.systems
                .firstOrNull { it.systemRef == systemRef }
                ?.talkgroups
                ?.firstOrNull { it.talkgroupRef == talkgroupRef }
                ?.let { FavoriteTagKey(systemRef, normalizedFavoriteTag(it.tag)) }
        }
        channelStore?.setFavorite(profileId, key, favorite)
        synchronized(session) {
            session.favoriteRevision++
            session.state = session.state.copy(
                systems = session.state.systems.map { system ->
                    if (system.systemRef != systemRef) system
                    else system.copy(
                        talkgroups = system.talkgroups.map { talkgroup ->
                            if (talkgroup.talkgroupRef == talkgroupRef) {
                                talkgroup.copy(favorite = favorite)
                            } else talkgroup
                        }
                    )
                },
                favoriteSystemRefs = if (favorite) {
                    session.state.favoriteSystemRefs
                } else {
                    session.state.favoriteSystemRefs - systemRef
                },
                favoriteTags = if (favorite || tagKey == null) {
                    session.state.favoriteTags
                } else {
                    session.state.favoriteTags - tagKey
                }
            )
        }
        publish()
        scheduleFavoriteSave(session)
    }

    fun setSystemFavorite(profileId: String, systemRef: Long, favorite: Boolean) {
        val session = sessions[profileId] ?: return
        val target = synchronized(session) {
            session.state.systems.firstOrNull { it.systemRef == systemRef }
        } ?: return
        val keys = target.talkgroups.map { it.key }.toSet()
        val tagKeys = target.talkgroups
            .map { FavoriteTagKey(systemRef, normalizedFavoriteTag(it.tag)) }
            .toSet()
        channelStore?.setFavorites(profileId, keys, favorite)
        synchronized(session) {
            session.favoriteRevision++
            session.state = session.state.copy(
                systems = session.state.systems.map { system ->
                    if (system.systemRef != systemRef) system
                    else system.copy(
                        talkgroups = system.talkgroups.map { it.copy(favorite = favorite) }
                    )
                },
                favoriteSystemRefs = if (favorite) {
                    session.state.favoriteSystemRefs + systemRef
                } else {
                    session.state.favoriteSystemRefs - systemRef
                },
                favoriteTags = if (favorite) {
                    session.state.favoriteTags + tagKeys
                } else {
                    session.state.favoriteTags - tagKeys
                }
            )
        }
        publish()
        scheduleFavoriteSave(session)
    }

    fun setTagFavorite(profileId: String, systemRef: Long, tag: String, favorite: Boolean) {
        val session = sessions[profileId] ?: return
        val normalizedTag = normalizedFavoriteTag(tag)
        val targetKeys = synchronized(session) {
            session.state.systems
                .firstOrNull { it.systemRef == systemRef }
                ?.talkgroups
                ?.filter { normalizedFavoriteTag(it.tag) == normalizedTag }
                ?.map { it.key }
                ?.toSet()
        }.orEmpty()
        if (targetKeys.isEmpty()) return

        channelStore?.setFavorites(profileId, targetKeys, favorite)
        synchronized(session) {
            val updatedSystems = session.state.systems.map { system ->
                if (system.systemRef != systemRef) system
                else system.copy(
                    talkgroups = system.talkgroups.map { talkgroup ->
                        if (talkgroup.key in targetKeys) talkgroup.copy(favorite = favorite) else talkgroup
                    }
                )
            }
            val updatedSystem = updatedSystems.firstOrNull { it.systemRef == systemRef }
            val allSystemFavorite = updatedSystem?.talkgroups
                ?.let { talkgroups -> talkgroups.isNotEmpty() && talkgroups.all { it.favorite } }
                ?: false
            val tagKey = FavoriteTagKey(systemRef, normalizedTag)

            session.favoriteRevision++
            session.state = session.state.copy(
                systems = updatedSystems,
                favoriteSystemRefs = when {
                    !favorite -> session.state.favoriteSystemRefs - systemRef
                    allSystemFavorite -> session.state.favoriteSystemRefs + systemRef
                    else -> session.state.favoriteSystemRefs
                },
                favoriteTags = if (favorite) {
                    session.state.favoriteTags + tagKey
                } else {
                    session.state.favoriteTags - tagKey
                }
            )
        }
        publish()
        scheduleFavoriteSave(session)
    }

    fun setHold(profileId: String, key: ChannelKey?) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            session.state = session.state.copy(
                hold = key,
                holdSystemRef = if (key != null) null else session.state.holdSystemRef
            )
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun setSystemHold(profileId: String, systemRef: Long?) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            session.state = session.state.copy(
                holdSystemRef = systemRef,
                hold = if (systemRef != null) null else session.state.hold
            )
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun clearHold(profileId: String) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            session.state = session.state.copy(hold = null, holdSystemRef = null)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun avoid(profileId: String, key: ChannelKey, avoided: Boolean = true) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            val set = session.state.avoided.toMutableSet()
            if (avoided) set += key else set -= key
            session.state = session.state.copy(avoided = set)
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun clearAvoids(profileId: String) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            session.state = session.state.copy(avoided = emptySet())
            sendEffectiveLivefeedLocked(session)
        }
        publish()
        pruneQueuedLiveCalls(profileId)
    }

    fun isChannelSubscribed(profileId: String, systemRef: Long, talkgroupRef: Long): Boolean {
        val session = sessions[profileId] ?: return false
        val state = session.state
        if (state.paused) return false
        val key = ChannelKey(systemRef, talkgroupRef)
        val baseEnabled = state.systems
            .firstOrNull { it.systemRef == systemRef }
            ?.talkgroups
            ?.firstOrNull { it.talkgroupRef == talkgroupRef }
            ?.enabled == true
        val talkgroupHoldAllows = state.hold?.let { it == key } ?: true
        val systemHoldAllows = state.holdSystemRef?.let { it == systemRef } ?: true
        return baseEnabled && talkgroupHoldAllows && systemHoldAllows && key !in state.avoided
    }

    private fun pruneQueuedLiveCalls(profileId: String) {
        appContext?.let { ScannerService.filterProfileMedia(it, profileId) }
    }
    fun requestHistory(
        profileId: String,
        reset: Boolean = true,
        systemRef: Long? = null,
        talkgroupRef: Long? = null,
        date: String? = null,
        group: String? = null,
        tag: String? = null,
        sort: Int = -1
    ) {
        val session = sessions[profileId] ?: return
        synchronized(session) {
            if (reset) {
                session.historyOffset = 0
                session.state = session.state.copy(
                    history = emptyList(),
                    historyHasMore = false,
                    historySystemRef = systemRef?.takeIf { it > 0 },
                    historyTalkgroupRef = talkgroupRef?.takeIf { it > 0 },
                    historyDate = date?.trim()?.takeIf { it.isNotBlank() },
                    historyGroup = group?.trim()?.takeIf { it.isNotBlank() },
                    historyTag = tag?.trim()?.takeIf { it.isNotBlank() },
                    historySort = if (sort < 0) -1 else 1
                )
            }
            val activeSystemRef = session.state.historySystemRef
            val activeTalkgroupRef = session.state.historyTalkgroupRef
            val activeDate = session.state.historyDate
            val activeGroup = session.state.historyGroup
            val activeTag = session.state.historyTag
            val activeSort = session.state.historySort
            session.socket?.requestHistory(
                limit = 200,
                offset = session.historyOffset,
                sort = activeSort,
                systemRef = activeSystemRef,
                talkgroupRef = activeTalkgroupRef,
                date = activeDate,
                group = activeGroup,
                tag = activeTag
            )
        }
        publish()
    }

    fun refreshAlerts(profileId: String) {
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    alertsLoading = false,
                    alertsError = "Sign in to load server alert history"
                )
            }
            publish()
            return
        }

        val shouldStart = synchronized(session) {
            if (session.state.alertsLoading) false
            else {
                session.state = session.state.copy(alertsLoading = true, alertsError = null)
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            try {
                val origin = httpOrigin(session.profile.baseUrl)
                val request = Request.Builder()
                    .url("$origin/api/alerts")
                    .header("Authorization", "Bearer $pin")
                    .get()
                    .build()
                val alerts = parseServerAlerts(session.profile, executeJsonArray(request))
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        alerts = alerts.take(500),
                        alertsLoading = false,
                        alertsError = null
                    )
                }
                publish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        alertsLoading = false,
                        alertsError = error.message?.takeIf { it.isNotBlank() } ?: "Alert history refresh failed"
                    )
                }
                publish()
            }
        }
    }

    fun refreshTranscripts(
        profileId: String,
        offset: Int = 0,
        limit: Int = 50,
        systemId: Long? = null,
        talkgroupId: Long? = null,
        dateFrom: Long? = null,
        dateTo: Long? = null,
        search: String? = null
    ) {
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    transcriptsLoading = false,
                    transcriptsError = "Sign in to load transcripts"
                )
            }
            publish()
            return
        }

        synchronized(session) {
            session.state = session.state.copy(
                transcriptsLoading = true,
                transcriptsError = null,
                transcriptsOffset = offset.coerceAtLeast(0)
            )
        }
        publish()

        scope.launch {
            try {
                val origin = httpOrigin(session.profile.baseUrl)
                val pageLimit = limit.coerceIn(1, 100)
                val pageOffset = offset.coerceAtLeast(0)
                val params = mutableListOf(
                    "limit=${pageLimit}",
                    "offset=${pageOffset}",
                    "pin=${encodeQuery(pin)}"
                )
                systemId?.takeIf { it > 0 }?.let { params += "systemId=$it" }
                talkgroupId?.takeIf { it > 0 }?.let { params += "talkgroupId=$it" }
                dateFrom?.takeIf { it > 0 }?.let { params += "dateFrom=$it" }
                dateTo?.takeIf { it > 0 }?.let { params += "dateTo=$it" }
                search?.trim()?.takeIf { it.isNotBlank() }?.let { params += "search=${encodeQuery(it)}" }

                val request = Request.Builder()
                    .url("$origin/api/transcripts?${params.joinToString("&")}")
                    .header("Authorization", "Bearer $pin")
                    .get()
                    .build()
                val transcripts = parseTranscripts(
                    session.profile,
                    executeJsonArray(request)
                )
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        transcripts = transcripts,
                        transcriptsLoading = false,
                        transcriptsError = null,
                        transcriptsOffset = pageOffset,
                        transcriptsHasMore = transcripts.size >= pageLimit
                    )
                }
                publish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        transcriptsLoading = false,
                        transcriptsError = error.message?.takeIf { it.isNotBlank() }
                            ?: "Transcript refresh failed"
                    )
                }
                publish()
            }
        }
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun scheduleAlertRefresh(session: Session) {
        if (session.profile.pin.isBlank()) return
        synchronized(session) {
            session.alertRefreshJob?.cancel()
            session.alertRefreshJob = scope.launch {
                delay(300L)
                synchronized(session) { session.alertRefreshJob = null }
                refreshAlerts(session.profile.id)
            }
        }
    }
    fun refreshAlertPreferences(profileId: String) {
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    alertPreferencesLoading = false,
                    alertPreferencesError = "Sign in to load alert preferences"
                )
            }
            publish()
            return
        }
        val shouldStart = synchronized(session) {
            if (session.state.alertPreferencesLoading || session.state.alertPreferencesSaving) false
            else {
                session.state = session.state.copy(
                    alertPreferencesLoading = true,
                    alertPreferencesError = null
                )
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            try {
                val preferences = fetchAlertPreferences(session)
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        alertPreferences = preferences,
                        alertPreferencesLoading = false,
                        alertPreferencesError = null
                    )
                }
                publish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        alertPreferencesLoading = false,
                        alertPreferencesError = error.message?.takeIf { it.isNotBlank() }
                            ?: "Alert preference refresh failed"
                    )
                }
                publish()
            }
        }
    }

    internal fun systemAlertsUrl(baseUrl: String, alertId: Long? = null): String {
        val base = "${httpOrigin(baseUrl)}/api/system-alerts"
        return alertId?.takeIf { it > 0 }?.let { "$base/$it" }
            ?: "$base?limit=50&includeDismissed=false"
    }

    fun refreshSystemAlerts(profileId: String) {
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    systemAlertsLoading = false,
                    systemAlertsError = "Sign in to load system alerts"
                )
            }
            publish()
            return
        }
        val shouldStart = synchronized(session) {
            if (session.state.systemAlertsLoading) false
            else {
                session.state = session.state.copy(
                    systemAlertsLoading = true,
                    systemAlertsError = null
                )
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            try {
                val result = fetchSystemAlerts(session)
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        systemAlerts = result.alerts,
                        systemAlertsLoading = false,
                        systemAlertsError = null,
                        canViewSystemAlerts = result.canViewSystemAlerts
                    )
                }
                publish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        systemAlertsLoading = false,
                        systemAlertsError = error.message?.takeIf { it.isNotBlank() }
                            ?: "System alert refresh failed"
                    )
                }
                publish()
            }
        }
    }

    fun dismissSystemAlert(profileId: String, alertId: Long) {
        if (alertId <= 0) return
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    systemAlertsLoading = false,
                    systemAlertsError = "Sign in to dismiss system alerts"
                )
            }
            publish()
            return
        }
        val shouldStart = synchronized(session) {
            if (session.state.systemAlertsLoading) false
            else {
                session.state = session.state.copy(
                    systemAlertsLoading = true,
                    systemAlertsError = null
                )
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            try {
                val request = Request.Builder()
                    .url(systemAlertsUrl(session.profile.baseUrl, alertId))
                    .header("Authorization", "Bearer $pin")
                    .delete()
                    .build()
                executeSuccess(request)
                val result = fetchSystemAlerts(session)
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        systemAlerts = result.alerts,
                        systemAlertsLoading = false,
                        systemAlertsError = null,
                        canViewSystemAlerts = result.canViewSystemAlerts
                    )
                }
                publish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        systemAlertsLoading = false,
                        systemAlertsError = error.message?.takeIf { it.isNotBlank() }
                            ?: "System alert dismissal failed"
                    )
                }
                publish()
            }
        }
    }

    fun refreshAlertKeywordLists(profileId: String) {
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    alertKeywordListsLoading = false,
                    alertKeywordListsError = "Sign in to load keyword lists"
                )
            }
            publish()
            return
        }
        val shouldStart = synchronized(session) {
            if (session.state.alertKeywordListsLoading) false
            else {
                session.state = session.state.copy(
                    alertKeywordListsLoading = true,
                    alertKeywordListsError = null
                )
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            try {
                val lists = fetchAlertKeywordLists(session)
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        alertKeywordLists = lists,
                        alertKeywordListsLoading = false,
                        alertKeywordListsError = null
                    )
                }
                publish()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrent(session)) return@launch
                synchronized(session) {
                    session.state = session.state.copy(
                        alertKeywordListsLoading = false,
                        alertKeywordListsError = error.message?.takeIf { it.isNotBlank() }
                            ?: "Keyword list refresh failed"
                    )
                }
                publish()
            }
        }
    }

    internal fun keywordListUrl(baseUrl: String, listId: Long? = null): String {
        val base = "${httpOrigin(baseUrl)}/api/keyword-lists"
        return listId?.takeIf { it > 0 }?.let { "$base/$it" } ?: base
    }

    internal fun keywordListPayload(
        label: String,
        description: String,
        rawKeywords: String
    ): JSONObject = JSONObject()
        .put("label", label.trim())
        .put("description", description.trim())
        .put("keywords", JSONArray(normalizeAlertKeywords(rawKeywords)))

    fun createAlertKeywordList(
        profileId: String,
        label: String,
        description: String,
        rawKeywords: String
    ) = saveAlertKeywordList(profileId, null, label, description, rawKeywords)

    fun updateAlertKeywordList(
        profileId: String,
        listId: Long,
        label: String,
        description: String,
        rawKeywords: String
    ) {
        if (listId <= 0) return
        saveAlertKeywordList(profileId, listId, label, description, rawKeywords)
    }

    private fun saveAlertKeywordList(
        profileId: String,
        listId: Long?,
        label: String,
        description: String,
        rawKeywords: String
    ) {
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        val normalizedLabel = label.trim()
        val validationError = when {
            pin.isBlank() -> "Sign in to save keyword lists"
            normalizedLabel.isBlank() -> "Keyword list name is required"
            else -> null
        }
        if (validationError != null) {
            synchronized(session) {
                session.state = session.state.copy(
                    alertKeywordListsSaving = false,
                    alertKeywordListsError = validationError
                )
            }
            publish()
            return
        }

        val shouldStart = synchronized(session) {
            if (session.state.alertKeywordListsSaving) false
            else {
                session.state = session.state.copy(
                    alertKeywordListsSaving = true,
                    alertKeywordListsError = null
                )
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            session.alertKeywordListMutex.withLock {
                try {
                    val body = keywordListPayload(normalizedLabel, description, rawKeywords)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                    val builder = Request.Builder()
                        .url(keywordListUrl(session.profile.baseUrl, listId))
                        .header("Authorization", "Bearer $pin")
                    val request = if (listId == null) {
                        builder.post(body).build()
                    } else {
                        builder.put(body).build()
                    }
                    executeSuccess(request)
                    val canonical = fetchAlertKeywordLists(session)
                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            alertKeywordLists = canonical,
                            alertKeywordListsSaving = false,
                            alertKeywordListsError = null
                        )
                    }
                    publish()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            alertKeywordListsSaving = false,
                            alertKeywordListsError = error.message?.takeIf { it.isNotBlank() }
                                ?: "Keyword list save failed"
                        )
                    }
                    publish()
                }
            }
        }
    }

    fun deleteAlertKeywordList(profileId: String, listId: Long) {
        if (listId <= 0) return
        val session = sessions[profileId] ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            synchronized(session) {
                session.state = session.state.copy(
                    alertKeywordListsSaving = false,
                    alertKeywordListsError = "Sign in to delete keyword lists"
                )
            }
            publish()
            return
        }
        val shouldStart = synchronized(session) {
            if (session.state.alertKeywordListsSaving) false
            else {
                session.state = session.state.copy(
                    alertKeywordListsSaving = true,
                    alertKeywordListsError = null
                )
                true
            }
        }
        if (!shouldStart) return
        publish()

        scope.launch {
            session.alertKeywordListMutex.withLock {
                try {
                    val request = Request.Builder()
                        .url(keywordListUrl(session.profile.baseUrl, listId))
                        .header("Authorization", "Bearer $pin")
                        .delete()
                        .build()
                    executeSuccess(request)
                    val canonicalLists = fetchAlertKeywordLists(session)
                    val canonicalPreferences = fetchAlertPreferences(session)
                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            alertKeywordLists = canonicalLists,
                            alertKeywordListsSaving = false,
                            alertKeywordListsError = null,
                            alertPreferences = canonicalPreferences
                        )
                    }
                    publish()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return@withLock
                    synchronized(session) {
                        session.state = session.state.copy(
                            alertKeywordListsSaving = false,
                            alertKeywordListsError = error.message?.takeIf { it.isNotBlank() }
                                ?: "Keyword list delete failed"
                        )
                    }
                    publish()
                }
            }
        }
    }

    fun setAlertKeywords(profileId: String, key: ChannelKey, rawKeywords: String) {
        val session = sessions[profileId] ?: return
        val normalizedKeywords = normalizeAlertKeywords(rawKeywords)
        val changed = synchronized(session) {
            val existing = session.state.alertPreferences.firstOrNull { it.key == key }
                ?: AlertPreference(systemRef = key.systemRef, talkgroupRef = key.talkgroupRef)
            val updated = existing.copy(keywords = normalizedKeywords)
            if (updated == existing && session.state.alertPreferences.any { it.key == key }) {
                false
            } else {
                session.alertPreferenceRevision++
                session.state = session.state.copy(
                    alertPreferences = (session.state.alertPreferences.filterNot { it.key == key } + updated)
                        .sortedWith(compareBy<AlertPreference> { it.systemRef }.thenBy { it.talkgroupRef }),
                    alertPreferencesSaving = true,
                    alertPreferencesError = null
                )
                true
            }
        }
        if (!changed) return
        publish()
        scheduleAlertPreferenceSave(session)
    }

    fun setAlertNotificationSound(profileId: String, key: ChannelKey, fileName: String) {
        updateAlertSoundPreference(profileId, key) { existing ->
            existing.copy(notificationSound = fileName.trim())
        }
    }

    fun setAlertToneSetSound(
        profileId: String,
        key: ChannelKey,
        toneSetId: String,
        fileName: String
    ) {
        val normalizedToneSetId = toneSetId.trim()
        if (normalizedToneSetId.isEmpty()) return
        updateAlertSoundPreference(profileId, key) { existing ->
            val sounds = existing.toneSetSounds.toMutableMap()
            val normalizedFileName = fileName.trim()
            if (normalizedFileName.isEmpty()) {
                sounds.remove(normalizedToneSetId)
            } else {
                sounds[normalizedToneSetId] = normalizedFileName
            }
            existing.copy(toneSetSounds = sounds)
        }
    }

    private fun updateAlertSoundPreference(
        profileId: String,
        key: ChannelKey,
        transform: (AlertPreference) -> AlertPreference
    ) {
        val session = sessions[profileId] ?: return
        val changed = synchronized(session) {
            val existing = session.state.alertPreferences.firstOrNull { it.key == key }
                ?: AlertPreference(systemRef = key.systemRef, talkgroupRef = key.talkgroupRef)
            val updated = transform(existing)
            if (updated == existing && session.state.alertPreferences.any { it.key == key }) {
                false
            } else {
                session.alertPreferenceRevision++
                session.state = session.state.copy(
                    alertPreferences = (session.state.alertPreferences.filterNot { it.key == key } + updated)
                        .sortedWith(compareBy<AlertPreference> { it.systemRef }.thenBy { it.talkgroupRef }),
                    alertPreferencesSaving = true,
                    alertPreferencesError = null
                )
                true
            }
        }
        if (!changed) return
        publish()
        scheduleAlertPreferenceSave(session)
    }

    internal fun normalizeAlertKeywords(rawKeywords: String): List<String> =
        rawKeywords
            .replace('\n', ',')
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    internal fun normalizeAlertToneSetIds(toneSetIds: Collection<String>): List<String> =
        toneSetIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    fun setAlertToneSets(profileId: String, key: ChannelKey, toneSetIds: Collection<String>) {
        val session = sessions[profileId] ?: return
        val normalizedIds = normalizeAlertToneSetIds(toneSetIds)
        val changed = synchronized(session) {
            val existing = session.state.alertPreferences.firstOrNull { it.key == key }
                ?: AlertPreference(systemRef = key.systemRef, talkgroupRef = key.talkgroupRef)
            val updated = existing.copy(toneSetIds = normalizedIds)
            if (updated == existing && session.state.alertPreferences.any { it.key == key }) {
                false
            } else {
                session.alertPreferenceRevision++
                session.state = session.state.copy(
                    alertPreferences = (session.state.alertPreferences.filterNot { it.key == key } + updated)
                        .sortedWith(compareBy<AlertPreference> { it.systemRef }.thenBy { it.talkgroupRef }),
                    alertPreferencesSaving = true,
                    alertPreferencesError = null
                )
                true
            }
        }
        if (!changed) return
        publish()
        scheduleAlertPreferenceSave(session)
    }

    fun setAlertKeywordLists(profileId: String, key: ChannelKey, keywordListIds: Collection<Long>) {
        val session = sessions[profileId] ?: return
        val normalizedIds = keywordListIds.filter { it > 0 }.distinct().sorted()
        val changed = synchronized(session) {
            val existing = session.state.alertPreferences.firstOrNull { it.key == key }
                ?: AlertPreference(systemRef = key.systemRef, talkgroupRef = key.talkgroupRef)
            val updated = existing.copy(keywordListIds = normalizedIds)
            if (updated == existing && session.state.alertPreferences.any { it.key == key }) {
                false
            } else {
                session.alertPreferenceRevision++
                session.state = session.state.copy(
                    alertPreferences = (session.state.alertPreferences.filterNot { it.key == key } + updated)
                        .sortedWith(compareBy<AlertPreference> { it.systemRef }.thenBy { it.talkgroupRef }),
                    alertPreferencesSaving = true,
                    alertPreferencesError = null
                )
                true
            }
        }
        if (!changed) return
        publish()
        scheduleAlertPreferenceSave(session)
    }
    fun setAlertPreference(
        profileId: String,
        key: ChannelKey,
        alertEnabled: Boolean? = null,
        toneAlerts: Boolean? = null,
        keywordAlerts: Boolean? = null
    ) {
        val session = sessions[profileId] ?: return
        val changed = synchronized(session) {
            val existing = session.state.alertPreferences.firstOrNull { it.key == key }
                ?: AlertPreference(systemRef = key.systemRef, talkgroupRef = key.talkgroupRef)
            val updated = existing.copy(
                alertEnabled = alertEnabled ?: existing.alertEnabled,
                toneAlerts = toneAlerts ?: existing.toneAlerts,
                keywordAlerts = keywordAlerts ?: existing.keywordAlerts
            )
            if (updated == existing && session.state.alertPreferences.any { it.key == key }) {
                false
            } else {
                val preferences = session.state.alertPreferences
                    .filterNot { it.key == key } + updated
                session.alertPreferenceRevision++
                session.state = session.state.copy(
                    alertPreferences = preferences.sortedWith(
                        compareBy<AlertPreference> { it.systemRef }.thenBy { it.talkgroupRef }
                    ),
                    alertPreferencesSaving = true,
                    alertPreferencesError = null
                )
                true
            }
        }
        if (!changed) return
        publish()
        scheduleAlertPreferenceSave(session)
    }

    private fun scheduleAlertPreferenceSave(session: Session) {
        synchronized(session) {
            session.alertPreferenceSaveJob?.cancel()
            session.alertPreferenceSaveJob = scope.launch {
                delay(500L)
                persistAlertPreferences(session)
            }
        }
    }

    private suspend fun persistAlertPreferences(session: Session) {
        session.alertPreferenceMutex.withLock {
            while (isCurrent(session)) {
                val snapshot = synchronized(session) {
                    session.alertPreferenceRevision to session.state.alertPreferences
                }
                val revision = snapshot.first
                val preferences = snapshot.second
                val pin = session.profile.pin.trim()
                if (pin.isBlank()) {
                    synchronized(session) {
                        session.state = session.state.copy(
                            alertPreferencesSaving = false,
                            alertPreferencesError = "Sign in to save alert preferences"
                        )
                        session.alertPreferenceSaveJob = null
                    }
                    publish()
                    return
                }

                try {
                    val origin = httpOrigin(session.profile.baseUrl)
                    val request = Request.Builder()
                        .url("$origin/api/alerts/preferences")
                        .header("Authorization", "Bearer $pin")
                        .put(serializeAlertPreferences(preferences).toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    executeSuccess(request)

                    val unchanged = synchronized(session) { session.alertPreferenceRevision == revision }
                    if (!unchanged) continue

                    val canonical = fetchAlertPreferences(session)
                    val done = synchronized(session) {
                        if (session.alertPreferenceRevision == revision) {
                            session.state = session.state.copy(
                                alertPreferences = canonical,
                                alertPreferencesSaving = false,
                                alertPreferencesError = null
                            )
                            session.alertPreferenceSaveJob = null
                            true
                        } else false
                    }
                    publish()
                    if (done) return
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrent(session)) return
                    synchronized(session) {
                        session.state = session.state.copy(
                            alertPreferencesSaving = false,
                            alertPreferencesError = error.message?.takeIf { it.isNotBlank() }
                                ?: "Alert preference save failed"
                        )
                        session.alertPreferenceSaveJob = null
                    }
                    publish()
                    return
                }
            }
        }
    }

    private fun fetchSystemAlerts(session: Session): ParsedSystemAlerts {
        val request = Request.Builder()
            .url(systemAlertsUrl(session.profile.baseUrl))
            .header("Authorization", "Bearer ${session.profile.pin.trim()}")
            .get()
            .build()
        return parseSystemAlerts(executeJsonObject(request))
    }

    private fun fetchAlertPreferences(session: Session): List<AlertPreference> {
        val origin = httpOrigin(session.profile.baseUrl)
        val request = Request.Builder()
            .url("$origin/api/alerts/preferences")
            .header("Authorization", "Bearer ${session.profile.pin.trim()}")
            .get()
            .build()
        return parseAlertPreferences(executeJsonArray(request))
    }
    private fun fetchAlertKeywordLists(session: Session): List<AlertKeywordList> {
        val request = Request.Builder()
            .url(keywordListUrl(session.profile.baseUrl))
            .header("Authorization", "Bearer ${session.profile.pin.trim()}")
            .get()
            .build()
        return parseAlertKeywordLists(executeJsonArray(request))
    }
    fun replay(profileId: String, callId: Long) {
        val session = sessions[profileId] ?: return
        val existing = session.state.history.firstOrNull { it.id == callId }
        val path = existing?.audioPath
        if (path != null && File(path).isFile) {
            appContext?.let { ScannerService.enqueue(it, existing, liveFeed = false) }
            return
        }
        synchronized(session) { session.pendingReplay += callId }
        session.socket?.requestPlaybackCall(callId)
    }

    internal fun continuationCallIds(historyIdsNewestFirst: List<Long>, startId: Long): List<Long> {
        val index = historyIdsNewestFirst.indexOf(startId)
        if (index < 0) return emptyList()
        return historyIdsNewestFirst.take(index + 1).asReversed()
    }

    fun continueHistory(profileId: String, callId: Long) {
        val session = sessions[profileId] ?: return
        val context = appContext ?: return
        val ids = synchronized(session) {
            continuationCallIds(session.state.history.map { it.id }, callId)
        }
        if (ids.isEmpty()) return

        ScannerService.stopAudio(context)
        synchronized(session) {
            session.continueReplayQueue.clear()
            session.continueReplayQueue.addAll(ids)
            session.continueReplayPending = null
            session.continueReplayRestoreLivefeed =
                !session.state.paused && session.state.status == ConnectionStatus.CONNECTED
            if (session.continueReplayRestoreLivefeed) {
                session.socket?.stopLivefeed()
            }
        }
        requestNextContinueReplay(session)
    }

    private fun requestNextContinueReplay(session: Session) {
        val context = appContext ?: return
        while (true) {
            var cachedCall: RadioCall? = null
            var requestId: Long? = null
            synchronized(session) {
                if (session.continueReplayPending != null) return
                val nextId = session.continueReplayQueue.pollFirst()
                if (nextId == null) {
                    if (session.continueReplayRestoreLivefeed) {
                        session.continueReplayRestoreLivefeed = false
                        sendEffectiveLivefeedLocked(session)
                    }
                    return
                }

                val existing = session.state.history.firstOrNull { it.id == nextId }
                val path = existing?.audioPath
                if (existing != null && path != null && File(path).isFile) {
                    cachedCall = existing
                } else {
                    session.continueReplayPending = nextId
                    session.pendingReplay += nextId
                    requestId = nextId
                }
            }

            if (cachedCall != null) {
                ScannerService.enqueue(context, cachedCall!!, liveFeed = false)
                continue
            }

            val id = requestId ?: continue
            if (session.socket?.requestPlaybackCall(id) == true) return

            synchronized(session) {
                session.pendingReplay.remove(id)
                if (session.continueReplayPending == id) session.continueReplayPending = null
                session.continueReplayQueue.clear()
                session.state = session.state.copy(error = "Could not continue archive playback")
                if (session.continueReplayRestoreLivefeed) {
                    session.continueReplayRestoreLivefeed = false
                    sendEffectiveLivefeedLocked(session)
                }
            }
            publish()
            return
        }
    }

    fun downloadCall(profileId: String, callId: Long) {
        if (callId <= 0) return
        val session = sessions[profileId] ?: return
        val context = appContext ?: return
        val pin = session.profile.pin.trim()
        if (pin.isBlank()) {
            showDownloadToast(context, "Sign in to download call audio")
            return
        }

        scope.launch {
            try {
                val request = Request.Builder()
                    .url(callAudioDownloadUrl(session.profile.baseUrl, callId))
                    .header("Authorization", "Bearer $pin")
                    .get()
                    .build()
                keyHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body.string().take(160).trim()
                        throw IllegalStateException(
                            if (detail.isBlank()) "Download failed (HTTP ${response.code})"
                            else "Download failed (HTTP ${response.code}): $detail"
                        )
                    }
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) throw IllegalStateException("Server returned empty call audio")
                    val mime = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    val fileName = callDownloadFilename(
                        response.header("Content-Disposition"),
                        callId,
                        mime
                    )
                    val savedTo = saveDownloadedAudio(context, fileName, mime, bytes)
                    showDownloadToast(context, "Saved $fileName to $savedTo")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showDownloadToast(
                    context,
                    error.message?.takeIf { it.isNotBlank() } ?: "Call audio download failed"
                )
            }
        }
    }

    internal fun callAudioDownloadUrl(baseUrl: String, callId: Long): String =
        "${httpOrigin(baseUrl)}/api/calls/$callId/audio"

    internal fun callDownloadFilename(
        contentDisposition: String?,
        callId: Long,
        mime: String?
    ): String {
        val fromHeader = contentDisposition
            ?.let { Regex("""filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(it) }
            ?.let { match -> match.groups[1]?.value ?: match.groups[2]?.value }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val fallbackExtension = when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            else -> "bin"
        }
        val candidate = fromHeader ?: "FatLine-call-$callId.$fallbackExtension"
        return candidate
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[^A-Za-z0-9._() -]"""), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "FatLine-call-$callId.$fallbackExtension" }
    }

    private fun saveDownloadedAudio(
        context: Context,
        fileName: String,
        mime: String?,
        bytes: ByteArray
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FatLine")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create a Downloads entry")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not open the Downloads file")
                val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, completed, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            return "Downloads/FatLine"
        }

        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads"))
            .resolve("FatLine")
            .apply { mkdirs() }
        val target = uniqueDownloadFile(dir, fileName)
        target.writeBytes(bytes)
        return target.parentFile?.absolutePath ?: dir.absolutePath
    }

    private fun uniqueDownloadFile(dir: File, fileName: String): File {
        val initial = File(dir, fileName)
        if (!initial.exists()) return initial
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = File(dir, "$stem ($suffix)$extension")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun showDownloadToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
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
        session.scanListSaveJob?.cancel()
        session.favoriteSaveJob?.cancel()
        session.alertRefreshJob?.cancel()
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
            session.scanListSaveJob = null
            session.favoriteSaveJob = null
            session.alertRefreshJob = null
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
                notifyConnectionLoss(
                    session,
                    if (networkAvailable) message else "Network unavailable"
                )
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
                notifyConnectionLoss(
                    session,
                    if (networkAvailable) reason.ifBlank { "Connection closed" } else "Network unavailable"
                )
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
            notifyConnectionLoss(
                session,
                if (networkAvailable) error.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
                else "Network unavailable"
            )
            scheduleReconnect(session, generation)
        }
    }

    private fun notifyConnectionLoss(session: Session, detail: String) {
        val shouldNotify = synchronized(session) {
            if (!isCurrent(session) || !session.hasConnected || session.disconnectNotified) {
                false
            } else {
                session.disconnectNotified = true
                true
            }
        }
        if (!shouldNotify) return
        appContext?.let { context ->
            val notificationId = ("connection-loss:" + session.profile.id).hashCode() and Int.MAX_VALUE
            AlertNotifier.postConnectionLoss(
                context,
                session.profile.id,
                session.profile.name,
                detail.ifBlank { "Scanner connection lost" },
                notificationId
            )
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
                val callFlag = envelope.flag?.toString()
                scope.launch { session.callMutex.withLock { processCall(session, payload, generation, callFlag) } }
            }
            ThinLineProtocol.LIST_CALL -> handleHistory(session, envelope.payload as? JSONObject ?: return)
            ThinLineProtocol.ALERT -> handleAlert(session, envelope.payload)
            ThinLineProtocol.INCIDENT -> scheduleAlertRefresh(session)
            ThinLineProtocol.LISTENER_COUNT -> ThinLineProtocol.parseListenerCount(envelope.payload)?.let { count ->
                synchronized(session) {
                    session.state = session.state.copy(listenerCount = count)
                }
                publish()
            }
            ThinLineProtocol.PIN_SET -> syncServerPin(session, envelope.payload)
            ThinLineProtocol.ERROR -> {
                var continueAfterError = false
                synchronized(session) {
                    session.continueReplayPending?.let { failedId ->
                        session.pendingReplay.remove(failedId)
                        session.continueReplayPending = null
                        continueAfterError = true
                    }
                    session.state = session.state.copy(error = envelope.payload?.toString() ?: "Server error")
                }
                publish()
                if (continueAfterError) requestNextContinueReplay(session)
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

    private fun syncServerPin(session: Session, payload: Any?) {
        val pin = ThinLineProtocol.parsePinSet(payload) ?: return
        val updated = synchronized(session) {
            if (pin == session.profile.pin) {
                null
            } else {
                val profile = session.profile.copy(pin = pin)
                session.profile = profile
                session.state = session.state.copy(profile = profile)
                profile
            }
        } ?: return

        val saved = runCatching {
            profileStore?.updatePin(updated.id, pin)
                ?: throw IllegalStateException("Profile store is unavailable")
        }
        if (saved.isFailure) {
            synchronized(session) {
                session.state = session.state.copy(
                    error = "Server updated the PIN, but secure local storage failed"
                )
            }
        } else {
            _profileCredentialUpdates.tryEmit(updated.id)
        }
        publish()
    }

    private fun handleConfig(session: Session, payload: JSONObject) {
        val parsed = ThinLineProtocol.parseSystems(payload)
        val options = payload.optJSONObject("options")
        val autoEnableNewTalkgroups = options?.optBoolean("autoEnableNewTalkgroups", false) == true
        val incidentMappingEnabled = options?.optBoolean("incidentMappingEnabled", false) == true
        val uiAccentColor = options?.optString("uiAccentColor")?.trim()?.takeIf { it.isNotBlank() }
        val userSettings = payload.optJSONObject("userSettings")
        val userUiAccentColor = userSettings
            ?.opt("uiAccentColor")
            ?.let { it as? String }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val tagColors = parseTagColors(userSettings)
        val livefeedBacklogMinutes = parseLivefeedBacklogMinutes(userSettings)
        val serverFavorites = parseFavoriteSelection(userSettings, parsed)
        val favoriteSavePending = synchronized(session) { session.favoriteSaveJob?.isActive == true }
        val applyServerFavorites = serverFavorites != null && !favoriteSavePending
        if (applyServerFavorites) {
            channelStore?.replaceFavorites(session.profile.id, serverFavorites!!.channels)
        }
        val systems = channelStore?.apply(
            session.profile.id,
            parsed,
            autoEnableNewTalkgroups
        ) ?: parsed
        val hiddenSystemRefs = channelStore?.hiddenSystems(session.profile.id).orEmpty()
        val scanLists = ThinLineProtocol.parseScanLists(payload)
        val encrypted = options?.optBoolean("audioEncryptionEnabled", false) == true
        val relayUrl = options?.optString("relayServerURL")?.takeIf { it.isNotBlank() }
        val token = options?.optString("audioClientToken")?.takeIf { it.isNotBlank() }
        val showListenersCount = payload.optBoolean("showListenersCount", false)
        val time12hFormat = payload.optBoolean("time12hFormat", false)
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
            session.hasConnected = true
            session.disconnectNotified = false
            session.state = session.state.copy(
                status = ConnectionStatus.CONNECTED,
                statusText = when {
                    session.state.paused -> "Connected — paused"
                    needsKeyExchange -> "Connected — securing audio"
                    else -> "Connected"
                },
                systems = systems,
                hiddenSystemRefs = hiddenSystemRefs,
                favoriteSystemRefs = if (applyServerFavorites) {
                    serverFavorites!!.systemRefs
                } else {
                    session.state.favoriteSystemRefs
                },
                favoriteTags = if (applyServerFavorites) {
                    serverFavorites!!.tags
                } else {
                    session.state.favoriteTags
                },
                scanLists = if (session.state.scanListSyncing) session.state.scanLists else scanLists,
                audioEncryptionEnabled = encrypted,
                encryptionReady = !encrypted || session.masterKey != null,
                showListenersCount = showListenersCount,
                listenerCount = if (showListenersCount) session.state.listenerCount else 0,
                incidentMappingEnabled = incidentMappingEnabled,
                time12hFormat = time12hFormat,
                uiAccentColor = uiAccentColor,
                userUiAccentColor = userUiAccentColor,
                tagColors = if (session.state.userSettingsSaving) {
                    session.state.tagColors
                } else {
                    tagColors
                },
                livefeedBacklogMinutes = if (session.state.userSettingsSaving) {
                    session.state.livefeedBacklogMinutes
                } else {
                    livefeedBacklogMinutes
                },
                error = null
            )
            if (session.state.paused) session.socket?.stopLivefeed()
            else sendEffectiveLivefeedLocked(session)
        }
        publish()
        if (session.profile.pin.isNotBlank()) {
            scheduleAlertRefresh(session)
            refreshAlertPreferences(session.profile.id)
            refreshAlertKeywordLists(session.profile.id)
        }
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

    private fun sendEffectiveLivefeedLocked(session: Session) {
        if (session.state.paused || session.state.status != ConnectionStatus.CONNECTED) return
        session.socket?.sendLivefeed(effectiveLivefeedSystems(session.state))
    }

    internal fun effectiveLivefeedSystems(state: ServerScannerState): List<SystemConfig> =
        state.systems.map { system ->
            val systemAllowed = state.holdSystemRef?.let { it == system.systemRef } ?: true
            system.copy(
                talkgroups = system.talkgroups.map { talkgroup ->
                    val talkgroupAllowed = state.hold?.let { it == talkgroup.key } ?: true
                    talkgroup.copy(
                        enabled = talkgroup.enabled &&
                            systemAllowed &&
                            talkgroupAllowed &&
                            talkgroup.key !in state.avoided
                    )
                }
            )
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
            val combined = (session.state.history + calls).associateBy { it.id }.values
            val merged = if (session.state.historySort < 0) {
                combined.sortedByDescending(::callSortKey)
            } else {
                combined.sortedBy(::callSortKey)
            }
            session.historyOffset += calls.size
            session.state = session.state.copy(
                history = merged,
                historyHasMore = payload.optBoolean("hasMore", false)
            )
        }
        publish()
    }

    private fun metadataCall(session: Session, payload: JSONObject): RadioCall {
        val systemRef = payload.optLong("system")
        val talkgroupRef = payload.optLong("talkgroup")
        val (systemLabel, talkgroupLabel) = labels(session.state.systems, systemRef, talkgroupRef)
        val sources = resolveCallSources(session.state.systems, systemRef, payload)
        val sourceRef = sources.firstNotNullOfOrNull { it.sourceRef }
            ?: payload.optLong("source").takeIf { it > 0 }
        return RadioCall(
            profileId = session.profile.id,
            serverName = session.profile.name,
            id = payload.optLong("id"),
            systemRef = systemRef,
            talkgroupRef = talkgroupRef,
            systemLabel = systemLabel,
            talkgroupLabel = talkgroupLabel,
            dateTime = payload.optString("dateTime"),
            sourceRef = sourceRef,
            sourceLabel = sources.firstOrNull()?.display
                ?: sourceRef?.let { formatUnitDisplay(session.state.systems, systemRef, it) },
            sources = sources,
            frequency = payload.optLong("frequency").takeIf { it > 0 },
            durationSeconds = payload.optDouble("duration").takeIf { !it.isNaN() && it > 0 }
        )
    }

    private fun processCall(
        session: Session,
        payload: JSONObject,
        generation: Long? = null,
        callFlag: String? = null
    ) {
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
        val sources = resolveCallSources(session.state.systems, systemRef, payload)
        val sourceRef = sources.firstNotNullOfOrNull { it.sourceRef }
            ?: payload.optLong("source").takeIf { it > 0 }
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
            sourceRef = sourceRef,
            sourceLabel = sources.firstOrNull()?.display
                ?: sourceRef?.let { formatUnitDisplay(session.state.systems, systemRef, it) },
            sources = sources,
            frequency = payload.optLong("frequency").takeIf { it > 0 },
            durationSeconds = payload.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            encryptedAudio = encrypted
        )

        val shouldPlay: Boolean
        var replayRequested = false
        synchronized(session) {
            val locallyRequestedReplay = session.pendingReplay.remove(id)
            replayRequested = callFlag == ThinLineProtocol.PLAY_FLAG || locallyRequestedReplay
            val enabled = session.state.systems.flatMap { it.talkgroups }.firstOrNull { it.key == key }?.enabled == true
            val talkgroupHoldAllows = session.state.hold?.let { it == key } ?: true
            val systemHoldAllows = session.state.holdSystemRef?.let { it == systemRef } ?: true
            val avoided = key in session.state.avoided
            shouldPlay = replayRequested || (!session.state.paused && enabled && talkgroupHoldAllows && systemHoldAllows && !avoided)
            val merged = if (matchesHistoryFilter(session.state, call)) {
                val combined = (session.state.history + call).associateBy { it.id }.values
                if (session.state.historySort < 0) {
                    combined.sortedByDescending(::callSortKey)
                } else {
                    combined.sortedBy(::callSortKey)
                }
            } else {
                session.state.history
            }
            val recent = if (replayRequested) {
                session.state.recentCalls
            } else {
                (listOf(call) + session.state.recentCalls.filterNot { it.id == call.id }).take(10)
            }
            session.state = session.state.copy(
                history = merged,
                recentCalls = recent,
                lastCall = if (replayRequested) session.state.lastCall else call
            )
        }
        publish()
        if (shouldPlay && path != null) ScannerService.enqueue(context, call, liveFeed = !replayRequested)

        val continueAfterCall = synchronized(session) {
            if (session.continueReplayPending == id) {
                session.continueReplayPending = null
                true
            } else {
                false
            }
        }
        if (continueAfterCall) requestNextContinueReplay(session)
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
            AlertNotifier.post(it, session.profile.id, session.profile.name, "${session.profile.name}: $title", body, notificationId)
        }
        scheduleAlertRefresh(session)
    }

    internal fun parseTranscripts(profile: ServerProfile, raw: JSONArray): List<TranscriptRecord> =
        buildList {
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val callId = item.optLong("callId").takeIf { it > 0 } ?: continue
                add(
                    TranscriptRecord(
                        profileId = profile.id,
                        serverName = profile.name,
                        callId = callId,
                        systemId = item.optLong("systemId").takeIf { it > 0 },
                        talkgroupId = item.optLong("talkgroupId").takeIf { it > 0 },
                        systemLabel = item.optString("systemLabel").trim().takeIf { it.isNotBlank() },
                        talkgroupLabel = item.optString("talkgroupLabel").trim().takeIf { it.isNotBlank() },
                        talkgroupName = item.optString("talkgroupName").trim().takeIf { it.isNotBlank() },
                        transcript = item.optString("transcript"),
                        reviewedTranscript = item.optString("reviewedTranscript").trim().takeIf { it.isNotBlank() },
                        transcriptionStatus = item.optString("transcriptionStatus").trim().takeIf { it.isNotBlank() },
                        timestamp = item.optLong("timestamp").takeIf { it > 0 },
                        alertSummary = item.optString("alertSummary").trim().takeIf { it.isNotBlank() }
                    )
                )
            }
        }.sortedByDescending { it.timestamp ?: 0L }

    internal fun parseServerAlerts(profile: ServerProfile, raw: JSONArray): List<ScannerAlert> {
        fun stringList(value: Any?): List<String> {
            val array = when (value) {
                is JSONArray -> value
                is String -> runCatching { JSONArray(value) }.getOrNull()
                else -> null
            } ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }

        return buildList {
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val alertId = item.optLong("alertId").takeIf { it > 0 }
                val callId = item.optLong("callId").takeIf { it > 0 }
                val alertType = item.optString("alertType").trim().takeIf { it.isNotBlank() }
                val systemLabel = item.optString("systemLabel").trim().takeIf { it.isNotBlank() }
                val talkgroupLabel = item.optString("talkgroupLabel").trim().takeIf { it.isNotBlank() }
                val talkgroupName = item.optString("talkgroupName").trim().takeIf { it.isNotBlank() }
                val toneSets = stringList(item.opt("matchedToneSetNames")).ifEmpty {
                    item.optString("matchedToneSetName").trim().takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
                }
                val keywords = stringList(item.opt("keywordsMatched"))
                val transcript = item.optString("transcript").trim().takeIf { it.isNotBlank() }
                    ?: item.optString("transcriptSnippet").trim().takeIf { it.isNotBlank() }
                val summary = item.optString("alertSummary").trim().takeIf { it.isNotBlank() }
                val incidentAddress = item.optString("incidentAddress").trim().takeIf { it.isNotBlank() }
                val incidentNature = item.optString("incidentNature").trim().takeIf { it.isNotBlank() }
                val createdAt = item.optLong("createdAt").takeIf { it > 0 }
                val lat = item.optDouble("incidentLat", Double.NaN).takeIf { !it.isNaN() }
                val lon = item.optDouble("incidentLon", Double.NaN).takeIf { !it.isNaN() }
                val titleBase = talkgroupLabel ?: talkgroupName ?: systemLabel ?: "Scanner alert"
                val title = alertType?.let { "$titleBase · $it" } ?: titleBase
                val body = summary
                    ?: transcript
                    ?: incidentNature
                    ?: toneSets.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: keywords.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: "Alert received"

                add(
                    ScannerAlert(
                        profileId = profile.id,
                        serverName = profile.name,
                        title = title,
                        body = body,
                        dateTime = createdAt?.let { Instant.ofEpochMilli(it).toString() },
                        alertId = alertId,
                        callId = callId,
                        alertType = alertType,
                        systemLabel = systemLabel,
                        talkgroupLabel = talkgroupLabel,
                        talkgroupName = talkgroupName,
                        matchedToneSets = toneSets,
                        keywords = keywords,
                        transcript = transcript,
                        summary = summary,
                        incidentAddress = incidentAddress,
                        incidentNature = incidentNature,
                        incidentLat = lat,
                        incidentLon = lon,
                        createdAt = createdAt
                    )
                )
            }
        }.sortedByDescending { it.createdAt ?: 0L }
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

    internal fun resolveCallSources(
        systems: List<SystemConfig>,
        systemRef: Long,
        payload: JSONObject
    ): List<CallSource> {
        val rawSources = payload.optJSONArray("sources")
        val parsed = mutableListOf<CallSource>()
        val seen = mutableSetOf<Long>()
        val fallbackTags = mutableListOf<Pair<Int, String>>()

        if (rawSources != null) {
            val ordered = buildList {
                for (i in 0 until rawSources.length()) {
                    rawSources.optJSONObject(i)?.let(::add)
                }
            }.sortedBy { it.optInt("pos", 0) }

            ordered.forEach { item ->
                val position = item.optInt("pos", 0)
                val source = item.optLong("src").takeIf { it > 0 }
                val tag = item.optString("tag").trim().takeIf { it.isNotBlank() }
                if (source != null) {
                    if (seen.add(source)) {
                        parsed += CallSource(
                            position = position,
                            sourceRef = source,
                            tag = tag,
                            display = formatUnitDisplay(systems, systemRef, source, tag)
                        )
                    }
                } else if (tag != null) {
                    fallbackTags += position to tag
                }
            }
        }

        if (parsed.isNotEmpty()) return parsed
        fallbackTags.minByOrNull { it.first }?.let { (position, tag) ->
            return listOf(CallSource(position = position, tag = tag, display = tag))
        }

        val legacy = payload.optLong("source").takeIf { it > 0 } ?: return emptyList()
        return listOf(
            CallSource(
                sourceRef = legacy,
                display = formatUnitDisplay(systems, systemRef, legacy)
            )
        )
    }

    private fun formatUnitDisplay(
        systems: List<SystemConfig>,
        systemRef: Long,
        sourceRef: Long,
        tag: String? = null
    ): String {
        val sourceText = sourceRef.toString()
        val dynamicAlias = tag?.trim()?.takeIf { it.isNotBlank() && it != sourceText }
        val configuredAlias = systems
            .firstOrNull { it.systemRef == systemRef }
            ?.units
            ?.firstOrNull { it.matches(sourceRef) }
            ?.label
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != sourceText }
        val alias = dynamicAlias ?: configuredAlias
        return alias?.let { "$it | $sourceText" } ?: sourceText
    }

    internal fun matchesHistoryFilter(state: ServerScannerState, call: RadioCall): Boolean {
        val systemMatches = state.historySystemRef?.let { call.systemRef == it } ?: true
        val talkgroupMatches = state.historyTalkgroupRef?.let { call.talkgroupRef == it } ?: true
        val talkgroup = state.systems
            .firstOrNull { it.systemRef == call.systemRef }
            ?.talkgroups
            ?.firstOrNull { it.talkgroupRef == call.talkgroupRef }
        val groupMatches = state.historyGroup?.let { selected ->
            talkgroup?.groups?.any { it.equals(selected, ignoreCase = true) } == true
        } ?: true
        val tagMatches = state.historyTag?.let { selected ->
            talkgroup?.tag?.equals(selected, ignoreCase = true) == true
        } ?: true
        val dateMatches = state.historyDate?.let { selected ->
            val cutoff = runCatching { Instant.parse(selected).toEpochMilli() }.getOrNull()
            val callTime = runCatching { Instant.parse(call.dateTime).toEpochMilli() }.getOrNull()
            cutoff != null && callTime != null && callTime >= cutoff
        } ?: true
        return systemMatches && talkgroupMatches && groupMatches && tagMatches && dateMatches
    }
    private fun callSortKey(call: RadioCall): Long = runCatching { Instant.parse(call.dateTime).toEpochMilli() }.getOrDefault(0L)

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun publish() {
        val serverMap = sessions.values.associate { it.profile.id to it.state }
        val history = serverMap.values.flatMap { it.history }.sortedByDescending(::callSortKey).take(500)
        val alerts = serverMap.values.flatMap { it.alerts }.sortedByDescending { it.createdAt ?: 0L }.take(500)
        _state.value = ScannerState(serverMap, history, alerts)
    }
}
