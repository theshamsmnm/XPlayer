package com.xplayer.app.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.core.formatDuration
import com.xplayer.app.playback.SleepTimerState

private val Presets = listOf(5, 10, 15, 30, 45, 60, 90)

/** Sleep timer controls. The timer itself runs inside the playback service. */
@Composable
fun SleepTimerDialog(current: SleepTimerState, onDismiss: () -> Unit) {
    val playback = androidx.compose.ui.platform.LocalContext.current.appContainer.playback
    var custom by remember { mutableStateOf("") }
    val customMinutes = custom.toIntOrNull()?.takeIf { it in 1..1440 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column {
                when (current) {
                    is SleepTimerState.Counting -> Text(
                        stringResource(R.string.sleep_in, formatDuration(current.remainingMs)),
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SleepTimerState.EndOfTrack -> Text(
                        stringResource(R.string.sleep_end_of_track),
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SleepTimerState.Off -> Unit
                }
                Presets.forEach { minutes ->
                    Text(
                        stringResource(R.string.minutes_fmt, minutes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { playback.startSleepTimer(minutes * 60_000L); onDismiss() }
                            .padding(vertical = 10.dp),
                    )
                }
                Text(
                    stringResource(R.string.sleep_end_of_track),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { playback.sleepAfterCurrentTrack(); onDismiss() }
                        .padding(vertical = 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom, onValueChange = { custom = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.custom_minutes)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = customMinutes != null,
                        onClick = { playback.startSleepTimer(customMinutes!! * 60_000L); onDismiss() },
                    ) { Text(stringResource(R.string.start)) }
                }
            }
        },
        confirmButton = {
            if (current != SleepTimerState.Off) {
                TextButton(onClick = { playback.cancelSleepTimer(); onDismiss() }) { Text(stringResource(R.string.cancel_timer)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
