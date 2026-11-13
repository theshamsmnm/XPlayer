package com.xplayer.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xplayer.app.R
import com.xplayer.app.domain.model.TrackItem
import com.xplayer.app.ui.components.MenuAction

/** Per-track overflow menu shared by every track list. */
@Composable
fun trackMenu(vm: TrackActionsViewModel, track: TrackItem, extra: List<MenuAction> = emptyList()): List<MenuAction> = listOf(
    MenuAction(stringResource(R.string.play_next)) { vm.playNext(listOf(track)) },
    MenuAction(stringResource(R.string.add_to_queue)) { vm.addToQueue(listOf(track)) },
    MenuAction(stringResource(if (track.isFavorite) R.string.unfavorite else R.string.favorite)) { vm.toggleFavorite(track.id) },
    MenuAction(stringResource(R.string.add_to_collection)) { vm.showDialog(TrackDialog.AddToCollection(listOf(track.id))) },
) + extra + listOf(
    MenuAction(stringResource(R.string.edit_info)) { vm.showDialog(TrackDialog.EditMetadata(track)) },
    MenuAction(stringResource(R.string.hide)) { vm.showDialog(TrackDialog.ConfirmHide(listOf(track.id))) },
    MenuAction(stringResource(R.string.delete_file)) { vm.showDialog(TrackDialog.ConfirmDelete(listOf(track.id))) },
)

/** Menu of the multi-select bar. [allTracks] enables "select all". */
@Composable
fun selectionMenu(
    vm: TrackActionsViewModel,
    selectedTracks: List<TrackItem>,
    allTracks: List<TrackItem>,
    extra: List<MenuAction> = emptyList(),
): List<MenuAction> {
    val ids = selectedTracks.map { it.id }
    return listOf(
        MenuAction(stringResource(R.string.favorite_toggle)) { vm.toggleFavoriteBatch(ids) },
        MenuAction(stringResource(R.string.add_to_collection)) { vm.showDialog(TrackDialog.AddToCollection(ids)) },
        MenuAction(stringResource(R.string.play_next)) { vm.playNext(selectedTracks); vm.clearSelection() },
        MenuAction(stringResource(R.string.add_to_queue)) { vm.addToQueue(selectedTracks); vm.clearSelection() },
    ) + extra + listOf(
        MenuAction(stringResource(R.string.select_all)) { vm.selectAll(allTracks.map { it.id }) },
        MenuAction(stringResource(R.string.hide)) { vm.showDialog(TrackDialog.ConfirmHide(ids)) },
        MenuAction(stringResource(R.string.delete_file)) { vm.showDialog(TrackDialog.ConfirmDelete(ids)) },
    )
}
