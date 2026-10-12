package dev.scanrelay.app.playback

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Private, bounded outbox. Only aggregate performance summaries. No audio,
 * transcripts, credentials, server URLs or per-call identifiers.
 */
internal object AutomaticPerformanceLogStore {
    const val MAX_PENDING_FILES = 576 // 48h at five-minute intervals
    const val MAX_PENDING_BYTES = 4L * 1024L * 1024L
    const val MAX_ENTRY_BYTES = 24 * 1024

    fun append(directory: File, report: String, timestampMs: Long = System.currentTimeMillis()): File {
        val data = ("Recorded UTC: " + Instant.ofEpochMilli(timestampMs) +
            "\n" + report + "\n---\n").toByteArray(Charsets.UTF_8)
        if (data.size > MAX_ENTRY_BYTES) throw IOException("Diagnostic report exceeds entry limit")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostic spool")
        val file = File(directory, "$timestampMs-${UUID.randomUUID()}.txt")
        if (!file.createNewFile()) throw IOException("Cannot reserve diagnostic report")
        try { file.outputStream().use { it.write(data) } }
        catch (e: IOException) { file.delete(); throw e }
        prune(directory)
        return file
    }

    /** Oldest unsent records are discarded only after capacity is exhausted. */
    fun prune(directory: File) {
        val files = pending(directory)
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= MAX_PENDING_FILES && total <= MAX_PENDING_BYTES) break
            val size = file.length()
            if (file.delete()) { count--; total -= size }
        }
    }

    fun pending(directory: File): List<File> =
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedBy { it.name } ?: emptyList()
}
