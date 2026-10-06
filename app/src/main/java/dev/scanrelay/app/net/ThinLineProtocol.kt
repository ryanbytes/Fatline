package dev.scanrelay.app.net

import dev.scanrelay.app.model.AlertToneSet
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ScanList
import dev.scanrelay.app.model.SystemConfig
import dev.scanrelay.app.model.TalkgroupConfig
import dev.scanrelay.app.model.UnitAlias
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

object ThinLineProtocol {
    const val ALERT = "ALT"
    const val INCIDENT = "INC"
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

    /**
     * ThinLine's public web client sends the complete subscription map, including
     * explicit false values. Keep that wire shape so removing a channel is never
     * ambiguous to the server.
     */
    fun livefeed(systems: List<SystemConfig>): String {
        val map = JSONObject()
        systems.forEach { system ->
            val talkgroups = JSONObject()
            system.talkgroups.forEach { talkgroup ->
                talkgroups.put(talkgroup.talkgroupRef.toString(), talkgroup.enabled)
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
            payload.put("talkgroups", JSONArray().apply {
                talkgroupRefs.filter { it > 0 }.forEach { ref -> put(ref) }
            })
        }
        return command(LIST_CALL, payload)
    }

    /** ThinLine's public web client serializes CAL ids as decimal strings. */
    fun call(callId: Long, download: Boolean = false): String =
        command(CALL, callId.toString(), if (download) DOWNLOAD_FLAG else null)

    fun parseListenerCount(payload: Any?): Int? = when (payload) {
        is Number -> payload.toInt()
        is String -> payload.trim().toIntOrNull()
        else -> payload?.toString()?.trim()?.toIntOrNull()
    }?.takeIf { it >= 0 }


    fun parseSystems(configPayload: JSONObject): List<SystemConfig> {
        val raw = configPayload.opt("systems") ?: return emptyList()
        val systems = mutableListOf<SystemConfig>()

        fun parseSystem(node: JSONObject, keyRef: Long? = null) {
            val ref = node.optLong("systemRef", node.optLong("id", keyRef ?: 0L)).takeIf { it > 0 } ?: return
            val label = node.optString("label").ifBlank { "System $ref" }
            systems += SystemConfig(
                ref,
                label,
                parseTalkgroups(ref, node.opt("talkgroups")),
                parseUnits(node.opt("units"))
            )
        }

        when (raw) {
            is JSONObject -> raw.keys().forEach { key -> raw.optJSONObject(key)?.let { parseSystem(it, key.toLongOrNull()) } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { parseSystem(it) }
        }
        return systems.sortedBy { it.label.lowercase() }
    }

    private fun parseUnits(raw: Any?): List<UnitAlias> {
        val result = mutableListOf<UnitAlias>()

        fun add(node: JSONObject, keyRef: Long? = null) {
            val unitRef = node.optLong("unitRef", 0L)
            val id = node.optLong("id", keyRef ?: 0L)
            val from = node.optLong("unitFrom", 0L)
            val to = node.optLong("unitTo", 0L)
            val label = node.optString("label").trim()
            if (label.isBlank()) return
            if (unitRef <= 0 && id <= 0 && (from <= 0 || to <= 0)) return
            result += UnitAlias(id = id, label = label, unitRef = unitRef, unitFrom = from, unitTo = to)
        }

        when (raw) {
            is JSONObject -> raw.keys().forEach { key -> raw.optJSONObject(key)?.let { add(it, key.toLongOrNull()) } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { add(it) }
        }
        return result
    }

    fun parseScanLists(configPayload: JSONObject): List<ScanList> {
        val lists = configPayload
            .optJSONObject("userSettings")
            ?.optJSONArray("scanLists")
            ?: return emptyList()

        return buildList {
            for (i in 0 until lists.length()) {
                val item = lists.optJSONObject(i) ?: continue
                val channels = item.optJSONArray("channels") ?: JSONArray()
                val keys = buildList {
                    for (j in 0 until channels.length()) {
                        val channel = channels.optJSONObject(j) ?: continue
                        val systemRef = channel.opt("systemId")?.toString()?.toLongOrNull() ?: continue
                        val talkgroupRef = channel.opt("talkgroupId")?.toString()?.toLongOrNull() ?: continue
                        if (systemRef > 0 && talkgroupRef > 0) add(ChannelKey(systemRef, talkgroupRef))
                    }
                }.distinct()
                add(
                    ScanList(
                        id = item.optString("id").ifBlank { "scan-list-$i" },
                        name = item.optString("name").ifBlank { "Scan List ${i + 1}" },
                        channels = keys
                    )
                )
            }
        }
    }
    private fun parseToneSets(raw: Any?): List<AlertToneSet> {
        val array = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    AlertToneSet(
                        id = id,
                        label = item.optString("label").trim().ifBlank { id }
                    )
                )
            }
        }.distinctBy { it.id }
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
                },
                toneDetectionEnabled = node.optBoolean("toneDetectionEnabled", false),
                toneSets = parseToneSets(node.opt("toneSets"))
            )
        }
        when (raw) {
            is JSONObject -> raw.keys().forEach { key -> raw.optJSONObject(key)?.let { add(it, key.toLongOrNull()) } }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { add(it) }
        }
        return result.sortedWith(compareBy({ it.tag.lowercase() }, { it.displayName.lowercase() }))
    }
}
