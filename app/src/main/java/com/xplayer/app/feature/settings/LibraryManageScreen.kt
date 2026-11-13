package com.xplayer.app.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.feature.common.TrackActionsViewModel
import com.xplayer.app.feature.common.TrackDialog
import com.xplayer.app.feature.common.TrackDialogHost
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.ui.components.ConfirmDialog
import com.xplayer.app.ui.components.EmptyState
import com.xplayer.app.ui.components.MenuAction
import com.xplayer.app.ui.components.TrackRow
import com.xplayer.app.ui.components.XScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryManageViewModel(c: AppContainer) : TrackActionsViewModel(c) {
    private fun share(f: kotlinx.coroutines.flow.Flow<List<TrackItem>>): StateFlow<List<TrackItem>> =
        f.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hidden = share(c.music.observeHidden())
    val missing = share(c.music.observeMissing())
    val duplicates = share(c.music.observeDuplicates())

    fun unhide(id: Long) { viewModelScope.launch { c.music.unhideTracks(listOf(id)) } }
    fun unhideAll() { viewModelScope.launch { c.music.unhideTracks(hidden.value.map { it.id }) } }

    /** Missing-file recovery: attach a file picked by the user; the track keeps its favorites/collections/artwork. */
    fun relink(trackId: Long, uri: Uri) {
        viewModelScope.launch {
            val ok = c.libraryManager.relinkMissing(trackId, uri)
            emit(if (ok) R.string.msg_relinked else R.string.msg_relink_failed)
        }
    }

    /** Forgets a missing track permanently (removes it from collections, favorites...). */
    fun forget(id: Long) {
        viewModelScope.launch { c.libraryManager.forgetTracks(listOf(id)); emit(R.string.msg_forgotten) }
    }
}

@Composable
fun LibraryManageScreen(onBack: () -> Unit) {
    val vm = appViewModel { LibraryManageViewModel(it) }
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val duplicates by vm.duplicates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var relinkTarget by remember { mutableStateOf<Long?>(null) }
    var forgetTarget by remember { mutableStateOf<Long?>(null) }
    TrackDialogHost(vm, snackbar)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = relinkTarget
        if (uri != null && id != null) vm.relink(id, uri)
        relinkTarget = null
    }

    XScaffold(stringResource(R.string.manage_library), snackbar, onBack = onBack) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.hidden_tracks, R.string.missing_files, R.string.duplicates).forEachIndexed { i, res ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res)) })
                }
            }
            val list = when (tab) { 0 -> hidden; 1 -> missing; else -> duplicates }
            if (list.isEmpty()) {
                EmptyState(
                    Icons.Filled.CheckCircle,
                    stringResource(when (tab) { 0 -> R.string.no_hidden; 1 -> R.string.no_missing; else -> R.string.no_duplicates }),
                )
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.id }) { track ->
                    val menu = when (tab) {
                        0 -> listOf(MenuAction(stringResource(R.string.unhide)) { vm.unhide(track.id) })
                        1 -> listOf(
                            MenuAction(stringResource(R.string.locate_file)) { relinkTarget = track.id; picker.launch(arrayOf("audio/*")) },
                            MenuAction(stringResource(R.string.forget_track)) { forgetTarget = track.id },
                        )
                        else -> listOf(
                            MenuAction(stringResource(R.string.hide)) { vm.showDialog(TrackDialog.ConfirmHide(listOf(track.id))) },
                            MenuAction(stringResource(R.string.delete_file)) { vm.showDialog(TrackDialog.ConfirmDelete(listOf(track.id))) },
                        )
                    }
                    TrackRow(track, onClick = {}, menu = menu)
                }
            }
        }
    }

    forgetTarget?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.forget_track),
            message = stringResource(R.string.forget_track_message),
            confirmLabel = stringResource(R.string.forget), destructive = true,
            onConfirm = { vm.forget(id) }, onDismiss = { forgetTarget = null },
        )
    }
}
