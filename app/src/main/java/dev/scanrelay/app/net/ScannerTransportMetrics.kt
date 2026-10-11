package dev.scanrelay.app.net

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local transport counters for opt-in performance reports.
 * Recording an existing socket event performs only an atomic increment:
 * no polling, audio retention, identifiers, files, or network telemetry.
 */
internal data class ScannerTransportSnapshot(
    val receivedCallFrames: Long = 0L,
    val successfulConfigs: Long = 0L,
    val socketTerminations: Long = 0L
)

internal object ScannerTransportMetrics {
    private val callFrames = AtomicLong()
    private val configs = AtomicLong()
    private val terminations = AtomicLong()

    fun receivedCallFrame() { callFrames.incrementAndGet() }
    fun configured() { configs.incrementAndGet() }
    fun terminated() { terminations.incrementAndGet() }

    fun snapshot(): ScannerTransportSnapshot = ScannerTransportSnapshot(
        receivedCallFrames = callFrames.get(),
        successfulConfigs = configs.get(),
        socketTerminations = terminations.get()
    )
}
