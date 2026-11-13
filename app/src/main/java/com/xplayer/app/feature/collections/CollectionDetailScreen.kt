package com.xplayer.app.feature.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.core.LayoutLockedException
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.CollectionItem
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.domain.sort.Reorder
import com.xplayer.app.domain.sort.TrackSorter
import com.xplayer.app.feature.artwork.ArtworkEditorDialog
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialog
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.feature.common.selectionMenu
import com.xplayer.app.feature.common.trackMenu
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.ConfirmDialog
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.LayoutSheet
import com.xplayer.app.ui.components.LoadingBox
import com.xplayer.app.ui.components.MenuAction
import com.xplayer.app.ui.components.OverflowMenu
import com.xplayer.app.ui.components.SelectionTopBar
import com.xplayer.app.ui.components.TextInputDialog
import com.xplayer.app.ui.components.TrackRow
import com.xplayer.app.ui.components.XScaffold
import com.xplayer.app.ui.components.draggedItem
import com.xplayer.app.ui.components.rememberReorderableListState
import com.xplayer.app.ui.components.reorderable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class CollectionDetailState(
    val loading: Boolean = true,
    val collection: CollectionItem? = null,
    val tracks: List<TrackItem> = emptyList(),
    val prefs: LayoutPrefs = LayoutPrefs(""),
)

/** Each collection has its own display settings, sort, custom track order and lock (scope "collection:<id>"). */
class CollectionDetailViewModel(c: AppContainer, val collectionId: Long) : TrackActionsViewModel(c) {
    private val scope = LayoutPrefs.collectionScope(collectionId)
    private val defaults = LayoutPrefs(scope, viewMode = com.xplayer.app.domain.model.ViewMode.LIST)
    private val draft = MutableStateFlow<List<Long>?>(null)

    val state: StateFlow<CollectionDetailState> = combine(
        c.collections.observeCollection(collectionId),
        c.collections.observeTracks(collectionId),
        c.layouts.observe(scope, defaults),
        draft,
    ) { collection, rows, prefs, draftOrder ->
        val byPosition = rows.sortedBy { it.position }.map { it.track }
        var tracks = TrackSorter.sort(byPosition, prefs.sortKey, prefs.ascending)
        if (draftOrder != null && prefs.sortKey == SortKey.CUSTOM) {
            val byId = tracks.associateBy { it.id }
            tracks = draftOrder.mapNotNull { byId[it] } + tracks.filter { it.id !in draftOrder.toSet() }
        }
        CollectionDetailState(false, collection, tracks, prefs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionDetailState())

    fun onDragMove(from: Int, to: Int) {
        val s = state.value
        if (s.prefs.locked || s.prefs.sortKey != SortKey.CUSTOM) return
        draft.value = Reorder.move(draft.value ?: s.tracks.map { it.id }, from, to)
    }

    fun onDragEnd() {
        val order = draft.value ?: return
        viewModelScope.launch {
            try {
                c.layouts.saveCollectionTrackOrder(collectionId, order)
                withTimeoutOrNull(1_000) { state.first { it.prefs.sortKey != SortKey.CUSTOM || it.tracks.map { t -> t.id } == order } }
            } catch (e: LayoutLockedException) {
                emit(R.string.msg_layout_locked)
            } finally {
                draft.value = null
            }
        }
    }

    fun setLocked(locked: Boolean) { viewModelScope.launch { c.layouts.setLocked(scope, locked, defaults) } }
    fun updatePrefs(t: (LayoutPrefs) -> LayoutPrefs) { viewModelScope.launch { c.layouts.update(scope, defaults, t) } }
    fun rename(name: String) { viewModelScope.launch { c.collections.rename(collectionId, name) } }
    fun deleteCollection(onDone: () -> Unit) { viewModelScope.launch { c.collections.delete(collectionId); onDone() } }

    fun resetOrder() {
        viewModelScope.launch {
            try {
                c.layouts.resetCollectionOrder(collectionId)
                emit(R.string.msg_order_reset)
            } catch (e: LayoutLockedException) {
                emit(R.string.msg_layout_locked)
            }
        }
    }
}

private val TrackSortKeys = listOf(
    SortKey.CUSTOM, SortKey.TITLE, SortKey.ARTIST, SortKey.RECENTLY_ADDED, SortKey.RECENTLY_PLAYED, SortKey.YEAR,
)

@Composable
fun CollectionDetailScreen(collectionId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "collection:$collectionId") { CollectionDetailViewModel(it, collectionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectMode by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editArt by remember { mutableStateOf(false) }

    TrackDialogHost(vm, snackbar, collectionId)
    if (editArt) ArtworkEditorDialog(ArtKind.COLLECTION, collectionId) { editArt = false }

    val prefs = state.prefs
    val tracks = state.tracks
    val selecting = selectMode || selected.isNotEmpty()
    val clear = { vm.clearSelection(); selectMode = false }
    val canDrag = !prefs.locked && prefs.sortKey == SortKey.CUSTOM && !selecting

    XScaffold(
        title = state.collection?.name.orEmpty(),
        snackbar = snackbar,
        onBack = onBack,
        topBarOverride = if (selecting) {
            {
                SelectionTopBar(
                    selected.size, clear,
                    selectionMenu(
                        vm, tracks.filter { it.id in selected }, tracks,
                        extra = listOf(
                            MenuAction(stringResource(R.string.remove_from_collection)) {
                                vm.showDialog(TrackDialog.RemoveFromCollection(selected.toList()))
                            },
                        ),
                    ),
                )
            }
        } else null,
        actions = {
            if (prefs.sortKey == SortKey.CUSTOM) {
                IconButton(onClick = { vm.setLocked(!prefs.locked) }) {
                    Icon(
                        if (prefs.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        stringResource(if (prefs.locked) R.string.unlock_layout else R.string.lock_layout),
                        Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = { showLayout = true }) { Icon(Icons.Filled.Tune, stringResource(R.string.layout)) }
            OverflowMenu(
                listOf(
                    MenuAction(stringResource(R.string.select)) { selectMode = true },
                    MenuAction(stringResource(R.string.rename)) { renaming = true },
                    MenuAction(stringResource(R.string.change_artwork)) { editArt = true },
                    MenuAction(stringResource(R.string.delete_collection)) { confirmDelete = true },
                ),
            )
        },
    ) { padding ->
        if (state.loading) { LoadingBox(Modifier.padding(padding)); return@XScaffold }
        val listState = rememberLazyListState()
        val reorder = rememberReorderableListState(listState, vm::onDragMove, vm::onDragEnd, leadingItems = 1)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().reorderable(reorder, canDrag),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp),
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    ArtworkImage(state.collection?.art, Modifier.size(120.dp), sizePx = 400)
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.tracks_count, tracks.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.play(tracks, 0, false) }, enabled = tracks.isNotEmpty()) {
                                Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), Modifier.size(18.dp))
                            }
                            OutlinedButton(onClick = { vm.play(tracks, tracks.indices.random(), true) }, enabled = tracks.isNotEmpty()) {
                                Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle), Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (tracks.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Icons.Filled.MusicNote, stringResource(R.string.collection_empty_title), stringResource(R.string.collection_empty_message), Modifier.padding(top = 24.dp))
                }
            }
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    modifier = Modifier.draggedItem(reorder, track.id),
                    selected = track.id in selected, selectionMode = selecting,
                    onClick = { if (selecting) vm.toggleSelected(track.id) else vm.play(tracks, tracks.indexOf(track)) },
                    onLongClick = if (canDrag) null else ({ vm.toggleSelected(track.id) }),
                    menu = trackMenu(
                        vm, track,
                        extra = listOf(MenuAction(stringResource(R.string.remove_from_collection)) { vm.showDialog(TrackDialog.RemoveFromCollection(listOf(track.id))) }),
                    ),
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.rename), initial = state.collection?.name.orEmpty(),
            label = stringResource(R.string.collection_name), confirmLabel = stringResource(R.string.save),
            onConfirm = vm::rename, onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_collection),
            message = stringResource(R.string.delete_collection_message),
            confirmLabel = stringResource(R.string.delete), destructive = true,
            onConfirm = { vm.deleteCollection(onBack) }, onDismiss = { confirmDelete = false },
        )
    }
    if (showLayout) {
        LayoutSheet(
            prefs = prefs, sortKeys = TrackSortKeys, onChange = vm::updatePrefs, onLockChange = vm::setLocked,
            onResetOrder = vm::resetOrder, onDismiss = { showLayout = false }, showColumns = false,
        )
    }
}
