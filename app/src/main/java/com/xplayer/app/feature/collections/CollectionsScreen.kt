package com.xplayer.app.feature.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QueueMusic
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.core.LayoutLockedException
import com.xplayer.app.domain.model.CollectionItem
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.sort.Reorder
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.LayoutSheet
import com.xplayer.app.ui.components.LoadingBox
import com.xplayer.app.ui.components.TextInputDialog
import com.xplayer.app.ui.components.XScaffold
import com.xplayer.app.ui.components.draggedItem
import com.xplayer.app.ui.components.rememberReorderableGridState
import com.xplayer.app.ui.components.reorderable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class CollectionsState(
    val loading: Boolean = true,
    val collections: List<CollectionItem> = emptyList(),
    val prefs: LayoutPrefs = LayoutPrefs(LayoutPrefs.SCOPE_COLLECTIONS),
)

class CollectionsViewModel(private val c: AppContainer) : ViewModel() {
    private val scope = LayoutPrefs.SCOPE_COLLECTIONS
    private val draft = MutableStateFlow<List<Long>?>(null)

    val state: StateFlow<CollectionsState> = combine(
        c.collections.observeCollections(), c.layouts.observe(scope), draft,
    ) { items, prefs, draftOrder ->
        // Custom order is the stored position; other sorts are display-only.
        var list = when (prefs.sortKey) {
            SortKey.CUSTOM -> items
            SortKey.RECENTLY_ADDED -> items.sortedByDescending { it.createdAt }
            SortKey.TRACK_COUNT -> items.sortedByDescending { it.trackCount }
            else -> items.sortedBy { it.name.lowercase() }
        }
        if (prefs.sortKey != SortKey.CUSTOM && !prefs.ascending) list = list.reversed()
        if (draftOrder != null && prefs.sortKey == SortKey.CUSTOM) {
            val byId = list.associateBy { it.id }
            list = draftOrder.mapNotNull { byId[it] } + list.filter { it.id !in draftOrder.toSet() }
        }
        CollectionsState(false, list, prefs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionsState())

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message
    fun messageShown() { _message.value = null }

    fun create(name: String) { viewModelScope.launch { c.collections.create(name) } }

    fun onDragMove(from: Int, to: Int) {
        val s = state.value
        if (!s.prefs.canReorder) return
        draft.value = Reorder.move(draft.value ?: s.collections.map { it.id }, from, to)
    }

    fun onDragEnd() {
        val order = draft.value ?: return
        viewModelScope.launch {
            try {
                c.layouts.saveCollectionOrder(order)
                withTimeoutOrNull(1_000) { state.first { it.collections.map { c -> c.id } == order } }
            } catch (e: LayoutLockedException) {
                _message.value = R.string.msg_layout_locked
            } finally {
                draft.value = null
            }
        }
    }

    fun setLocked(locked: Boolean) { viewModelScope.launch { c.layouts.setLocked(scope, locked) } }
    fun updatePrefs(t: (LayoutPrefs) -> LayoutPrefs) { viewModelScope.launch { c.layouts.update(scope, transform = t) } }
}

private val CollectionSortKeys = listOf(SortKey.CUSTOM, SortKey.ALPHABETICAL, SortKey.RECENTLY_ADDED, SortKey.TRACK_COUNT)

@Composable
fun CollectionsScreen(onOpenCollection: (Long) -> Unit) {
    val vm = appViewModel { CollectionsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }
    val prefs = state.prefs

    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(context.getString(it)); vm.messageShown() }
    }

    XScaffold(
        title = stringResource(R.string.nav_collections),
        snackbar = snackbar,
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
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, stringResource(R.string.new_collection)) }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.collections.isEmpty() -> EmptyState(
                Icons.Filled.QueueMusic, stringResource(R.string.no_collections_title),
                stringResource(R.string.no_collections_message), Modifier.padding(padding),
                actionLabel = stringResource(R.string.new_collection), onAction = { creating = true },
            )
            else -> {
                val gridState = rememberLazyGridState()
                val reorder = rememberReorderableGridState(gridState, vm::onDragMove, vm::onDragEnd)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(prefs.columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().reorderable(reorder, prefs.canReorder),
                    contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(prefs.spacingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(prefs.spacingDp.dp + 8.dp),
                ) {
                    items(state.collections, key = { it.id }) { item ->
                        Column(Modifier.draggedItem(reorder, item.id).clickable { onOpenCollection(item.id) }) {
                            ArtworkImage(item.art, Modifier.fillMaxWidth(prefs.artworkScale), sizePx = 420)
                            if (prefs.showTitles) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            }
                            if (prefs.showArtists) {
                                Text(
                                    stringResource(R.string.tracks_count, item.trackCount),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_collection),
            label = stringResource(R.string.collection_name),
            confirmLabel = stringResource(R.string.create),
            onConfirm = vm::create,
            onDismiss = { creating = false },
        )
    }
    if (showLayout) {
        LayoutSheet(
            prefs = prefs, sortKeys = CollectionSortKeys, onChange = vm::updatePrefs, onLockChange = vm::setLocked,
            onResetOrder = null, onDismiss = { showLayout = false },
        )
    }
}
