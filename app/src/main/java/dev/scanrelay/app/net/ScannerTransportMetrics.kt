package dev.scanrelay.app.net

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Bounded process-local record of connection state transitions; never uploaded. */
internal data class ScannerTransportEvent(
    val sequence: Long,
    val timestampMs: Long,
    val detail: String
)

internal class ScannerTransportEventJournal(private val capacity: Int = 96) {
    init { require(capacity > 0) }
    private val events = ArrayDeque<ScannerTransportEvent>()
    private var sequence = 0L

    @Synchronized
    fun record(detail: String, timestampMs: Long = System.currentTimeMillis()): Long {
        val number = ++sequence
        if (events.size == capacity) events.removeFirst()
        events.addLast(ScannerTransportEvent(number, timestampMs, detail))
        return number
    }

    @Synchronized
    fun sequence(): Long = sequence

    @Synchronized
    fun between(startExclusive: Long, endInclusive: Long): List<ScannerTransportEvent> =
        events.filter { it.sequence > startExclusive && it.sequence <= endInclusive }
}

/**
 * Process-local transport counters for opt-in performance reports.
 * Each live call performs only an atomic increment. Events are appended only
 * on a connection lifecycle change, not with each packet or timer tick.
 */
internal data class ScannerTransportSnapshot(
    val receivedCallFrames: Long = 0L,
    val successfulConfigs: Long = 0L,
    val socketTerminations: Long = 0L,
    val eventSequence: Long = 0L
)

internal object ScannerTransportMetrics {
    private val callFrames = AtomicLong()
    private val configs = AtomicLong()
    private val terminations = AtomicLong()
    private val journal = ScannerTransportEventJournal()

    fun receivedCallFrame() { callFrames.incrementAndGet() }
    fun configured() { configs.incrementAndGet() }
    fun terminated() { terminations.incrementAndGet() }
    fun event(detail: String) { journal.record(detail) }

    fun eventsBetween(startExclusive: Long, endInclusive: Long): List<ScannerTransportEvent> =
        journal.between(startExclusive, endInclusive)

    fun snapshot(): ScannerTransportSnapshot = ScannerTransportSnapshot(
        receivedCallFrames = callFrames.get(),
        successfulConfigs = configs.get(),
        socketTerminations = terminations.get(),
        eventSequence = journal.sequence()
    )
}
