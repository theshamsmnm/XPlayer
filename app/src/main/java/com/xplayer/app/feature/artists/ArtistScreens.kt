package com.xplayer.app.feature.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.core.TextKeys
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.ArtistItem
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.feature.common.selectionMenu
import com.xplayer.app.feature.common.trackMenu
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.LoadingBox
import com.xplayer.app.ui.components.SectionHeader
import com.xplayer.app.ui.components.SelectionTopBar
import com.xplayer.app.ui.components.TrackRow
import com.xplayer.app.ui.components.XScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ArtistsViewModel(c: AppContainer) : ViewModel() {
    val artists: StateFlow<List<ArtistItem>?> = c.music.observeArtists()
        .map { list -> list.sortedBy { TextKeys.sortKey(it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ArtistsScreen(onOpenArtist: (Long) -> Unit) {
    val vm = appViewModel { ArtistsViewModel(it) }
    val artists by vm.artists.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    XScaffold(title = stringResource(R.string.nav_artists), snackbar = snackbar) { padding ->
        val list = artists
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(Icons.Filled.Person, stringResource(R.string.no_artists), modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)) {
                items(list, key = { it.id }) { artist ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenArtist(artist.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(artist.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.artist_counts, artist.albumCount, artist.trackCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

class ArtistDetailViewModel(c: AppContainer, artistId: Long) : TrackActionsViewModel(c) {
    val artist: StateFlow<ArtistItem?> = c.music.observeArtist(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albums: StateFlow<List<AlbumItem>> = c.music.observeAlbumsOfArtist(artistId)
        .map { it.sortedWith(compareByDescending<AlbumItem> { a -> a.year }.thenBy { a -> TextKeys.sortKey(a.title) }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tracks: StateFlow<List<TrackItem>> = c.music.observeArtistTracks(artistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ArtistDetailScreen(artistId: Long, onBack: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val vm = appViewModel(key = "artist:$artistId") { ArtistDetailViewModel(it, artistId) }
    val artist by vm.artist.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TrackDialogHost(vm, snackbar)
    val selecting = selected.isNotEmpty()

    XScaffold(
        title = artist?.name.orEmpty(), snackbar = snackbar, onBack = onBack,
        topBarOverride = if (selecting) {
            { SelectionTopBar(selected.size, vm::clearSelection, selectionMenu(vm, tracks.filter { it.id in selected }, tracks)) }
        } else null,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)) {
            if (albums.isNotEmpty()) {
                item(key = "albums-header") { SectionHeader(stringResource(R.string.nav_albums)) }
                items(albums, key = { "a${it.id}" }) { album ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenAlbum(album.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtworkImage(album.art, Modifier.size(52.dp), sizePx = 160)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(album.title.ifBlank { stringResource(R.string.unknown_album) }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(album.year.takeIf { it > 0 }?.toString(), stringResource(R.string.tracks_count, album.trackCount)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item(key = "tracks-header") { SectionHeader(stringResource(R.string.tracks)) }
            items(tracks, key = { "t${it.id}" }) { track ->
                TrackRow(
                    track = track,
                    selected = track.id in selected, selectionMode = selecting,
                    onClick = { if (selecting) vm.toggleSelected(track.id) else vm.play(tracks, tracks.indexOf(track)) },
                    onLongClick = { vm.toggleSelected(track.id) },
                    menu = trackMenu(vm, track),
                )
            }
        }
    }
}
