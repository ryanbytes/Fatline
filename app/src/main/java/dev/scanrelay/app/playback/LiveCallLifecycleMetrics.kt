package dev.scanrelay.app.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local aggregate live-call counters. No call IDs, channels, audio,
 * transcripts, credentials, or timestamps are recorded in these summaries.
 */
internal data class LiveCallLifecycleSnapshot(
    val received: Long = 0,
    val routed: Long = 0,
    val filtered: Long = 0,
    val missingAudio: Long = 0,
    val dispatchFailures: Long = 0,
    val suppressed: Long = 0,
    val duplicates: Long = 0,
    val enqueued: Long = 0,
    val started: Long = 0,
    val completed: Long = 0,
    val removed: Long = 0,
    val playerErrors: Long = 0
)

/** Thread-safe accumulator shared by socket, repository and Media3 callbacks. */
internal open class LiveCallLifecycleCounters {
    private val receivedCount = AtomicLong()
    private val routedCount = AtomicLong()
    private val filteredCount = AtomicLong()
    private val missingAudioCount = AtomicLong()
    private val dispatchFailuresCount = AtomicLong()
    private val suppressedCount = AtomicLong()
    private val duplicateCount = AtomicLong()
    private val queuedCount = AtomicLong()
    private val startedCount = AtomicLong()
    private val completedCount = AtomicLong()
    private val removedCount = AtomicLong()
    private val playerErrorCount = AtomicLong()

    fun received() { receivedCount.incrementAndGet() }
    fun routed() { routedCount.incrementAndGet() }
    fun filtered() { filteredCount.incrementAndGet() }
    fun missingAudio() { missingAudioCount.incrementAndGet() }
    fun dispatchFailed() { dispatchFailuresCount.incrementAndGet() }
    fun suppressed() { suppressedCount.incrementAndGet() }
    fun duplicate() { duplicateCount.incrementAndGet() }
    fun enqueued() { queuedCount.incrementAndGet() }
    fun progressVerified() { startedCount.incrementAndGet() }
    fun completed() { completedCount.incrementAndGet() }
    fun removed() { removedCount.incrementAndGet() }
    fun playerError() { playerErrorCount.incrementAndGet() }

    fun snapshot() = LiveCallLifecycleSnapshot(
        received = receivedCount.get(),
        routed = routedCount.get(),
        filtered = filteredCount.get(),
        missingAudio = missingAudioCount.get(),
        dispatchFailures = dispatchFailuresCount.get(),
        suppressed = suppressedCount.get(),
        duplicates = duplicateCount.get(),
        enqueued = queuedCount.get(),
        started = startedCount.get(),
        completed = completedCount.get(),
        removed = removedCount.get(),
        playerErrors = playerErrorCount.get()
    )
}

internal object LiveCallLifecycleMetrics : LiveCallLifecycleCounters()
