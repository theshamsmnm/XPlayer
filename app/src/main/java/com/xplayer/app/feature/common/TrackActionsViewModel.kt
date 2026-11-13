package com.xplayer.app.feature.common

import android.content.IntentSender
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.domain.model.CollectionItem
import com.xplayer.app.domain.model.TrackItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot UI events. */
sealed interface UiEvent {
    data class Message(@StringRes val res: Int, val args: List<Any> = emptyList()) : UiEvent
    /** Launch a system confirmation (delete / write request). The result is routed back to the VM. */
    data class LaunchSender(val sender: IntentSender) : UiEvent
}

/** Dialogs a track-list screen can show. Kept in the ViewModel so they survive rotation. */
sealed interface TrackDialog {
    data class AddToCollection(val trackIds: List<Long>) : TrackDialog
    data class ConfirmHide(val trackIds: List<Long>) : TrackDialog
    data class ConfirmDelete(val trackIds: List<Long>) : TrackDialog
    data class EditMetadata(val track: TrackItem) : TrackDialog
    data class RemoveFromCollection(val trackIds: List<Long>) : TrackDialog
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.appContainer
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

private sealed interface PendingSystemAction {
    data class Delete(val trackIds: List<Long>) : PendingSystemAction
    data class Edit(val trackId: Long, val title: String, val artist: String, val album: String) : PendingSystemAction
}

/**
 * Shared behaviour of every screen that lists tracks: selection, playback commands, favorites,
 * collections, hiding, deletion and metadata editing. Screens only add their own data.
 */
abstract class TrackActionsViewModel(protected val c: AppContainer) : ViewModel() {

    val collections: StateFlow<List<CollectionItem>> = c.collections.observeCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _dialog = MutableStateFlow<TrackDialog?>(null)
    val dialog: StateFlow<TrackDialog?> = _dialog.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var pending: PendingSystemAction? = null

    // ---- selection -------------------------------------------------------------------------------------

    fun toggleSelected(id: Long) = _selected.update { if (id in it) it - id else it + id }
    fun selectAll(ids: Collection<Long>) { _selected.value = ids.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    // ---- dialogs ---------------------------------------------------------------------------------------

    fun showDialog(d: TrackDialog) { _dialog.value = d }
    fun dismissDialog() { _dialog.value = null }

    protected fun emit(@StringRes res: Int, vararg args: Any) { _events.tryEmit(UiEvent.Message(res, args.toList())) }

    // ---- playback ----------------------------------------------------------------------------------------

    fun play(tracks: List<TrackItem>, index: Int = 0, shuffle: Boolean? = null) = c.playback.playTracks(tracks, index, shuffle)

    fun playNext(tracks: List<TrackItem>) {
        c.playback.playNext(tracks)
        emit(R.string.msg_play_next)
    }

    fun addToQueue(tracks: List<TrackItem>) {
        c.playback.addToQueue(tracks)
        emit(R.string.msg_added_to_queue)
    }

    // ---- library / favorites -------------------------------------------------------------------------------

    fun toggleFavorite(trackId: Long) { viewModelScope.launch { c.music.toggleTrackFavorite(trackId) } }

    fun toggleFavoriteBatch(ids: List<Long>) {
        viewModelScope.launch {
            val nowFavorite = c.music.toggleTracksFavorite(ids)
            emit(if (nowFavorite) R.string.msg_favorited else R.string.msg_unfavorited, ids.size)
        }
        clearSelection()
    }

    fun addToCollection(collectionId: Long, ids: List<Long>) {
        viewModelScope.launch {
            val added = c.collections.addTracks(collectionId, ids)
            emit(R.string.msg_added_to_collection, added)
        }
        clearSelection()
    }

    fun createCollectionWith(name: String, ids: List<Long>) {
        viewModelScope.launch {
            c.collections.create(name, ids)
            emit(R.string.msg_collection_created, name)
        }
        clearSelection()
    }

    open fun removeFromCollection(collectionId: Long, ids: List<Long>) {
        viewModelScope.launch {
            c.collections.removeTracks(collectionId, ids)
            emit(R.string.msg_removed_from_collection, ids.size)
        }
        clearSelection()
    }

    fun hide(ids: List<Long>) {
        viewModelScope.launch {
            c.music.hideTracks(ids)
            emit(R.string.msg_hidden, ids.size)
        }
        clearSelection()
    }

    // ---- deletion (system confirmation always required) -----------------------------------------------------

    fun delete(ids: List<Long>) {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                emit(R.string.msg_delete_unsupported)
                return@launch
            }
            val intent = c.libraryManager.createDeleteRequest(ids) ?: return@launch
            pending = PendingSystemAction.Delete(ids)
            _events.emit(UiEvent.LaunchSender(intent.intentSender))
        }
    }

    // ---- metadata editing (Android 11+) -----------------------------------------------------------------------

    fun editMetadata(trackId: Long, title: String, artist: String, album: String) {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                emit(R.string.msg_edit_unsupported)
                return@launch
            }
            val intent = c.libraryManager.createWriteRequest(listOf(trackId)) ?: return@launch
            pending = PendingSystemAction.Edit(trackId, title, artist, album)
            _events.emit(UiEvent.LaunchSender(intent.intentSender))
        }
    }

    /** Called by the screen with the outcome of the system confirmation dialog. */
    fun onSystemResult(granted: Boolean) {
        val action = pending ?: return
        pending = null
        if (!granted) return
        viewModelScope.launch {
            when (action) {
                is PendingSystemAction.Delete -> {
                    c.libraryManager.forgetTracks(action.trackIds)
                    emit(R.string.msg_deleted, action.trackIds.size)
                }
                is PendingSystemAction.Edit -> {
                    val ok = c.libraryManager.applyMetadata(action.trackId, action.title, action.artist, action.album)
                    if (ok) c.syncCoordinator.syncNow(force = true)
                    emit(if (ok) R.string.msg_metadata_saved else R.string.msg_metadata_failed)
                }
            }
        }
        clearSelection()
    }
}
