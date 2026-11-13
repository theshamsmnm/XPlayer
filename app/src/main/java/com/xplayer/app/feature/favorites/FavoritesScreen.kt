package com.xplayer.app.feature.favorites

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.PlayHistoryEntry
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.feature.common.selectionMenu
import com.xplayer.app.feature.common.trackMenu
import com.xplayer.app.ui.components.AlbumCell
import com.xplayer.app.ui.components.ConfirmDialog
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.MenuAction
import com.xplayer.app.ui.components.OverflowMenu
import com.xplayer.app.ui.components.SelectionTopBar
import com.xplayer.app.ui.components.TrackRow
import com.xplayer.app.ui.components.XScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(c: AppContainer) : TrackActionsViewModel(c) {
    private fun <T> share(f: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        f.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val favoriteTracks = share(c.music.observeFavoriteTracks(), emptyList<TrackItem>())
    val favoriteAlbums = share(c.music.observeFavoriteAlbums(), emptyList<AlbumItem>())
    val history = share(c.music.observeHistory(), emptyList<PlayHistoryEntry>())
    val recentlyAdded = share(c.music.observeRecentlyAdded(50), emptyList<TrackItem>())

    fun clearHistory() { viewModelScope.launch { c.music.clearHistory(); emit(R.string.msg_history_cleared) } }
}

@Composable
fun FavoritesScreen(onOpenAlbum: (Long) -> Unit) {
    val vm = appViewModel { FavoritesViewModel(it) }
    val tracks by vm.favoriteTracks.collectAsStateWithLifecycle()
    val albums by vm.favoriteAlbums.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val added by vm.recentlyAdded.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { androidx.compose.runtime.mutableStateOf(false) }
    TrackDialogHost(vm, snackbar)
    val selecting = selected.isNotEmpty()

    val visibleTracks: List<TrackItem> = when (tab) {
        0 -> tracks
        2 -> history.map { it.track }
        3 -> added
        else -> emptyList()
    }

    XScaffold(
        title = stringResource(R.string.nav_favorites),
        snackbar = snackbar,
        topBarOverride = if (selecting) {
            { SelectionTopBar(selected.size, vm::clearSelection, selectionMenu(vm, visibleTracks.filter { it.id in selected }, visibleTracks)) }
        } else null,
        actions = {
            if (tab == 2) OverflowMenu(listOf(MenuAction(stringResource(R.string.clear_history)) { confirmClear = true }))
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.padding(top = padding.calculateTopPadding())) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf(R.string.fav_tracks, R.string.fav_albums, R.string.history, R.string.recently_added).forEachIndexed { i, res ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res)) })
                }
            }
            when (tab) {
                1 -> if (albums.isEmpty()) EmptyState(Icons.Filled.FavoriteBorder, stringResource(R.string.no_favorite_albums)) else {
                    val prefs = LayoutPrefs(LayoutPrefs.SCOPE_FAVORITES)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(prefs.columns), modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(albums, key = { it.id }) { album -> AlbumCell(album, prefs, onClick = { onOpenAlbum(album.id) }) }
                    }
                }
                else -> {
                    val list = visibleTracks
                    if (list.isEmpty()) {
                        EmptyState(
                            if (tab == 2) Icons.Filled.History else Icons.Filled.FavoriteBorder,
                            stringResource(
                                when (tab) { 0 -> R.string.no_favorite_tracks; 2 -> R.string.no_history; else -> R.string.no_recent },
                            ),
                        )
                    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        // History can contain the same track many times, so keys use the position.
                        items(list.size, key = { "$tab-$it-${list[it].id}" }) { i ->
                            val track = list[i]
                            TrackRow(
                                track, selected = track.id in selected, selectionMode = selecting,
                                onClick = { if (selecting) vm.toggleSelected(track.id) else vm.play(list, i) },
                                onLongClick = { vm.toggleSelected(track.id) },
                                menu = trackMenu(vm, track),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history),
            message = stringResource(R.string.clear_history_message),
            confirmLabel = stringResource(R.string.clear), destructive = true,
            onConfirm = vm::clearHistory, onDismiss = { confirmClear = false },
        )
    }
}
