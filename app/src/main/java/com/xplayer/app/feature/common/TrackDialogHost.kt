package com.xplayer.app.feature.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xplayer.app.R
import com.xplayer.app.ui.components.ConfirmDialog
import com.xplayer.app.ui.components.TextInputDialog

/**
 * Renders the dialogs requested by a [TrackActionsViewModel], launches system confirmation dialogs and
 * forwards one-shot messages to the snackbar. Place once per screen.
 */
@Composable
fun TrackDialogHost(vm: TrackActionsViewModel, snackbar: SnackbarHostState, collectionId: Long? = null) {
    val context = LocalContext.current
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onSystemResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(context.getString(event.res, *event.args.toTypedArray()))
                is UiEvent.LaunchSender -> launcher.launch(IntentSenderRequest.Builder(event.sender).build())
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is TrackDialog.AddToCollection -> AddToCollectionDialog(
            collections = collections.map { it.id to it.name },
            onPick = { vm.addToCollection(it, d.trackIds) },
            onCreate = { vm.createCollectionWith(it, d.trackIds) },
            onDismiss = vm::dismissDialog,
        )
        is TrackDialog.ConfirmHide -> ConfirmDialog(
            title = stringResource(R.string.hide_tracks_title),
            message = stringResource(R.string.hide_tracks_message),
            confirmLabel = stringResource(R.string.hide),
            onConfirm = { vm.hide(d.trackIds) },
            onDismiss = vm::dismissDialog,
        )
        is TrackDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.delete_files_title),
            message = stringResource(R.string.delete_files_message, d.trackIds.size),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { vm.delete(d.trackIds) },
            onDismiss = vm::dismissDialog,
        )
        is TrackDialog.RemoveFromCollection -> ConfirmDialog(
            title = stringResource(R.string.remove_from_collection_title),
            message = stringResource(R.string.remove_from_collection_message),
            confirmLabel = stringResource(R.string.remove),
            onConfirm = { collectionId?.let { id -> vm.removeFromCollection(id, d.trackIds) } },
            onDismiss = vm::dismissDialog,
        )
        is TrackDialog.EditMetadata -> EditMetadataDialog(
            title = d.track.title, artist = d.track.artist, album = d.track.albumTitle,
            onSave = { t, ar, al -> vm.editMetadata(d.track.id, t, ar, al) },
            onDismiss = vm::dismissDialog,
        )
    }
}

@Composable
fun AddToCollectionDialog(
    collections: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(collections.isEmpty()) }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_collection),
            label = stringResource(R.string.collection_name),
            confirmLabel = stringResource(R.string.create),
            onConfirm = onCreate,
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_collection)) },
        text = {
            Column {
                TextButton(onClick = { creating = true }) { Text(stringResource(R.string.new_collection)) }
                HorizontalDivider()
                LazyColumn {
                    items(collections, key = { it.first }) { (id, name) ->
                        Text(
                            name,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(id); onDismiss() }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun EditMetadataDialog(
    title: String,
    artist: String,
    album: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var t by remember { mutableStateOf(title) }
    var ar by remember { mutableStateOf(artist) }
    var al by remember { mutableStateOf(album) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_info)) },
        text = {
            Column {
                OutlinedTextField(t, { t = it }, label = { Text(stringResource(R.string.field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ar, { ar = it }, label = { Text(stringResource(R.string.field_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(al, { al = it }, label = { Text(stringResource(R.string.field_album)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = t.isNotBlank(), onClick = { onSave(t.trim(), ar.trim(), al.trim()); onDismiss() }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
