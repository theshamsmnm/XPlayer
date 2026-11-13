package com.xplayer.app.feature.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.core.formatDuration
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.feature.artwork.ArtworkEditorDialog
import com.xplayer.app.playback.NowPlaying
import com.xplayer.app.playback.PlayerUiState
import com.xplayer.app.playback.SleepTimerState
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.MenuAction
import com.xplayer.app.ui.components.OverflowMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val container = androidx.compose.ui.platform.LocalContext.current.appContainer
    val playback = container.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val sleep by container.sleepTimer.collectAsStateWithLifecycle()
    val now = state.nowPlaying

    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var editArt by remember { mutableStateOf(false) }

    // Favorite flag comes from Room (the single owner of favorites), not from the player.
    val favoriteFlow = remember(now?.trackId) {
        now?.let { n -> container.music.observeTrack(n.trackId).map { it?.isFavorite == true } }
    }
    val isFavorite by (favoriteFlow ?: remember { kotlinx.coroutines.flow.flowOf(false) }).collectAsStateWithLifecycle(false)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.close)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSleep = true }) {
                Icon(
                    Icons.Filled.Bedtime, stringResource(R.string.sleep_timer),
                    tint = if (sleep != SleepTimerState.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue)) }
            if (now != null) {
                OverflowMenu(listOf(MenuAction(stringResource(R.string.change_artwork)) { editArt = true }))
            }
        }

        if (now == null) {
            EmptyState(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.nothing_playing), stringResource(R.string.nothing_playing_message))
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                val landscape = maxWidth > maxHeight
                if (landscape) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkImage(now.art, Modifier.fillMaxHeight(0.9f), sizePx = 900, fallbackTextSize = 96.sp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { Controls(now, state, isFavorite, sleep) }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(0.4f))
                        ArtworkImage(now.art, Modifier.fillMaxWidth().widthIn(max = 460.dp), sizePx = 1000, fallbackTextSize = 96.sp)
                        Spacer(Modifier.height(28.dp))
                        Controls(now, state, isFavorite, sleep)
                        Spacer(Modifier.weight(0.6f))
                    }
                }
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showSleep) SleepTimerDialog(sleep, onDismiss = { showSleep = false })
    if (editArt && now != null) ArtworkEditorDialog(ArtKind.TRACK, now.trackId) { editArt = false }
}

@Composable
private fun Controls(now: NowPlaying, state: PlayerUiState, isFavorite: Boolean, sleep: SleepTimerState) {
    val container = androidx.compose.ui.platform.LocalContext.current.appContainer
    val playback = container.playback

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(now.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                now.artist.ifBlank { stringResource(R.string.unknown_artist) },
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (now.album.isNotBlank()) {
                Text(now.album, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { container.appScope.launch { container.music.toggleTrackFavorite(now.trackId) } }) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(R.string.favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    SeekBar(durationMs = now.durationMs, isPlaying = state.isPlaying, key = now.trackId)
    if (sleep is SleepTimerState.Counting) {
        Text(
            stringResource(R.string.sleep_in, formatDuration(sleep.remainingMs)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    } else if (sleep is SleepTimerState.EndOfTrack) {
        Text(
            stringResource(R.string.sleep_end_of_track),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = playback::toggleShuffle) {
            Icon(
                Icons.Filled.Shuffle, stringResource(R.string.shuffle),
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = playback::previous, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(30.dp))
        }
        FilledIconButton(
            onClick = playback::togglePlayPause, modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (state.isPlaying) R.string.pause else R.string.play), Modifier.size(30.dp),
            )
        }
        IconButton(onClick = playback::next, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(30.dp))
        }
        IconButton(onClick = playback::cycleRepeat) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                stringResource(R.string.repeat),
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Progress bar. The position is *polled from the service's player* only while visible and playing;
 * it is deliberately not part of the state flow (that would recompose the whole screen twice a second).
 */
@Composable
private fun SeekBar(durationMs: Long, isPlaying: Boolean, key: Long) {
    val playback = androidx.compose.ui.platform.LocalContext.current.appContainer.playback
    var position by remember(key) { mutableLongStateOf(playback.currentPositionMs()) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L)

    LaunchedEffect(key, isPlaying) {
        while (true) {
            if (!dragging) position = playback.currentPositionMs()
            if (!isPlaying) break
            delay(300)
        }
    }

    val shown = if (dragging) (dragValue * duration).toLong() else position
    Slider(
        value = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = {
            playback.seekTo((dragValue * duration).toLong())
            position = (dragValue * duration).toLong()
            dragging = false
        },
        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("-" + formatDuration((duration - shown).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
