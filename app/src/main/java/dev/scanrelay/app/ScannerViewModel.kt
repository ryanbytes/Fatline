package dev.scanrelay.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.scanrelay.app.data.ChannelStore
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.AccountProfile
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.net.ScannerRepository
import dev.scanrelay.app.playback.ScannerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

data class AccountProfileLoadState(
    val loading: Boolean = false,
    val account: AccountProfile? = null,
    val error: String? = null
)

data class PasswordRecoveryState(
    val working: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

data class AccountPasswordChangeState(
    val working: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val channelStore = ChannelStore(application)
    private val _profiles = MutableStateFlow(profileStore.load())
    private val _accountLogin = MutableStateFlow(AccountLoginState())
    private val _accountProfiles = MutableStateFlow<Map<String, AccountProfileLoadState>>(emptyMap())
    private val _passwordRecovery = MutableStateFlow(PasswordRecoveryState())
    private val _accountPasswordChange = MutableStateFlow(AccountPasswordChangeState())
    private val loginHttpClient = OkHttpClient()

    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()
    val accountLogin: StateFlow<AccountLoginState> = _accountLogin.asStateFlow()
    val accountProfiles: StateFlow<Map<String, AccountProfileLoadState>> = _accountProfiles.asStateFlow()
    val passwordRecovery: StateFlow<PasswordRecoveryState> = _passwordRecovery.asStateFlow()
    val accountPasswordChange: StateFlow<AccountPasswordChangeState> = _accountPasswordChange.asStateFlow()
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
        _accountProfiles.update { it - profileId }
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

    fun refreshAccountProfile(profile: ServerProfile) {
        val pin = profile.pin.trim()
        if (pin.isBlank()) {
            _accountProfiles.update {
                it + (profile.id to AccountProfileLoadState(error = "Sign in to load account details"))
            }
            return
        }

        val previous = _accountProfiles.value[profile.id]?.account
        _accountProfiles.update {
            it + (profile.id to AccountProfileLoadState(loading = true, account = previous))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val origin = httpOrigin(profile.baseUrl)
                val request = Request.Builder()
                    .url("$origin/api/account")
                    .header("Authorization", "Bearer $pin")
                    .get()
                    .build()
                dev.scanrelay.app.model.parseAccountProfile(executeJson(request))
            }.onSuccess { account ->
                if (_profiles.value.any { it.id == profile.id }) {
                    _accountProfiles.update {
                        it + (profile.id to AccountProfileLoadState(account = account))
                    }
                }
            }.onFailure { error ->
                if (_profiles.value.any { it.id == profile.id }) {
                    _accountProfiles.update {
                        it + (
                            profile.id to AccountProfileLoadState(
                                account = previous,
                                error = error.message?.takeIf(String::isNotBlank)
                                    ?: "Account details could not be loaded"
                            )
                        )
                    }
                }
            }
        }
    }

    fun requestPasswordReset(baseUrl: String, email: String) {
        if (baseUrl.isBlank()) {
            _passwordRecovery.value = PasswordRecoveryState(error = "Server URL is required")
            return
        }
        if (email.isBlank()) {
            _passwordRecovery.value = PasswordRecoveryState(error = "Email is required")
            return
        }

        _passwordRecovery.value = PasswordRecoveryState(working = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val origin = httpOrigin(baseUrl)
                postJson(
                    "$origin/api/user/forgot-password",
                    JSONObject().put("email", email.trim().lowercase())
                )
            }.onSuccess { response ->
                _passwordRecovery.value = PasswordRecoveryState(
                    message = response.optString("message").takeIf(String::isNotBlank)
                        ?: "If an account with that email exists, a password reset code has been sent."
                )
            }.onFailure { error ->
                _passwordRecovery.value = PasswordRecoveryState(
                    error = error.message?.takeIf(String::isNotBlank) ?: "Reset code request failed"
                )
            }
        }
    }

    fun resetAccountPassword(baseUrl: String, email: String, code: String, newPassword: String) {
        if (baseUrl.isBlank()) {
            _passwordRecovery.value = PasswordRecoveryState(error = "Server URL is required")
            return
        }
        if (email.isBlank() || code.isBlank() || newPassword.isBlank()) {
            _passwordRecovery.value = PasswordRecoveryState(
                error = "Email, reset code, and new password are required"
            )
            return
        }

        _passwordRecovery.value = PasswordRecoveryState(working = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val origin = httpOrigin(baseUrl)
                postJson(
                    "$origin/api/user/reset-password",
                    JSONObject()
                        .put("email", email.trim().lowercase())
                        .put("code", code.trim())
                        .put("newPassword", newPassword)
                )
            }.onSuccess { response ->
                _passwordRecovery.value = PasswordRecoveryState(
                    message = response.optString("message").takeIf(String::isNotBlank)
                        ?: "Password reset successful"
                )
            }.onFailure { error ->
                _passwordRecovery.value = PasswordRecoveryState(
                    error = error.message?.takeIf(String::isNotBlank) ?: "Password reset failed"
                )
            }
        }
    }

    fun clearPasswordRecoveryStatus() {
        _passwordRecovery.value = PasswordRecoveryState()
    }

    fun requestAccountPasswordChangeCode(baseUrl: String, pin: String) {
        if (baseUrl.isBlank()) {
            _accountPasswordChange.value = AccountPasswordChangeState(error = "Server URL is required")
            return
        }
        if (pin.isBlank()) {
            _accountPasswordChange.value = AccountPasswordChangeState(error = "Sign in to change the account password")
            return
        }

        _accountPasswordChange.value = AccountPasswordChangeState(working = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val origin = httpOrigin(baseUrl)
                postAuthenticatedJson(
                    "$origin/api/account/password/request-verification",
                    pin.trim(),
                    JSONObject()
                )
            }.onSuccess { response ->
                _accountPasswordChange.value = AccountPasswordChangeState(
                    message = response.optString("message").takeIf(String::isNotBlank)
                        ?: "Verification code sent to your account email"
                )
            }.onFailure { error ->
                _accountPasswordChange.value = AccountPasswordChangeState(
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "Password change code request failed"
                )
            }
        }
    }

    fun changeAccountPassword(baseUrl: String, pin: String, code: String, newPassword: String) {
        if (baseUrl.isBlank()) {
            _accountPasswordChange.value = AccountPasswordChangeState(error = "Server URL is required")
            return
        }
        if (pin.isBlank() || code.isBlank() || newPassword.isBlank()) {
            _accountPasswordChange.value = AccountPasswordChangeState(
                error = "Sign in and enter the verification code and new password"
            )
            return
        }

        _accountPasswordChange.value = AccountPasswordChangeState(working = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val origin = httpOrigin(baseUrl)
                postAuthenticatedJson(
                    "$origin/api/account/password",
                    pin.trim(),
                    JSONObject()
                        .put("newPassword", newPassword)
                        .put("code", code.trim())
                )
            }.onSuccess { response ->
                _accountPasswordChange.value = AccountPasswordChangeState(
                    message = response.optString("message").takeIf(String::isNotBlank)
                        ?: "Password updated successfully"
                )
            }.onFailure { error ->
                _accountPasswordChange.value = AccountPasswordChangeState(
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "Password change failed"
                )
            }
        }
    }

    fun clearAccountPasswordChangeStatus() {
        _accountPasswordChange.value = AccountPasswordChangeState()
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

    private fun postAuthenticatedJson(url: String, pin: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pin")
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
    fun reorderScanList(profileId: String, fromIndex: Int, toIndex: Int) =
        ScannerRepository.reorderScanList(profileId, fromIndex, toIndex)
    fun setPaused(profileId: String, paused: Boolean) = ScannerRepository.setPaused(profileId, paused)
    fun setLivefeedBacklogMinutes(profileId: String, minutes: Int) =
        ScannerRepository.setLivefeedBacklogMinutes(profileId, minutes)
    fun setTagColor(profileId: String, tag: String, color: String?) =
        ScannerRepository.setTagColor(profileId, tag, color)
    fun setFavorite(profileId: String, systemRef: Long, talkgroupRef: Long, favorite: Boolean) =
        ScannerRepository.setFavorite(profileId, systemRef, talkgroupRef, favorite)
    fun setSystemFavorite(profileId: String, systemRef: Long, favorite: Boolean) =
        ScannerRepository.setSystemFavorite(profileId, systemRef, favorite)
    fun setTagFavorite(profileId: String, systemRef: Long, tag: String, favorite: Boolean) =
        ScannerRepository.setTagFavorite(profileId, systemRef, tag, favorite)
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

    fun requestHistoryFiltered(
        profileId: String,
        systemRef: Long? = null,
        talkgroupRef: Long? = null,
        date: String? = null,
        group: String? = null,
        tag: String? = null,
        sort: Int = -1
    ) = ScannerRepository.requestHistory(
        profileId = profileId,
        reset = true,
        systemRef = systemRef,
        talkgroupRef = talkgroupRef,
        date = date,
        group = group,
        tag = tag,
        sort = sort
    )

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
    fun setAlertNotificationSound(profileId: String, key: ChannelKey, fileName: String) =
        ScannerRepository.setAlertNotificationSound(profileId, key, fileName)
    fun setAlertToneSetSound(profileId: String, key: ChannelKey, toneSetId: String, fileName: String) =
        ScannerRepository.setAlertToneSetSound(profileId, key, toneSetId, fileName)
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
