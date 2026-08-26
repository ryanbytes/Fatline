package dev.scanrelay.app.net

import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

object ThinLineProtocol {
    const val ALERT = "ALT"
    const val CALL = "CAL"
    const val CONFIG = "CFG"
    const val ERROR = "ERR"
    const val EXPIRED = "XPR"
    const val FCM = "FCM"
    const val LIST_CALL = "LCL"
    const val LISTENER_COUNT = "LSC"
    const val LIVEFEED_MAP = "LFM"
    const val MAX = "MAX"
    const val PIN = "PIN"
    const val PIN_SET = "PNS"
    const val PING = "PNG"
    const val SERVER = "SRV"
    const val VERSION = "VER"
    const val DOWNLOAD_FLAG = "d"

    data class Envelope(val command: String, val payload: Any? = null, val flag: Any? = null)

    fun parseEnvelope(text: String): Envelope {
        val array = JSONArray(text)
        require(array.length() >= 1) { "Empty ThinLine message" }
        return Envelope(
            command = array.getString(0),
            payload = if (array.length() >= 2 && !array.isNull(1)) array.get(1) else null,
            flag = if (array.length() >= 3 && !array.isNull(2)) array.get(2) else null
        )
    }

    fun command(command: String): String = JSONArray().put(command).toString()

    fun command(command: String, payload: Any?, flag: Any? = null): String {
        val array = JSONArray().put(command).put(payload ?: JSONObject.NULL)
        if (flag != null) array.put(flag)
        return array.toString()
    }

    fun pin(pin: String): String = command(
        PIN,
        Base64.getEncoder().encodeToString(pin.toByteArray(Charsets.UTF_8))
    )

    fun livefeed(systems: List<SystemConfig>): String {
        val map = JSONObject()
        systems.forEach { system ->
            val talkgroups = JSONObject()
            system.talkgroups.filter { it.enabled }.forEach {
                talkgroups.put(it.talkgroupRef.toString(), true)
            }
            if (talkgroups.length() > 0) map.put(system.systemRef.toString(), talkgroups)
        }
        return command(LIVEFEED_MAP, map)
    }

    fun listCalls(
        limit: Int = 100,
        offset: Int = 0,
        sort: Int = -1,
        systemRef: Long? = null,
        talkgroupRefs: Collection<Long> = emptyList()
    ): String {
        val payload = JSONObject()
            .put("limit", limit.coerceIn(1, 500))
            .put("offset", offset.coerceAtLeast(0))
            .put("sort", if (sort < 0) -1 else 1)
        if (systemRef != null && systemRef > 0) payload.put("system", systemRef)
        if (talkgroupRefs.isNotEmpty()) {
            payload.put("talkgroups", JSONArray().apply { talkgroupRefs.filter { it > 0 }.forEach(::put) })
        }
        return command(LIST_CALL, payload)
    }

    fun call(callId: Long, download: Boolean = false): String =
        command(CALL, callId, if (download) DOWNLOAD_FLAG else null)

    fun parseSystems(configPayload: JSONObject): List<SystemConfig> {
        val raw = configPayload.opt("systems") ?: return emptyList()
        val systems = mutableListOf<SystemConfig>()

        fun parseSystem(node: JSONObject, keyRef: Long? = null) {
            val ref = node.optLong("systemRef", node.optLong("id", keyRef ?: 0L)).takeIf { it > 0 } ?: return
            val label = node.optString("label").ifBlank { "System $ref" }
            systems += SystemConfig(ref, label, parseTalkgroups(ref, node.opt("talkgroups")))
        }

        when (raw) {
            is JSONObject -> raw.keys().forEach { key -> raw.optJSONObject(key)?.let { parseSystem(it, key.toLongOrNull()) } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { parseSystem(it) }
        }
        return systems.sortedBy { it.label.lowercase() }
    }

    private fun parseTalkgroups(systemRef: Long, raw: Any?): List<TalkgroupConfig> {
        val result = mutableListOf<TalkgroupConfig>()
        fun add(node: JSONObject, keyRef: Long? = null) {
            val ref = node.optLong("talkgroupRef", node.optLong("id", keyRef ?: 0L)).takeIf { it > 0 } ?: return
            result += TalkgroupConfig(
                systemRef = systemRef,
                talkgroupRef = ref,
                label = node.optString("label"),
                name = node.optString("name"),
                tag = when (val tag = node.opt("tag")) {
                    is String -> tag
                    is JSONObject -> tag.optString("label")
                    else -> ""
                }
            )
        }
        when (raw) {
            is JSONObject -> raw.keys().forEach { key -> raw.optJSONObject(key)?.let { add(it, key.toLongOrNull()) } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { add(it) }
        }
        return result.sortedWith(compareBy({ it.tag.lowercase() }, { it.displayName.lowercase() }))
    }
}
