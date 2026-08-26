package dev.scanrelay.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.scanrelay.app.data.ChannelStore
import dev.scanrelay.app.data.ProfileStore
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.net.ScannerRepository
import dev.scanrelay.app.playback.ScannerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val channelStore = ChannelStore(application)
    private val _profiles = MutableStateFlow(profileStore.load())

    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()
    val scannerState = ScannerRepository.state

    init { ScannerRepository.initialize(application) }

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

    fun connect(profile: ServerProfile) = ScannerService.connect(getApplication(), saveProfile(profile).id)
    fun disconnect(profileId: String) = ScannerService.disconnect(getApplication(), profileId)
    fun disconnectAll() = ScannerService.disconnectAll(getApplication())
    fun setTalkgroup(profileId: String, systemRef: Long, talkgroupRef: Long, enabled: Boolean) = ScannerRepository.setTalkgroupEnabled(profileId, systemRef, talkgroupRef, enabled)
    fun setAllTalkgroups(profileId: String, enabled: Boolean) = ScannerRepository.setAllEnabled(profileId, enabled)
    fun setFavorite(profileId: String, systemRef: Long, talkgroupRef: Long, favorite: Boolean) = ScannerRepository.setFavorite(profileId, systemRef, talkgroupRef, favorite)
    fun setHold(profileId: String, systemRef: Long, talkgroupRef: Long) = ScannerRepository.setHold(profileId, ChannelKey(systemRef, talkgroupRef))
    fun clearHold(profileId: String) = ScannerRepository.setHold(profileId, null)
    fun avoid(profileId: String, systemRef: Long, talkgroupRef: Long, avoided: Boolean = true) = ScannerRepository.avoid(profileId, ChannelKey(systemRef, talkgroupRef), avoided)
    fun clearAvoids(profileId: String) = ScannerRepository.clearAvoids(profileId)
    fun requestHistory(profileId: String, reset: Boolean = true) = ScannerRepository.requestHistory(profileId, reset)
    fun replay(profileId: String, callId: Long) = ScannerRepository.replay(profileId, callId)
    fun skip() = ScannerRepository.skip()
}
