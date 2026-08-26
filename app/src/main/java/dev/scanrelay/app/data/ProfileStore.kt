package dev.scanrelay.app.data

import android.content.Context
import dev.scanrelay.app.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("scanrelay_profiles", Context.MODE_PRIVATE)
    private val pinVault = PinVault(context)

    fun load(): List<ServerProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.getString("id")
                    add(
                        ServerProfile(
                            id = id,
                            name = item.optString("name", "Server"),
                            baseUrl = item.optString("baseUrl", ""),
                            pin = pinVault.get(id)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(profile: ServerProfile) {
        val profiles = load().filterNot { it.id == profile.id } + profile
        persist(profiles)
        pinVault.put(profile.id, profile.pin)
    }

    fun delete(profileId: String) {
        persist(load().filterNot { it.id == profileId })
        pinVault.remove(profileId)
    }

    private fun persist(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
            )
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }
}
