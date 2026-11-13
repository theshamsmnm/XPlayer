package com.xplayer.app.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.domain.sort.Reorder
import com.xplayer.app.playback.QueueEntry
import com.xplayer.app.ui.art.ArtworkImage
import com.xplayer.app.ui.components.ConfirmDialog
import com.xplayer.app.ui.components.draggedItem
import com.xplayer.app.ui.components.rememberReorderableListState
import com.xplayer.app.ui.components.reorderable

/**
 * The playback queue (owned by the playback service; independent of library/collection order).
 * Supports: play a specific entry, remove, drag to reorder (long press), clear.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val playback = androidx.compose.ui.platform.LocalContext.current.appContainer.playback
    val state by playback.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<List<QueueEntry>?>(null) }
    var movedKey by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val shown = draft ?: state.queue
    val reorder = rememberReorderableListState(
        listState,
        onMove = { from, to ->
            val base = draft ?: state.queue
            if (movedKey == null) movedKey = base.getOrNull(from)?.index
            draft = Reorder.move(base, from, to)
        },
        onDragEnd = {
            val key = movedKey
            val final = draft
            if (key != null && final != null) {
                val to = final.indexOfFirst { it.index == key }
                if (to >= 0 && to != key) playback.moveInQueue(key, to)
            }
            draft = null
            movedKey = null
        },
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmClear = true }, enabled = state.queue.isNotEmpty()) { Text(stringResource(R.string.clear_queue)) }
        }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.queue_empty), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding().reorderable(reorder, enabled = true),
        ) {
            items(shown, key = { it.index }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .draggedItem(reorder, entry.index)
                        .clickable { playback.skipToQueueItem(entry.index) }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkImage(entry.art, Modifier.size(40.dp), sizePx = 96, shape = RoundedCornerShape(2.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                            color = if (entry.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { playback.removeFromQueue(entry.index) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.remove_from_queue), Modifier.size(18.dp))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.queue_reorder_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_queue),
            message = stringResource(R.string.clear_queue_message),
            confirmLabel = stringResource(R.string.clear), destructive = true,
            onConfirm = { playback.clearQueue(); onDismiss() },
            onDismiss = { confirmClear = false },
        )
    }
}
