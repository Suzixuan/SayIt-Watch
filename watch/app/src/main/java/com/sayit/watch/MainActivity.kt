package com.sayit.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.sayit.watch.settings.SettingsStore
import com.sayit.watch.ui.RecordingScreen
import com.sayit.watch.ui.RecordingViewModel
import com.sayit.watch.ui.vibratePattern

/**
 * Delivery 1A/1B debug application: record 16 kHz/16-bit/mono audio and transport
 * it to the SayIt Windows debug receiver over LAN HTTP. No ASR integration.
 *
 * 0.2.0-dev.2: the screen stays awake while recording or uploading (app-driven,
 * never user-tapping); the UI shows the three-screen model with inline upload
 * states and transport-only success wording.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: RecordingViewModel by viewModels {
        val store = SettingsStore(applicationContext)
        // Delivery 1C: the application context lets the ViewModel build the
        // platform NSD browser (debug builds only).
        RecordingViewModel.Factory(store, applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind the vibrator to the ViewModel (needs Context; ViewModel has none).
        viewModel.onVibrate = { pattern -> vibratePattern(this, pattern) }

        setContent {
            var hasPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> hasPermission = granted }

            // Keep the screen awake while recording or uploading — driven by the
            // app, never by asking the user to keep tapping the screen.
            val uiState by viewModel.ui.collectAsState()
            LaunchedEffect(uiState.keepScreenOn) {
                if (uiState.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.prepare()
            }

            RecordingScreen(
                viewModel = viewModel,
                settings = SettingsStore(applicationContext),
                hasPermission = hasPermission,
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        }
    }
}
