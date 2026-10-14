package dev.scanrelay.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.scanrelay.app.playback.ScannerService
import dev.scanrelay.app.playback.LiveCallLifecycleMetrics
import dev.scanrelay.app.playback.PlaybackDeliveryTrace
import dev.scanrelay.app.ui.FatLineApp

class MainActivity : ComponentActivity() {
    private val viewModel: ScannerViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onStart() {
        super.onStart()
        LiveCallLifecycleMetrics.activityVisible(true)
        PlaybackDeliveryTrace.record("activity_visible")
    }

    // Observe volume keys only when this Activity receives them. Android still
    // handles the keys normally; no volume or scanner state is changed here.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) "down" else "up"
            PlaybackDeliveryTrace.record(
                "hardware_volume_key",
                "direction=$direction repeat=${event.repeatCount.coerceIn(0, 99)}"
            )
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        LiveCallLifecycleMetrics.activityVisible(false)
        PlaybackDeliveryTrace.record("activity_hidden")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android's system status bar visible over the dark Compose app.
        // Edge-to-edge devices draw a transparent status bar over our dark surface;
        // dark/light-status-bar icons from the old theme made it look hidden.
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false // light clock, signal and battery icons
            show(WindowInsetsCompat.Type.statusBars())
        }
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // A stopped media service does not always receive a START_STICKY restart.
        // Resume only scanner sessions the listener previously left active.
        ScannerService.resumeActiveConnections(this)
        setContent { FatLineApp(viewModel) }
    }
}
