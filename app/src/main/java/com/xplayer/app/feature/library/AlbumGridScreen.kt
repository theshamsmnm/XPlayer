package com.xplayer.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xplayer.app.R
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.ViewMode
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.AlbumCell
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.LayoutSheet
import com.xplayer.app.ui.components.LoadingBox
import com.xplayer.app.ui.components.MenuAction
import com.xplayer.app.ui.components.OverflowMenu
import com.xplayer.app.ui.components.SelectionTopBar
import com.xplayer.app.ui.components.XScaffold
import com.xplayer.app.ui.components.draggedItem
import com.xplayer.app.ui.components.rememberReorderableGridState
import com.xplayer.app.ui.components.reorderable

private val GridSortKeys = SortKey.entries.filter { it != SortKey.ALPHABETICAL }

/** Home/Library (scope = library) and Albums (scope = albums) share this screen. */
@Composable
fun AlbumGridScreen(
    scope: String,
    title: String,
    onOpenAlbum: (Long) -> Unit,
    onRescan: () -> Unit,
) {
    val vm = appViewModel(key = "grid:$scope") { AlbumGridViewModel(it, scope) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedAlbums.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showLayout by remember { mutableStateOf(false) }
    val prefs = state.prefs

    TrackDialogHost(vm, snackbar)

    XScaffold(
        title = title,
        snackbar = snackbar,
        topBarOverride = if (selecting) {
            {
                SelectionTopBar(
                    count = selected.size,
                    onClear = vm::clearAlbumSelection,
                    actions = listOf(
                        MenuAction(stringResource(R.string.favorite_toggle), vm::favoriteSelectedAlbums),
                        MenuAction(stringResource(R.string.add_to_collection), vm::addSelectedToCollection),
                        MenuAction(stringResource(R.string.play_next), { vm.queueSelected(true) }),
                        MenuAction(stringResource(R.string.add_to_queue), { vm.queueSelected(false) }),
                        MenuAction(stringResource(R.string.select_all), vm::selectAllAlbums),
                    ),
                )
            }
        } else null,
        actions = {
            if (prefs.locked) {
                IconButton(onClick = { vm.setLocked(false) }) {
                    Icon(Icons.Filled.Lock, stringResource(R.string.unlock_layout), Modifier.size(20.dp))
                }
            } else if (prefs.sortKey == SortKey.CUSTOM) {
                IconButton(onClick = { vm.setLocked(true) }) {
                    Icon(Icons.Filled.LockOpen, stringResource(R.string.lock_layout), Modifier.size(20.dp))
                }
            }
            IconButton(onClick = { showLayout = true }) { Icon(Icons.Filled.Tune, stringResource(R.string.layout)) }
            OverflowMenu(
                listOf(
                    MenuAction(stringResource(R.string.select), vm::startSelecting),
                    MenuAction(stringResource(R.string.play_all), { vm.playAll(false) }),
                    MenuAction(stringResource(R.string.shuffle_all), { vm.playAll(true) }),
                    MenuAction(stringResource(R.string.rescan_library), onRescan),
                ),
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.albums.isEmpty() -> EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty_message),
                modifier = Modifier.padding(padding),
                actionLabel = stringResource(R.string.rescan_library),
                onAction = onRescan,
            )
            prefs.viewMode == ViewMode.LIST -> AlbumList(vm, state, selected, selecting, padding, onOpenAlbum)
            else -> AlbumGrid(vm, state, selected, selecting, padding, onOpenAlbum)
        }
    }

    if (showLayout) {
        LayoutSheet(
            prefs = prefs,
            sortKeys = GridSortKeys,
            onChange = vm::updatePrefs,
            onLockChange = vm::setLocked,
            onResetOrder = vm::resetOrder,
            onDismiss = { showLayout = false },
            showViewMode = scope == LayoutPrefs.SCOPE_ALBUMS,
        )
    }
}

@Composable
private fun AlbumGrid(
    vm: AlbumGridViewModel,
    state: AlbumGridState,
    selected: Set<Long>,
    selecting: Boolean,
    padding: PaddingValues,
    onOpenAlbum: (Long) -> Unit,
) {
    val prefs = state.prefs
    val gridState = rememberLazyGridState()
    val reorder = rememberReorderableGridState(gridState, onMove = vm::onDragMove, onDragEnd = vm::onDragEnd)
    val spacing = prefs.spacingDp.dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(prefs.columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().reorderable(reorder, enabled = prefs.canReorder && !selecting),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing + 8.dp),
    ) {
        items(state.albums, key = { it.id }) { album ->
            AlbumCell(
                album = album,
                prefs = prefs,
                selected = album.id in selected,
                selectionMode = selecting,
                modifier = Modifier.draggedItem(reorder, album.id),
                onClick = { if (selecting) vm.toggleAlbum(album.id) else onOpenAlbum(album.id) },
                // When dragging is possible the long press belongs to the drag gesture.
                onLongClick = if (prefs.canReorder && !selecting) null else ({ vm.toggleAlbum(album.id) }),
            )
        }
    }
}

@Composable
private fun AlbumList(
    vm: AlbumGridViewModel,
    state: AlbumGridState,
    selected: Set<Long>,
    selecting: Boolean,
    padding: PaddingValues,
    onOpenAlbum: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)) {
        items(state.albums, key = { it.id }) { album ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { if (selecting) vm.toggleAlbum(album.id) else onOpenAlbum(album.id) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(album.art, Modifier.size(52.dp), sizePx = 160)
                Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    if (state.prefs.showTitles) {
                        Text(album.title.ifBlank { stringResource(R.string.unknown_album) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (state.prefs.showArtists) {
                        Text(album.artist.ifBlank { stringResource(R.string.unknown_artist) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (album.id in selected) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(album.trackCount.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
