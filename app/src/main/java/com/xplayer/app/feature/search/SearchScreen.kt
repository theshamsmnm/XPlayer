package com.xplayer.app.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.domain.model.SearchResults
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.feature.common.selectionMenu
import com.xplayer.app.feature.common.trackMenu
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.SectionHeader
import com.xplayer.app.ui.components.SelectionTopBar
import com.xplayer.app.ui.components.TrackRow
import com.xplayer.app.ui.components.XScaffold
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(c: AppContainer) : TrackActionsViewModel(c) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Debounced (250 ms); `flatMapLatest` cancels the obsolete query when a newer one arrives. */
    val results: StateFlow<SearchResults> = _query
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) emit(SearchResults()) else emit(c.music.search(q))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun setQuery(q: String) { _query.value = q }
}

@Composable
fun SearchScreen(onOpenAlbum: (Long) -> Unit, onOpenArtist: (Long) -> Unit, onOpenCollection: (Long) -> Unit) {
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TrackDialogHost(vm, snackbar)
    val selecting = selected.isNotEmpty()

    XScaffold(
        title = stringResource(R.string.nav_search),
        snackbar = snackbar,
        topBarOverride = {
            if (selecting) {
                SelectionTopBar(selected.size, vm::clearSelection, selectionMenu(vm, results.tracks.filter { it.id in selected }, results.tracks))
            } else {
                OutlinedTextField(
                    value = query, onValueChange = vm::setQuery, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) } },
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        when {
            query.isBlank() -> EmptyState(Icons.Filled.Search, stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_message), Modifier.padding(padding))
            results.isEmpty -> EmptyState(Icons.Filled.Search, stringResource(R.string.no_results), modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp)) {
                if (results.tracks.isNotEmpty()) {
                    item(key = "h-tracks") { SectionHeader(stringResource(R.string.tracks)) }
                    items(results.tracks, key = { "t${it.id}" }) { track ->
                        TrackRow(
                            track, selected = track.id in selected, selectionMode = selecting,
                            onClick = { if (selecting) vm.toggleSelected(track.id) else vm.play(results.tracks, results.tracks.indexOf(track)) },
                            onLongClick = { vm.toggleSelected(track.id) },
                            menu = trackMenu(vm, track),
                        )
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item(key = "h-albums") { SectionHeader(stringResource(R.string.nav_albums)) }
                    items(results.albums, key = { "a${it.id}" }) { album ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenAlbum(album.id) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(album.art, Modifier.size(44.dp), sizePx = 128)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                if (results.artists.isNotEmpty()) {
                    item(key = "h-artists") { SectionHeader(stringResource(R.string.nav_artists)) }
                    items(results.artists, key = { "r${it.id}" }) { artist ->
                        Text(artist.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onOpenArtist(artist.id) }.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
                if (results.collections.isNotEmpty()) {
                    item(key = "h-collections") { SectionHeader(stringResource(R.string.nav_collections)) }
                    items(results.collections, key = { "c${it.id}" }) { col ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenCollection(col.id) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(col.art, Modifier.size(44.dp), sizePx = 128)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(col.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(stringResource(R.string.tracks_count, col.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
