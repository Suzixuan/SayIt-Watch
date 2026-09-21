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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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

        // 1C-D-04@R7 §2A: the connection task owner follows the REAL foreground state.
        // `onStop` cancels the round in flight and ends all scheduling, so nothing polls
        // in the background; `onStart` revalidates the computer in use immediately (one
        // bounded authenticated probe) and resumes the 5 s recovery search when none is
        // usable. This is also what lets a PC started after the Watch be found without
        // restarting the app — the old screen-effect scheduler only ran on page changes.
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    viewModel.onForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    viewModel.onBackground()
                }
            },
        )

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
