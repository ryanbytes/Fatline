package dev.scanrelay.app.model

import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val pin: String = ""
)

data class ChannelKey(val systemRef: Long, val talkgroupRef: Long) {
    override fun toString(): String = "$systemRef:$talkgroupRef"

    companion object {
        fun parse(value: String): ChannelKey? {
            val parts = value.split(':', limit = 2)
            if (parts.size != 2) return null
            val system = parts[0].toLongOrNull() ?: return null
            val talkgroup = parts[1].toLongOrNull() ?: return null
            return ChannelKey(system, talkgroup)
        }
    }
}

data class AlertToneSet(
    val id: String,
    val label: String
)

data class TalkgroupConfig(
    val systemRef: Long,
    val talkgroupRef: Long,
    val label: String,
    val name: String = "",
    val tag: String = "",
    val enabled: Boolean = false,
    val favorite: Boolean = false,
    val toneDetectionEnabled: Boolean = false,
    val toneSets: List<AlertToneSet> = emptyList(),
    val talkgroupId: Long? = null
) {
    val key: ChannelKey get() = ChannelKey(systemRef, talkgroupRef)
    val displayName: String
        get() = when {
            label.isNotBlank() -> label
            name.isNotBlank() -> name
            else -> "TG $talkgroupRef"
        }
}

data class UnitAlias(
    val id: Long = 0,
    val label: String,
    val unitRef: Long = 0,
    val unitFrom: Long = 0,
    val unitTo: Long = 0
) {
    fun matches(sourceRef: Long): Boolean =
        (unitFrom > 0 && unitTo > 0 && sourceRef in unitFrom..unitTo) ||
            (unitRef > 0 && unitRef == sourceRef) ||
            (unitRef <= 0 && id > 0 && id == sourceRef)
}

data class SystemConfig(
    val systemRef: Long,
    val label: String,
    val talkgroups: List<TalkgroupConfig>,
    val units: List<UnitAlias> = emptyList(),
    val systemId: Long? = null
)

data class ScanList(
    val id: String,
    val name: String,
    val channels: List<ChannelKey>
)

data class CallSource(
    val position: Int = 0,
    val sourceRef: Long? = null,
    val tag: String? = null,
    val display: String? = null
)
data class CallKey(val profileId: String, val callId: Long)

data class RadioCall(
    val profileId: String,
    val serverName: String,
    val id: Long,
    val systemRef: Long,
    val talkgroupRef: Long,
    val systemLabel: String,
    val talkgroupLabel: String,
    val dateTime: String,
    val transcript: String? = null,
    val audioPath: String? = null,
    val audioMime: String? = null,
    val audioName: String? = null,
    val sourceRef: Long? = null,
    val sourceLabel: String? = null,
    val sources: List<CallSource> = emptyList(),
    val frequency: Long? = null,
    val durationSeconds: Double? = null,
    val encryptedAudio: Boolean = false
) {
    val key: CallKey get() = CallKey(profileId, id)
    val sourceDisplay: String?
        get() = sources.mapNotNull { it.display?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
            ?: sourceLabel
            ?: sourceRef?.toString()
}

data class AlertKeywordList(
    val id: Long,
    val label: String,
    val description: String = "",
    val keywords: List<String> = emptyList()
)
data class AlertPreference(
    val systemRef: Long,
    val talkgroupRef: Long,
    val alertEnabled: Boolean = false,
    val toneAlerts: Boolean = true,
    val keywordAlerts: Boolean = true,
    val keywords: List<String> = emptyList(),
    val keywordListIds: List<Long> = emptyList(),
    val toneSetIds: List<String> = emptyList(),
    val notificationSound: String = "",
    val toneSetSounds: Map<String, String> = emptyMap(),
    val pagerAlert: Boolean = false,
    val toneSetPagerAlerts: Map<String, Boolean> = emptyMap()
) {
    val key: ChannelKey get() = ChannelKey(systemRef, talkgroupRef)
}
data class SystemHealthAlert(
    val id: Long,
    val alertType: String,
    val severity: String,
    val title: String,
    val message: String,
    val data: String? = null,
    val createdAt: Long = 0L,
    val dismissed: Boolean = false
)

data class TranscriptRecord(
    val profileId: String,
    val serverName: String,
    val callId: Long,
    val systemId: Long? = null,
    val talkgroupId: Long? = null,
    val systemLabel: String? = null,
    val talkgroupLabel: String? = null,
    val talkgroupName: String? = null,
    val transcript: String,
    val reviewedTranscript: String? = null,
    val transcriptionStatus: String? = null,
    val timestamp: Long? = null,
    val alertSummary: String? = null
)

data class ScannerAlert(
    val profileId: String,
    val serverName: String,
    val title: String,
    val body: String,
    val dateTime: String? = null,
    val alertId: Long? = null,
    val callId: Long? = null,
    val alertType: String? = null,
    val systemLabel: String? = null,
    val talkgroupLabel: String? = null,
    val talkgroupName: String? = null,
    val matchedToneSets: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val transcript: String? = null,
    val summary: String? = null,
    val incidentAddress: String? = null,
    val incidentNature: String? = null,
    val incidentLat: Double? = null,
    val incidentLon: Double? = null,
    val createdAt: Long? = null
) {
    val stableKey: String
        get() = alertId?.let { "alert-$it" }
            ?: listOf(title, body, dateTime.orEmpty()).joinToString("|")
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    AUTH_REQUIRED,
    CONNECTED,
    ERROR
}

data class ServerScannerState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusText: String = "Disconnected",
    val profile: ServerProfile,
    val systems: List<SystemConfig> = emptyList(),
    val hiddenSystemRefs: Set<Long> = emptySet(),
    val scanLists: List<ScanList> = emptyList(),
    val scanListSyncing: Boolean = false,
    val scanListError: String? = null,
    val history: List<RadioCall> = emptyList(),
    val recentCalls: List<RadioCall> = emptyList(),
    val lastCall: RadioCall? = null,
    val alerts: List<ScannerAlert> = emptyList(),
    val alertsLoading: Boolean = false,
    val alertsError: String? = null,
    val transcripts: List<TranscriptRecord> = emptyList(),
    val transcriptsLoading: Boolean = false,
    val transcriptsError: String? = null,
    val transcriptsOffset: Int = 0,
    val transcriptsHasMore: Boolean = false,
    val systemAlerts: List<SystemHealthAlert> = emptyList(),
    val systemAlertsLoading: Boolean = false,
    val systemAlertsError: String? = null,
    val canViewSystemAlerts: Boolean = false,
    val alertPreferences: List<AlertPreference> = emptyList(),
    val alertPreferencesLoading: Boolean = false,
    val alertPreferencesSaving: Boolean = false,
    val alertPreferencesError: String? = null,
    val alertKeywordLists: List<AlertKeywordList> = emptyList(),
    val alertKeywordListsLoading: Boolean = false,
    val alertKeywordListsSaving: Boolean = false,
    val alertKeywordListsError: String? = null,
    val hold: ChannelKey? = null,
    val holdSystemRef: Long? = null,
    val paused: Boolean = false,
    val avoided: Set<ChannelKey> = emptySet(),
    val audioEncryptionEnabled: Boolean = false,
    val encryptionReady: Boolean = false,
    val serverVersion: String? = null,
    val showListenersCount: Boolean = false,
    val listenerCount: Int = 0,
    val incidentMappingEnabled: Boolean = false,
    val time12hFormat: Boolean = false,
    val uiAccentColor: String? = null,
    val userUiAccentColor: String? = null,
    val historyHasMore: Boolean = false,
    val historySystemRef: Long? = null,
    val historyTalkgroupRef: Long? = null,
    val error: String? = null
)

data class ScannerState(
    val servers: Map<String, ServerScannerState> = emptyMap(),
    val history: List<RadioCall> = emptyList(),
    val alerts: List<ScannerAlert> = emptyList()
)
