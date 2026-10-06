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

data class TalkgroupConfig(
    val systemRef: Long,
    val talkgroupRef: Long,
    val label: String,
    val name: String = "",
    val tag: String = "",
    val enabled: Boolean = false,
    val favorite: Boolean = false
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
    val units: List<UnitAlias> = emptyList()
)

data class ScanList(
    val id: String,
    val name: String,
    val channels: List<ChannelKey>
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
    val frequency: Long? = null,
    val durationSeconds: Double? = null,
    val encryptedAudio: Boolean = false
) {
    val key: CallKey get() = CallKey(profileId, id)
}

data class ScannerAlert(
    val profileId: String,
    val serverName: String,
    val title: String,
    val body: String,
    val dateTime: String? = null
)

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
    val scanLists: List<ScanList> = emptyList(),
    val history: List<RadioCall> = emptyList(),
    val recentCalls: List<RadioCall> = emptyList(),
    val lastCall: RadioCall? = null,
    val alerts: List<ScannerAlert> = emptyList(),
    val hold: ChannelKey? = null,
    val holdSystemRef: Long? = null,
    val paused: Boolean = false,
    val avoided: Set<ChannelKey> = emptySet(),
    val audioEncryptionEnabled: Boolean = false,
    val encryptionReady: Boolean = false,
    val serverVersion: String? = null,
    val historyHasMore: Boolean = false,
    val error: String? = null
)

data class ScannerState(
    val servers: Map<String, ServerScannerState> = emptyMap(),
    val history: List<RadioCall> = emptyList(),
    val alerts: List<ScannerAlert> = emptyList()
)
