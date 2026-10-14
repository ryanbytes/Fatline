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

/** Fixed schema for automatic uploads. Never include server-provided reason strings,
 * endpoint names/URLs, call identifiers, stack traces or exception messages. */
internal enum class ConnectionDiagnosticType {
    NETWORK_UNAVAILABLE, NETWORK_CHANGED, SOCKET_FAILURE, SOCKET_CLOSED,
    SOCKET_STARTUP_FAILURE, RETRY_SCHEDULED, CONFIG_ACCEPTED
}

internal object ConnectionDiagnosticFormatter {
    fun format(
        type: ConnectionDiagnosticType,
        networkAvailable: Boolean,
        reconnectAttempt: Int = 0,
        queued: Int = 0,
        cause: Throwable? = null,
        httpStatus: Int? = null,
        closeCode: Int? = null,
        retryDelayMs: Long? = null
    ): String {
        val errorFamily = when (cause) {
            null -> "none"
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.UnknownHostException -> "dns"
            is javax.net.ssl.SSLException -> "tls"
            is java.net.ConnectException -> "connect"
            is java.net.SocketException -> "socket"
            is java.io.EOFException -> "eof"
            is java.io.IOException -> "io"
            else -> "other"
        }
        return "type=${type.name.lowercase(java.util.Locale.ROOT)}" +
            " network_available=$networkAvailable" +
            " attempt=${reconnectAttempt.coerceIn(0, 99)}" +
            " queued=${queued.coerceAtLeast(0)}" +
            " error_family=$errorFamily" +
            " http_status=${httpStatus?.takeIf { it in 100..599 } ?: "none"}" +
            " close_code=${closeCode?.takeIf { it in 1000..4999 } ?: "none"}" +
            " retry_ms=${retryDelayMs?.takeIf { it in 0L..60_000L } ?: "none"}"
    }
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
    val eventSequence: Long = 0L,
    val diagnosticSequence: Long = 0L
)

internal object ScannerTransportMetrics {
    private val callFrames = AtomicLong()
    private val configs = AtomicLong()
    private val terminations = AtomicLong()
    private val journal = ScannerTransportEventJournal()
    private val diagnosticJournal = ScannerTransportEventJournal()

    fun receivedCallFrame() { callFrames.incrementAndGet() }
    fun configured() { configs.incrementAndGet() }
    fun terminated() { terminations.incrementAndGet() }
    fun event(detail: String) { journal.record(detail) }

    fun diagnostic(
        type: ConnectionDiagnosticType,
        networkAvailable: Boolean,
        reconnectAttempt: Int = 0,
        queued: Int = 0,
        cause: Throwable? = null,
        httpStatus: Int? = null,
        closeCode: Int? = null,
        retryDelayMs: Long? = null
    ) {
        diagnosticJournal.record(ConnectionDiagnosticFormatter.format(
            type, networkAvailable, reconnectAttempt, queued, cause,
            httpStatus, closeCode, retryDelayMs
        ))
    }

    fun diagnosticsBetween(startExclusive: Long, endInclusive: Long): List<ScannerTransportEvent> =
        diagnosticJournal.between(startExclusive, endInclusive)

    fun eventsBetween(startExclusive: Long, endInclusive: Long): List<ScannerTransportEvent> =
        journal.between(startExclusive, endInclusive)

    fun snapshot(): ScannerTransportSnapshot = ScannerTransportSnapshot(
        receivedCallFrames = callFrames.get(),
        successfulConfigs = configs.get(),
        socketTerminations = terminations.get(),
        eventSequence = journal.sequence(),
        diagnosticSequence = diagnosticJournal.sequence()
    )
}
