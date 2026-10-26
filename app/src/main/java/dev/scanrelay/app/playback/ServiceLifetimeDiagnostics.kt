package dev.scanrelay.app.playback

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Persists minimal service lifecycle evidence across process death.
 *
 * The five-minute performance probe is process-local, so it cannot write a
 * "killed" event after Android terminates the process. On the next service
 * creation, correlate the prior PID with Android's recorded process exits.
 * No profile IDs, calls, audio, messages, URLs, or credentials are recorded.
 */
internal object ServiceLifetimeDiagnostics {
    private const val PREFS = "fatline_service_lifetime"
    private const val KEY_ACTIVE = "active"
    private const val KEY_PID = "pid"
    private const val KEY_STARTED = "started"
    private const val KEY_HEARTBEAT = "heartbeat"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_TASK_REMOVED = "task_removed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serviceCreated(context: Context) {
        val now = System.currentTimeMillis()
        val preferences = prefs(context)
        val previousStart = preferences.getLong(KEY_STARTED, 0)
        val previousPid = preferences.getInt(KEY_PID, 0)
        val previousActive = preferences.getBoolean(KEY_ACTIVE, false)
        val heartbeat = preferences.getLong(KEY_HEARTBEAT, 0)
        val cleanStop = preferences.getLong(KEY_STOPPED, 0)
        val taskRemoved = preferences.getLong(KEY_TASK_REMOVED, 0)
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            previousPid > 0 && previousStart > 0
        ) {
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, previousPid, 10)
                    .firstOrNull { it.pid == previousPid && it.timestamp >= previousStart }
            }.getOrNull()
        } else null

        // Commit before any disk append: the marker survives abrupt process death.
        preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_PID, Process.myPid())
            .putLong(KEY_STARTED, now)
            .putLong(KEY_HEARTBEAT, now)
            .commit()

        val report = buildString {
            append("Service lifetime: created at ").append(Instant.ofEpochMilli(now))
                .append(" pid=").append(Process.myPid()).append('\n')
            if (previousStart > 0) {
                append("Previous session: start=").append(Instant.ofEpochMilli(previousStart))
                    .append(" pid=").append(previousPid)
                    .append(" clean_stop=").append(!previousActive)
                    .append(" last_heartbeat=")
                    .append(if (heartbeat > 0) Instant.ofEpochMilli(heartbeat).toString() else "unknown")
                    .append(" last_clean_stop=")
                    .append(if (cleanStop > 0) Instant.ofEpochMilli(cleanStop).toString() else "none")
                    .append(" task_removed=")
                    .append(if (taskRemoved >= previousStart) Instant.ofEpochMilli(taskRemoved).toString() else "none")
                    .append('\n')
                if (exit != null) {
                    append("Android exit record: reason=").append(exitReason(exit))
                        .append(" reason_code=").append(exit.reason)
                        .append(" timestamp=").append(Instant.ofEpochMilli(exit.timestamp))
                        .append(" status=").append(exit.status)
                        .append(" importance=").append(exit.importance).append('\n')
                } else {
                    append("Android exit record: not available for prior PID/session\n")
                }
                if (previousActive) {
                    append("Prior service did not record onDestroy; unexpected process death is possible.\n")
                }
            }
        }
        append(context, report)
    }

    fun heartbeat(context: Context) {
        // Called once per performance sample; a synchronous commit gives a
        // durable last-known-alive time even when the process is later killed.
        prefs(context).edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).commit()
    }

    fun taskRemoved(
        context: Context, activeProfiles: Int, connectedProfiles: Int,
        foreground: Boolean, audioEnabled: Boolean
    ) {
        val now = System.currentTimeMillis()
        prefs(context).edit().putLong(KEY_TASK_REMOVED, now).commit()
        append(
            context, "Service lifetime: Android called onTaskRemoved at " +
                Instant.ofEpochMilli(now) +
                " active_profiles=$activeProfiles connected=$connectedProfiles" +
                " foreground=$foreground audio_enabled=$audioEnabled\n"
        )
    }

    fun serviceDestroyed(
        context: Context, activeProfiles: Int, connectedProfiles: Int,
        foreground: Boolean
    ) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_STOPPED, now)
            .commit()
        append(
            context, "Service lifetime: onDestroy at " + Instant.ofEpochMilli(now) +
                " active_profiles=$activeProfiles connected=$connectedProfiles" +
                " foreground=$foreground\n"
        )
    }

    private fun exitReason(exit: ApplicationExitInfo): String = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXIT_SELF -> "self_exit"
        else -> "other"
    }

    private fun append(context: Context, report: String) {
        runCatching {
            AutomaticPerformanceLogStore.append(
                File(context.filesDir, "fatline_diagnostics/pending"), report
            )
        }.onFailure { Log.w("FatLineLifetime", "Unable to persist service lifecycle diagnostics") }
    }
}
