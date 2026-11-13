package com.xplayer.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xplayer.app.R
import com.xplayer.app.core.AudioAccess

/**
 * First-launch / permission screen. Explains *why* audio access is needed before the system prompt is
 * shown, and handles the denied and permanently-denied states without trying to bypass anything.
 */
@Composable
fun OnboardingScreen(
    access: AudioAccess,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.LibraryMusic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_privacy),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(28.dp))
        when (access) {
            AudioAccess.NEEDS_REQUEST, AudioAccess.GRANTED -> Button(onClick = onRequest) { Text(stringResource(R.string.grant_access)) }
            AudioAccess.DENIED -> {
                Text(stringResource(R.string.permission_denied_message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequest) { Text(stringResource(R.string.try_again)) }
            }
            AudioAccess.DENIED_PERMANENTLY -> {
                Text(stringResource(R.string.permission_blocked_message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_app_settings)) }
            }
        }
    }
}
