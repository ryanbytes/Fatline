package dev.scanrelay.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.scanrelay.app.data.ChannelStore
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.net.ScannerRepository
import dev.scanrelay.app.playback.ScannerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI

data class AccountLoginState(
    val working: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val channelStore = ChannelStore(application)
    private val _profiles = MutableStateFlow(profileStore.load())
    private val _accountLogin = MutableStateFlow(AccountLoginState())
    private val loginHttpClient = OkHttpClient()

    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()
    val accountLogin: StateFlow<AccountLoginState> = _accountLogin.asStateFlow()
    val scannerState = ScannerRepository.state

    init {
        ScannerRepository.initialize(application)
        viewModelScope.launch {
            ScannerRepository.profileCredentialUpdates.collect {
                _profiles.value = profileStore.load()
            }
        }
    }

    fun saveProfile(profile: ServerProfile): ServerProfile {
        val clean = profile.copy(
            name = profile.name.trim().ifBlank { "Scanner" },
            baseUrl = profile.baseUrl.trim().trimEnd('/')
        )
        profileStore.save(clean)
        _profiles.value = profileStore.load()
        return clean
    }

    fun deleteProfile(profileId: String) {
        ScannerService.disconnect(getApplication(), profileId)
        profileStore.delete(profileId)
        channelStore.deleteProfile(profileId)
        _profiles.value = profileStore.load()
    }

    fun loginAndConnect(profile: ServerProfile, username: String, password: String) {
        if (profile.baseUrl.isBlank()) {
            _accountLogin.value = AccountLoginState(error = "Server URL is required")
            return
        }
        if (username.isBlank() || password.isBlank()) {
            _accountLogin.value = AccountLoginState(error = "Username / email and password are required")
            return
        }

        _accountLogin.value = AccountLoginState(working = true, message = "Signing in…")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val origin = httpOrigin(profile.baseUrl)
                val email = username.trim().lowercase()
                val settings = runCatching { getJson("$origin/api/registration-settings") }.getOrNull()
                val pin = if (settings?.optBoolean("centralManagementEnabled", false) == true) {
                    val login = postJson(
                        "$origin/api/cm-auth/login",
                        JSONObject().put("email", email).put("password", password)
                    )
                    val token = login.optString("token").trim()
                    require(token.isNotBlank()) { "Login succeeded but no account session token was returned" }
                    val session = postJson(
                        "$origin/api/cm-auth/session",
                        JSONObject().put("token", token).put("returnUrl", origin)
                    )
                    if (session.optBoolean("needsSubscription", false)) {
                        throw IllegalStateException(
                            session.optString("message").takeIf { it.isNotBlank() }
                                ?: "This account does not currently have scanner access"
                        )
                    }
                    session.optString("pin").trim()
                } else {
                    val login = postJson(
                        "$origin/api/user/login",
                        JSONObject().put("email", email).put("password", password)
                    )
                    login.optJSONObject("user")?.optString("pin").orEmpty().trim()
                }

                require(pin.isNotBlank()) { "Login succeeded but the server did not return a scanner PIN" }
                val saved = saveProfile(profile.copy(pin = pin))
                ScannerService.connect(getApplication(), saved.id)
                _accountLogin.value = AccountLoginState(message = "Signed in")
            }.onFailure { error ->
                _accountLogin.value = AccountLoginState(
                    error = error.message?.takeIf { it.isNotBlank() } ?: "Login failed"
                )
            }
        }
    }

    fun clearAccountLoginStatus() {
        _accountLogin.value = AccountLoginState()
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return executeJson(request)
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request)
    }

    private fun executeJson(request: Request): JSONObject =
        loginHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                val message = json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
                    ?: "Login failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
            json
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
    fun connect(profile: ServerProfile) = ScannerService.connect(getApplication(), saveProfile(profile).id)
    fun disconnect(profileId: String) = ScannerService.disconnect(getApplication(), profileId)
    fun disconnectAll() = ScannerService.disconnectAll(getApplication())
    fun setTalkgroup(profileId: String, systemRef: Long, talkgroupRef: Long, enabled: Boolean) = ScannerRepository.setTalkgroupEnabled(profileId, systemRef, talkgroupRef, enabled)
    fun setSystemTalkgroups(profileId: String, systemRef: Long, enabled: Boolean) = ScannerRepository.setSystemEnabled(profileId, systemRef, enabled)
    fun setSystemHidden(profileId: String, systemRef: Long, hidden: Boolean) =
        ScannerRepository.setSystemHidden(profileId, systemRef, hidden)
    fun setAllTalkgroups(profileId: String, enabled: Boolean) = ScannerRepository.setAllEnabled(profileId, enabled)
    fun setChannels(profileId: String, keys: Collection<ChannelKey>, enabled: Boolean) = ScannerRepository.setChannelsEnabled(profileId, keys, enabled)
    fun createScanList(profileId: String, name: String) = ScannerRepository.createScanList(profileId, name)
    fun renameScanList(profileId: String, listId: String, name: String) = ScannerRepository.renameScanList(profileId, listId, name)
    fun deleteScanList(profileId: String, listId: String) = ScannerRepository.deleteScanList(profileId, listId)
    fun setScanListChannel(profileId: String, listId: String, key: ChannelKey, included: Boolean) =
        ScannerRepository.setScanListChannel(profileId, listId, key, included)
    fun setPaused(profileId: String, paused: Boolean) = ScannerRepository.setPaused(profileId, paused)
    fun setFavorite(profileId: String, systemRef: Long, talkgroupRef: Long, favorite: Boolean) = ScannerRepository.setFavorite(profileId, systemRef, talkgroupRef, favorite)
    fun setHold(profileId: String, systemRef: Long, talkgroupRef: Long) = ScannerRepository.setHold(profileId, ChannelKey(systemRef, talkgroupRef))
    fun setSystemHold(profileId: String, systemRef: Long?) = ScannerRepository.setSystemHold(profileId, systemRef)
    fun clearHold(profileId: String) = ScannerRepository.clearHold(profileId)
    fun avoid(profileId: String, systemRef: Long, talkgroupRef: Long, avoided: Boolean = true) = ScannerRepository.avoid(profileId, ChannelKey(systemRef, talkgroupRef), avoided)
    fun clearAvoids(profileId: String) = ScannerRepository.clearAvoids(profileId)
    fun requestHistory(
        profileId: String,
        reset: Boolean = true,
        systemRef: Long? = null,
        talkgroupRef: Long? = null
    ) = ScannerRepository.requestHistory(profileId, reset, systemRef, talkgroupRef)
    fun refreshAlerts(profileId: String) = ScannerRepository.refreshAlerts(profileId)
    fun refreshTranscripts(
        profileId: String,
        offset: Int = 0,
        limit: Int = 50,
        systemId: Long? = null,
        talkgroupId: Long? = null,
        dateFrom: Long? = null,
        dateTo: Long? = null,
        search: String? = null
    ) = ScannerRepository.refreshTranscripts(
        profileId = profileId,
        offset = offset,
        limit = limit,
        systemId = systemId,
        talkgroupId = talkgroupId,
        dateFrom = dateFrom,
        dateTo = dateTo,
        search = search
    )
    fun refreshSystemAlerts(profileId: String) = ScannerRepository.refreshSystemAlerts(profileId)
    fun dismissSystemAlert(profileId: String, alertId: Long) =
        ScannerRepository.dismissSystemAlert(profileId, alertId)
    fun refreshAlertPreferences(profileId: String) = ScannerRepository.refreshAlertPreferences(profileId)
    fun refreshAlertKeywordLists(profileId: String) = ScannerRepository.refreshAlertKeywordLists(profileId)
    fun createAlertKeywordList(profileId: String, label: String, description: String, rawKeywords: String) =
        ScannerRepository.createAlertKeywordList(profileId, label, description, rawKeywords)
    fun updateAlertKeywordList(profileId: String, listId: Long, label: String, description: String, rawKeywords: String) =
        ScannerRepository.updateAlertKeywordList(profileId, listId, label, description, rawKeywords)
    fun deleteAlertKeywordList(profileId: String, listId: Long) =
        ScannerRepository.deleteAlertKeywordList(profileId, listId)
    fun setAlertKeywordLists(profileId: String, key: ChannelKey, keywordListIds: Collection<Long>) =
        ScannerRepository.setAlertKeywordLists(profileId, key, keywordListIds)
    fun setAlertToneSets(profileId: String, key: ChannelKey, toneSetIds: Collection<String>) =
        ScannerRepository.setAlertToneSets(profileId, key, toneSetIds)
    fun setAlertKeywords(profileId: String, key: ChannelKey, rawKeywords: String) =
        ScannerRepository.setAlertKeywords(profileId, key, rawKeywords)
    fun setAlertPreference(
        profileId: String,
        key: ChannelKey,
        alertEnabled: Boolean? = null,
        toneAlerts: Boolean? = null,
        keywordAlerts: Boolean? = null
    ) = ScannerRepository.setAlertPreference(profileId, key, alertEnabled, toneAlerts, keywordAlerts)
    fun replay(profileId: String, callId: Long) = ScannerRepository.replay(profileId, callId)
    fun continueHistory(profileId: String, callId: Long) = ScannerRepository.continueHistory(profileId, callId)
    fun downloadCall(profileId: String, callId: Long) = ScannerRepository.downloadCall(profileId, callId)
    fun skip() = ScannerRepository.skip()
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

}
