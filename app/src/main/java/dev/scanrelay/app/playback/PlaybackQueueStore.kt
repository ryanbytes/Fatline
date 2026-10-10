package dev.scanrelay.app.playback

import android.content.Context
import dev.scanrelay.app.model.RadioCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Only locally buffered calls are recoverable: no replay requests, account data,
 * or network access is needed when Android restarts the foreground service.
 */
internal data class SavedPlaybackCall(
    val mediaId: String,
    val call: RadioCall,
    val liveFeed: Boolean
)

internal data class SavedPlaybackQueue(
    val calls: List<SavedPlaybackCall>,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = true
)

/**
 * Media3 may report play-state, timeline, and current-media changes for a
 * single queue operation. Every *different* recovery snapshot is written
 * immediately; identical snapshots do not need another JSON serialization
 * or SharedPreferences write. Position and playWhenReady are significant.
 */
internal object PlaybackQueueJournalPolicy {
    fun shouldWrite(previous: SavedPlaybackQueue?, current: SavedPlaybackQueue?): Boolean =
        previous != current
}

internal object PlaybackQueueCodec {
    fun encode(snapshot: SavedPlaybackQueue): String {
        val calls = JSONArray()
        snapshot.calls.forEach { entry ->
            val call = entry.call
            calls.put(JSONObject()
                .put("mediaId", entry.mediaId)
                .put("profileId", call.profileId)
                .put("serverName", call.serverName)
                .put("id", call.id)
                .put("systemRef", call.systemRef)
                .put("talkgroupRef", call.talkgroupRef)
                .put("systemLabel", call.systemLabel)
                .put("talkgroupLabel", call.talkgroupLabel)
                .put("dateTime", call.dateTime)
                .put("audioPath", call.audioPath)
                .put("liveFeed", entry.liveFeed))
        }
        return JSONObject()
            .put("version", 1)
            .put("positionMs", snapshot.positionMs.coerceAtLeast(0L))
            .put("playWhenReady", snapshot.playWhenReady)
            .put("calls", calls)
            .toString()
    }

    fun decode(raw: String?): SavedPlaybackQueue? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != 1) return@runCatching null
            val rows = root.optJSONArray("calls") ?: return@runCatching null
            val calls = buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val path = row.optString("audioPath").takeIf { it.isNotBlank() } ?: continue
                    val profileId = row.optString("profileId").takeIf { it.isNotBlank() } ?: continue
                    val mediaId = row.optString("mediaId")
                    if (!mediaId.startsWith("call:$profileId:")) continue
                    add(SavedPlaybackCall(
                        mediaId = mediaId,
                        call = RadioCall(
                            profileId = profileId,
                            serverName = row.optString("serverName"),
                            id = row.optLong("id"),
                            systemRef = row.optLong("systemRef"),
                            talkgroupRef = row.optLong("talkgroupRef"),
                            systemLabel = row.optString("systemLabel"),
                            talkgroupLabel = row.optString("talkgroupLabel"),
                            dateTime = row.optString("dateTime"),
                            audioPath = path
                        ),
                        liveFeed = row.optBoolean("liveFeed", true)
                    ))
                }
            }
            SavedPlaybackQueue(
                calls = calls,
                positionMs = root.optLong("positionMs").coerceAtLeast(0L),
                playWhenReady = root.optBoolean("playWhenReady", true)
            )
        }.getOrNull()
    }
}

/** Keep buffered audio even when newer traffic pushes cache beyond its normal limit. */
internal object PlaybackCachePolicy {
    private data class DatedFile(val file: java.io.File, val lastModifiedMs: Long)

    /**
     * Calling File.lastModified() inside a sort comparator causes many stat()
     * calls for the same audio file. Read it once, then use a stable sort.
     * The original newest-first order for equal timestamps is preserved.
     */
    fun newestFirst(
        files: Array<java.io.File>,
        lastModified: (java.io.File) -> Long = { it.lastModified() }
    ): List<java.io.File> = files
        .map { DatedFile(it, lastModified(it)) }
        .sortedByDescending { it.lastModifiedMs }
        .map { it.file }

    /** No intermediate path list, filtered list, dropped list, set or file list. */
    fun forEachEvictableFile(
        newestFirst: List<java.io.File>,
        protectedPaths: Set<String>,
        retainUnprotected: Int = 150,
        evict: (java.io.File) -> Unit
    ) {
        require(retainUnprotected >= 0)
        var unprotectedSeen = 0
        for (file in newestFirst) {
            if (file.absolutePath in protectedPaths) continue
            if (unprotectedSeen++ >= retainUnprotected) evict(file)
        }
    }

    // Kept as a pure reference policy for cache safety regression tests.
    fun evictablePaths(
        newestFirst: List<String>,
        protectedPaths: Set<String>,
        retainUnprotected: Int = 150
    ): List<String> =
        newestFirst.filterNot { it in protectedPaths }.drop(retainUnprotected)
}

/**
 * Run an immediate cache sweep after the first audio write in a process, then
 * once per [interval] subsequent writes per scanner. This avoids directory
 * listing, mtime sort and saved-queue reads for most incoming calls while
 * keeping only up to interval - 1 extra unprotected files between sweeps.
 *
 * Queue-protected paths are still read afresh during every actual sweep.
 */
internal class PlaybackCachePruneSchedule(private val interval: Int = 16) {
    init { require(interval > 0) }

    private val writesSincePrune = mutableMapOf<String, Int>()

    @Synchronized
    fun afterWrite(profileId: String): Boolean {
        val count = writesSincePrune[profileId] ?: 0
        writesSincePrune[profileId] = (count + 1) % interval
        return count == 0
    }
}

internal class PlaybackQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("fatline_playback_queue", Context.MODE_PRIVATE)

    fun load(): SavedPlaybackQueue? =
        PlaybackQueueCodec.decode(prefs.getString("snapshot", null))

    fun save(snapshot: SavedPlaybackQueue?) {
        val editor = prefs.edit()
        if (snapshot == null || snapshot.calls.isEmpty()) editor.remove("snapshot")
        else editor.putString("snapshot", PlaybackQueueCodec.encode(snapshot))
        editor.apply()
    }

    fun protectedAudioPaths(): Set<String> =
        load()?.calls?.mapNotNullTo(mutableSetOf()) { it.call.audioPath } ?: emptySet()
}
