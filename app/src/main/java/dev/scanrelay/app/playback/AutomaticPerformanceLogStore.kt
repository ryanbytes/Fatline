package dev.scanrelay.app.playback

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * Private, bounded, append-only diagnostic spool. Only aggregate counters and
 * connection state summaries are stored; no audio, transcripts or credentials.
 *
 * Never transmit these records to ThinLine or any other service. An upload
 * destination must be explicitly configured and implemented separately.
 */
internal object AutomaticPerformanceLogStore {
    const val MAX_LOG_BYTES = 256L * 1024L

    /**
     * Keep two files (at most 512 KiB total); overwrite oldest on rotation.
     * Called on Dispatchers.IO, never on the service main thread.
     */
    fun append(directory: File, report: String, timestampMs: Long = System.currentTimeMillis(),
               maxFileBytes: Long = MAX_LOG_BYTES) {
        require(maxFileBytes > 0L)
        val entry = "Recorded UTC: ${Instant.ofEpochMilli(timestampMs)}\n$report\n---\n"
        val data = entry.toByteArray(Charsets.UTF_8)
        if (data.size > maxFileBytes) throw IOException("Performance report exceeds diagnostic file limit")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create private diagnostics directory")
        }
        val current = File(directory, "current.log")
        val previous = File(directory, "previous.log")
        if (current.exists() && current.length() + data.size > maxFileBytes) {
            if (previous.exists() && !previous.delete()) {
                throw IOException("Cannot rotate previous diagnostics")
            }
            if (!current.renameTo(previous)) {
                throw IOException("Cannot rotate current diagnostics")
            }
        }
        FileOutputStream(current, true).use { it.write(data) }
    }
}
