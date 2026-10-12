package dev.scanrelay.app.playback

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPerformanceLogStoreTest {
    @Test
    fun appendsImmutableReportsInTimestampOrder() {
        val dir = Files.createTempDirectory("fatline-perf-spool").toFile()
        try {
            val first = AutomaticPerformanceLogStore.append(dir, "PSS 120 MiB", 1_000L)
            val second = AutomaticPerformanceLogStore.append(dir, "PSS 140 MiB", 2_000L)
            assertTrue(first.exists())
            assertTrue(second.exists())
            assertEquals(2, AutomaticPerformanceLogStore.pending(dir).size)
            assertTrue(first.readText().contains("PSS 120 MiB"))
            assertFalse(first.readText().contains("PSS 140 MiB"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun pruneOnlyDropsOldestWhenFileLimitExceeded() {
        val dir = Files.createTempDirectory("fatline-perf-prune").toFile()
        try {
            val first = AutomaticPerformanceLogStore.append(dir, "old report", 1_000L)
            for (i in 2..577) {
                AutomaticPerformanceLogStore.append(dir, "report $i", i * 1_000L)
            }
            val pending = AutomaticPerformanceLogStore.pending(dir)
            assertEquals(576, pending.size)
            assertFalse(first.exists())
            assertTrue(pending.any { it.readText().contains("report 577") })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun rejectOversizedReportsWithoutLeavingPartialFile() {
        val dir = Files.createTempDirectory("fatline-perf-limit").toFile()
        try {
            try {
                AutomaticPerformanceLogStore.append(dir, "x".repeat(25_000))
                throw AssertionError("Oversized report accepted")
            } catch (_: java.io.IOException) {
                assertTrue(AutomaticPerformanceLogStore.pending(dir).isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }
}
