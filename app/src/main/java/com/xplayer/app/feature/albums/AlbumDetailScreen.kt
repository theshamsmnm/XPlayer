package com.xplayer.app.feature.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.core.formatDuration
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.feature.artwork.ArtworkEditorDialog
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.feature.common.selectionMenu
import com.xplayer.app.feature.common.trackMenu
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.LoadingBox
import com.xplayer.app.ui.components.MenuAction
import com.xplayer.app.ui.components.OverflowMenu
import com.xplayer.app.ui.components.SelectionTopBar
import com.xplayer.app.ui.components.TrackRow
import com.xplayer.app.ui.components.XScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumDetailViewModel(c: AppContainer, val albumId: Long) : TrackActionsViewModel(c) {
    val album: StateFlow<AlbumItem?> = c.music.observeAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tracks: StateFlow<List<TrackItem>?> = c.music.observeAlbumTracks(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleAlbumFavorite() {
        val a = album.value ?: return
        viewModelScope.launch { c.music.setAlbumFavorite(a.id, !a.isFavorite) }
    }
}

@Composable
fun AlbumDetailScreen(albumId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "album:$albumId") { AlbumDetailViewModel(it, albumId) }
    val album by vm.album.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editArt by remember { mutableStateOf(false) }

    TrackDialogHost(vm, snackbar)
    if (editArt) ArtworkEditorDialog(ArtKind.ALBUM, albumId) { editArt = false }

    val list = tracks.orEmpty()
    val selecting = selected.isNotEmpty()

    XScaffold(
        title = album?.title.orEmpty(),
        snackbar = snackbar,
        onBack = onBack,
        topBarOverride = if (selecting) {
            {
                SelectionTopBar(selected.size, vm::clearSelection, selectionMenu(vm, list.filter { it.id in selected }, list))
            }
        } else null,
        actions = {
            IconButton(onClick = vm::toggleAlbumFavorite) {
                Icon(
                    if (album?.isFavorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    stringResource(R.string.favorite),
                )
            }
            OverflowMenu(listOf(MenuAction(stringResource(R.string.change_artwork)) { editArt = true }))
        },
    ) { padding ->
        val a = album
        if (a == null || tracks == null) {
            LoadingBox(Modifier.padding(padding))
            return@XScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    ArtworkImage(a.art, Modifier.size(148.dp), sizePx = 512, fallbackTextSize = 44.sp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(a.title.ifBlank { stringResource(R.string.unknown_album) }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(a.artist.ifBlank { stringResource(R.string.unknown_artist) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val meta = buildList {
                            if (a.year > 0) add(a.year.toString())
                            add(stringResource(R.string.tracks_count, list.size))
                            add(formatDuration(list.sumOf { it.durationMs }))
                        }.joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.play(list, 0, false) }, enabled = list.isNotEmpty()) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play))
                    }
                    OutlinedButton(onClick = { vm.play(list, list.indices.random(), true) }, enabled = list.isNotEmpty()) {
                        Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.shuffle))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            items(list, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    showArt = false,
                    leadingNumber = track.trackNumber,
                    selected = track.id in selected,
                    selectionMode = selecting,
                    onClick = { if (selecting) vm.toggleSelected(track.id) else vm.play(list, list.indexOf(track)) },
                    onLongClick = { vm.toggleSelected(track.id) },
                    menu = trackMenu(vm, track),
                )
            }
        }
    }
}
