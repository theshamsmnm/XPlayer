package com.xplayer.app.feature.library

import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.core.LayoutLockedException
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.sort.AlbumSorter
import com.xplayer.app.domain.sort.Reorder
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AlbumGridState(
    val loading: Boolean = true,
    val albums: List<AlbumItem> = emptyList(),
    val prefs: LayoutPrefs = LayoutPrefs(LayoutPrefs.SCOPE_LIBRARY),
)

/**
 * Backs both the Home/Library grid (scope "library") and the Albums section (scope "albums").
 * Both share the single persisted custom order (Room `library_order`) but keep independent display
 * preferences and lock state.
 *
 * Ownership: persistent order/lock/prefs = Room (via LayoutRepository); the in-flight drag order is
 * temporary UI state ([draft]) that is committed once, on drop.
 */
class AlbumGridViewModel(c: AppContainer, val scope: String) : TrackActionsViewModel(c) {

    private val defaults = if (scope == LayoutPrefs.SCOPE_ALBUMS) {
        LayoutPrefs(scope, sortKey = SortKey.ALPHABETICAL, showArtists = true)
    } else LayoutPrefs(scope)

    private val draft = MutableStateFlow<List<Long>?>(null)
    private val _selectedAlbums = MutableStateFlow<Set<Long>>(emptySet())
    val selectedAlbums: StateFlow<Set<Long>> = _selectedAlbums.asStateFlow()
    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    val state: StateFlow<AlbumGridState> = combine(
        c.music.observeAlbums(),
        c.layouts.observe(scope, defaults),
        draft,
    ) { albums, prefs, draftOrder ->
        var sorted = AlbumSorter.sort(albums, prefs.sortKey, prefs.ascending)
        if (draftOrder != null && prefs.sortKey == SortKey.CUSTOM) {
            val byId = sorted.associateBy { it.id }
            val ordered = draftOrder.mapNotNull { byId[it] }
            val rest = sorted.filter { it.id !in draftOrder.toSet() }
            sorted = ordered + rest
        }
        AlbumGridState(loading = false, albums = sorted, prefs = prefs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumGridState())

    // ---- drag and drop -----------------------------------------------------------------------------------

    fun onDragMove(from: Int, to: Int) {
        val s = state.value
        if (!s.prefs.canReorder) return
        val ids = draft.value ?: s.albums.map { it.id }
        draft.value = Reorder.move(ids, from, to)
    }

    fun onDragEnd() {
        val order = draft.value ?: return
        viewModelScope.launch {
            try {
                c.layouts.saveAlbumOrder(scope, order)
                // Keep the draft until Room re-emits the same order, so the grid never flickers back.
                withTimeoutOrNull(1_000) {
                    state.first { s -> s.prefs.sortKey != SortKey.CUSTOM || s.albums.map { it.id } == order }
                }
            } catch (e: LayoutLockedException) {
                emit(R.string.msg_layout_locked)
            } finally {
                draft.value = null
            }
        }
    }

    fun setLocked(locked: Boolean) {
        viewModelScope.launch { c.layouts.setLocked(scope, locked, defaults) }
    }

    fun updatePrefs(transform: (LayoutPrefs) -> LayoutPrefs) {
        viewModelScope.launch { c.layouts.update(scope, defaults, transform) }
    }

    fun resetOrder() {
        viewModelScope.launch {
            try {
                c.layouts.resetAlbumOrder(scope)
                emit(R.string.msg_order_reset)
            } catch (e: LayoutLockedException) {
                emit(R.string.msg_layout_locked)
            }
        }
    }

    // ---- album selection ----------------------------------------------------------------------------------

    fun startSelecting() { _selecting.value = true }

    fun toggleAlbum(id: Long) {
        _selectedAlbums.value = _selectedAlbums.value.let { if (id in it) it - id else it + id }
        _selecting.value = true
    }

    fun clearAlbumSelection() {
        _selectedAlbums.value = emptySet()
        _selecting.value = false
    }

    fun selectAllAlbums() {
        _selectedAlbums.value = state.value.albums.map { it.id }.toSet()
    }

    private suspend fun selectedTrackIds(): List<Long> =
        _selectedAlbums.value.flatMap { c.music.trackIdsOfAlbum(it) }

    fun favoriteSelectedAlbums() {
        val ids = _selectedAlbums.value.toList()
        viewModelScope.launch {
            val allFav = state.value.albums.filter { it.id in ids }.all { it.isFavorite }
            ids.forEach { c.music.setAlbumFavorite(it, !allFav) }
            emit(if (allFav) R.string.msg_unfavorited else R.string.msg_favorited, ids.size)
        }
        clearAlbumSelection()
    }

    fun addSelectedToCollection() {
        viewModelScope.launch {
            val ids = selectedTrackIds()
            if (ids.isNotEmpty()) showDialog(TrackDialog.AddToCollection(ids))
        }
    }

    fun queueSelected(asNext: Boolean) {
        viewModelScope.launch {
            val tracks = c.music.tracksByIds(selectedTrackIds())
            if (asNext) playNext(tracks) else addToQueue(tracks)
        }
        clearAlbumSelection()
    }

    fun playAlbum(albumId: Long, shuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = c.music.tracksByIds(c.music.trackIdsOfAlbum(albumId))
            if (tracks.isNotEmpty()) play(tracks, 0, if (shuffle) true else null)
        }
    }

    fun playAll(shuffle: Boolean) {
        viewModelScope.launch {
            val ids = state.value.albums.flatMap { c.music.trackIdsOfAlbum(it.id) }
            val tracks = c.music.tracksByIds(ids)
            if (tracks.isNotEmpty()) play(tracks, if (shuffle) tracks.indices.random() else 0, shuffle)
        }
    }
}
