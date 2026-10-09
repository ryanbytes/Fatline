package dev.scanrelay.app.playback

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.util.Locale

/** Process counters use monotonic time; battery capacity/charge is whole-device, not app-only. */
internal data class PerformanceReading(
    val elapsedMs: Long,
    val cpuMs: Long,
    val pssKb: Long?,
    val javaHeapKb: Long,
    val nativeHeapKb: Long,
    val rxBytes: Long?,
    val txBytes: Long?,
    val batteryPercent: Int?,
    val chargeMicroAh: Long?,
    val plugged: Boolean
)

internal object PerformanceReader {
    fun read(context: Context): PerformanceReading {
        val battery = context.getSystemService(BatteryManager::class.java)
        val properties = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (properties?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charge = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it != Long.MIN_VALUE && it >= 0L }
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val runtime = Runtime.getRuntime()
        return PerformanceReading(
            elapsedMs = SystemClock.elapsedRealtime(),
            cpuMs = Process.getElapsedCpuTime(),
            pssKb = Debug.getPss().takeIf { it > 0L },
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
            rxBytes = TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it >= 0L },
            txBytes = TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it >= 0L },
            batteryPercent = capacity,
            chargeMicroAh = charge,
            plugged = plugged
        )
    }
}

/**
 * Pure aggregator, independent of Android services, for offline JVM regression tests.
 * No network upload, file access, telemetry or raw transcript/call content.
 */
internal class PerformanceProbe(val scenario: String, private val first: PerformanceReading) {
    private var last = first
    private var samples = 1
    private var peakPssKb: Long? = first.pssKb
    private var peakJavaHeapKb = first.javaHeapKb
    private var peakNativeHeapKb = first.nativeHeapKb
    private var everPlugged = first.plugged

    fun sample(next: PerformanceReading) {
        if (next.elapsedMs < last.elapsedMs) return
        last = next
        samples++
        peakPssKb = listOfNotNull(peakPssKb, next.pssKb).maxOrNull()
        peakJavaHeapKb = maxOf(peakJavaHeapKb, next.javaHeapKb)
        peakNativeHeapKb = maxOf(peakNativeHeapKb, next.nativeHeapKb)
        everPlugged = everPlugged || next.plugged
    }

    private fun counterDelta(a: Long?, b: Long?): Long? =
        if (a == null || b == null || b < a) null else b - a

    private fun memoryText(kb: Long?): String =
        kb?.let { String.format(Locale.US, "%.1f MiB", it / 1024.0) } ?: "unavailable"

    private fun transferredText(bytes: Long?): String =
        bytes?.let { String.format(Locale.US, "%.1f KiB", it / 1024.0) } ?: "unavailable"

    fun report(): String {
        val durationMs = (last.elapsedMs - first.elapsedMs).coerceAtLeast(0L)
        val cpuMs = (last.cpuMs - first.cpuMs).coerceAtLeast(0L)
        val cpuPercent = if (durationMs > 0L) cpuMs * 100.0 / durationMs else null
        val batteryDelta = if (!everPlugged) {
            val a = first.chargeMicroAh
            val b = last.chargeMicroAh
            if (a != null && b != null && a >= b) a - b else null
        } else null
        val batteryPercent = if (!everPlugged &&
            first.batteryPercent != null && last.batteryPercent != null
        ) "${first.batteryPercent}% → ${last.batteryPercent}%" else "unavailable while charging"

        return buildString {
            appendLine("FatLine local performance report")
            appendLine("Scenario: $scenario")
            appendLine("Duration: ${String.format(Locale.US, "%.1f", durationMs / 60000.0)} min; samples: $samples")
            appendLine("App CPU time: $cpuMs ms")
            appendLine("Avg app CPU: ${cpuPercent?.let { String.format(Locale.US, "%.1f%% of one core", it) } ?: "unavailable"}")
            appendLine("PSS memory start / end / peak: ${memoryText(first.pssKb)} / ${memoryText(last.pssKb)} / ${memoryText(peakPssKb)}")
            appendLine("Java heap start / end / peak: ${memoryText(first.javaHeapKb)} / ${memoryText(last.javaHeapKb)} / ${memoryText(peakJavaHeapKb)}")
            appendLine("Native heap start / end / peak: ${memoryText(first.nativeHeapKb)} / ${memoryText(last.nativeHeapKb)} / ${memoryText(peakNativeHeapKb)}")
            appendLine("UID network received: ${transferredText(counterDelta(first.rxBytes, last.rxBytes))}")
            appendLine("UID network sent: ${transferredText(counterDelta(first.txBytes, last.txBytes))}")
            appendLine("Phone battery level: $batteryPercent")
            appendLine("Phone battery charge used: ${batteryDelta?.let { String.format(Locale.US, "%.2f mAh", it / 1000.0) } ?: "unavailable"}")
            appendLine("Charging observed: $everPlugged")
            appendLine("Battery counters are whole-phone measurements, not FatLine-only drain.")
            appendLine("CPU percent is relative to one core and can exceed 100% on multicore devices.")
            append("Sampling is opt-in and happens only while the scanner service is running.")
        }
    }
}

data class PerformanceCaptureState(
    val running: Boolean = false,
    val label: String = "",
    val report: String = "",
    val error: String? = null
)
