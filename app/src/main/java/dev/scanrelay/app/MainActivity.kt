package dev.scanrelay.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.scanrelay.app.playback.ScannerService
import dev.scanrelay.app.ui.FatLineApp

class MainActivity : ComponentActivity() {
    private val viewModel: ScannerViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // A stopped media service does not always receive a START_STICKY restart.
        // Resume only scanner sessions the listener previously left active.
        ScannerService.resumeActiveConnections(this)
        setContent { FatLineApp(viewModel) }
    }
}
