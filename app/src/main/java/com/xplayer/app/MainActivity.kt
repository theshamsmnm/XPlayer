package com.xplayer.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.xplayer.app.core.AudioAccess
import com.xplayer.app.core.AudioAccessResolver
import com.xplayer.app.core.Permissions
import com.xplayer.app.domain.model.AppSettings
import com.xplayer.app.feature.onboarding.OnboardingScreen
import com.xplayer.app.navigation.AppRoot
import com.xplayer.app.ui.theme.XPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioGranted by mutableStateOf(false)
    private var askedBefore by mutableStateOf(false)
    private var rationale by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        container.playback.connect()

        audioGranted = Permissions.hasAudio(this)
        lifecycleScope.launch { askedBefore = container.settings.onboardingDone.first() }

        setContent {
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                audioGranted = granted
                rationale = shouldShowRequestPermissionRationale(Permissions.audio())
                lifecycleScope.launch { container.settings.setOnboardingDone(); askedBefore = true }
                if (granted) {
                    onAudioGranted()
                    if (Permissions.notificationsRequired() && !Permissions.hasNotifications(this@MainActivity)) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            XPlayerTheme(settings.theme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (audioGranted) {
                        AppRoot()
                    } else {
                        OnboardingScreen(
                            access = AudioAccessResolver.resolve(audioGranted, askedBefore, rationale),
                            onRequest = { audioLauncher.launch(Permissions.audio()) },
                            onOpenSettings = {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The permission can be granted or revoked from system settings while we are in the background.
        val granted = Permissions.hasAudio(this)
        audioGranted = granted
        if (granted) onAudioGranted()
    }

    /**
     * Single permission grant is all that is needed: from here on discovery is automatic (first scan,
     * change observation while running, periodic background sync).
     */
    private fun onAudioGranted() {
        val container = appContainer
        lifecycleScope.launch {
            val autoScan = container.settings.current().autoScan
            // The first scan always runs; later passes are skipped when MediaStore did not change.
            runCatching { container.syncCoordinator.syncNow(force = false) }
            if (autoScan) {
                container.syncCoordinator.startObserving()
                container.syncCoordinator.schedulePeriodic()
            }
        }
    }
}
