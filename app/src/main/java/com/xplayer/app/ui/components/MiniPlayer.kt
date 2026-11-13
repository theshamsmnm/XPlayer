package com.xplayer.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xplayer.app.R
import com.xplayer.app.playback.PlaybackController
import com.xplayer.app.playback.PlayerUiState
import com.xplayer.app.ui.art.ArtworkImage
import kotlinx.coroutines.delay

/** Compact persistent player. Shown above the bottom navigation whenever something is queued. */
@Composable
fun MiniPlayer(state: PlayerUiState, playback: PlaybackController, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now = state.nowPlaying ?: return
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(now.trackId, state.isPlaying) {
        while (true) {
            val d = now.durationMs.coerceAtLeast(1L)
            progress = (playback.currentPositionMs().toFloat() / d).coerceIn(0f, 1f)
            if (!state.isPlaying) break
            delay(500)
        }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(
                Modifier.clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(now.art, Modifier.size(40.dp), sizePx = 96)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        now.artist.ifBlank { stringResource(R.string.unknown_artist) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = playback::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    )
                }
                IconButton(onClick = playback::next) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next)) }
            }
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(1.5.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
