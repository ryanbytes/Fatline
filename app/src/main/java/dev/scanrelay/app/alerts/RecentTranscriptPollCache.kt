package dev.scanrelay.app.alerts

/**
 * Avoid matching the same HTTP transcript page against local phrase rules on
 * every 30/60-second check. Late reviewed transcripts and metadata edits must
 * still be processed, so use exact strings rather than lossy hash signatures.
 *
 * Owned by one transcript-monitor coroutine. A new monitor (for example when
 * rules change, monitoring is toggled, or a scanner reconnects) gets a fresh
 * cache. Live WebSocket transcripts never pass through this filter.
 */
internal class RecentTranscriptPollCache(
    private val maxEntries: Int = 128,
    private val maxTotalCharacters: Int = 65_536,
    private val maxTextCharacters: Int = 4_096
) {
    private data class Snapshot(
        val text: String,
        val systemLabel: String?,
        val talkgroupLabel: String?
    )

    private val seen = LinkedHashMap<Long, Snapshot>()
    private var retainedCharacters = 0

    init {
        require(maxEntries > 0 && maxTotalCharacters > 0 && maxTextCharacters > 0)
    }

    fun changed(callId: Long, text: String, systemLabel: String?, talkgroupLabel: String?): Boolean {
        // Never cache incomplete or exceptionally long records; exact matching
        // still runs, and the cache remains strictly bounded in memory.
        if (callId <= 0 || text.isBlank()) {
            forget(callId)
            return true
        }
        if (text.length > maxTextCharacters || text.length > maxTotalCharacters) {
            forget(callId)
            return true
        }

        val previous = seen[callId]
        if (previous?.text == text &&
            previous.systemLabel == systemLabel &&
            previous.talkgroupLabel == talkgroupLabel
        ) return false

        if (previous != null) {
            retainedCharacters -= previous.text.length
            seen.remove(callId)
        }
        seen[callId] = Snapshot(text, systemLabel, talkgroupLabel)
        retainedCharacters += text.length

        while (seen.size > maxEntries || retainedCharacters > maxTotalCharacters) {
            val oldest = seen.entries.iterator()
            val removed = oldest.next()
            retainedCharacters -= removed.value.text.length
            oldest.remove()
        }
        return true
    }

    fun forget(callId: Long) {
        seen.remove(callId)?.let { retainedCharacters -= it.text.length }
    }

    internal val entryCount: Int get() = seen.size
    internal val cachedCharacters: Int get() = retainedCharacters
}
