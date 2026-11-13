package com.xplayer.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xplayer.app.R
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.ViewMode

@Composable
fun sortLabel(key: SortKey): String = stringResource(
    when (key) {
        SortKey.CUSTOM -> R.string.sort_custom
        SortKey.ALPHABETICAL -> R.string.sort_alphabetical
        SortKey.TITLE -> R.string.sort_title
        SortKey.ARTIST -> R.string.sort_artist
        SortKey.RECENTLY_ADDED -> R.string.sort_recently_added
        SortKey.RECENTLY_PLAYED -> R.string.sort_recently_played
        SortKey.YEAR -> R.string.sort_year
        SortKey.TRACK_COUNT -> R.string.sort_track_count
    },
)

/**
 * Display + sort + lock controls for one layout scope. Every control edits display fields only:
 * changing them never alters the saved order or the lock state.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LayoutSheet(
    prefs: LayoutPrefs,
    sortKeys: List<SortKey>,
    onChange: ((LayoutPrefs) -> LayoutPrefs) -> Unit,
    onLockChange: (Boolean) -> Unit,
    onResetOrder: (() -> Unit)?,
    onDismiss: () -> Unit,
    showColumns: Boolean = true,
    showViewMode: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.layout), style = MaterialTheme.typography.titleMedium)

            if (showViewMode) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ViewMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = prefs.viewMode == mode,
                            onClick = { onChange { it.copy(viewMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ViewMode.entries.size),
                        ) { Text(stringResource(if (mode == ViewMode.GRID) R.string.view_grid else R.string.view_list)) }
                    }
                }
            }

            if (showColumns && prefs.viewMode == ViewMode.GRID) {
                Text(stringResource(R.string.columns), style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(2, 3, 4).forEachIndexed { i, n ->
                        SegmentedButton(
                            selected = prefs.columns == n,
                            onClick = { onChange { it.copy(columns = n) } },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(n.toString()) }
                    }
                }
                Text(stringResource(R.string.artwork_size), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = prefs.artworkScale,
                    onValueChange = { v -> onChange { it.copy(artworkScale = v) } },
                    valueRange = LayoutPrefs.MIN_ARTWORK_SCALE..1f,
                )
                Text(stringResource(R.string.grid_spacing), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = prefs.spacingDp.toFloat(),
                    onValueChange = { v -> onChange { it.copy(spacingDp = v.toInt()) } },
                    valueRange = 0f..24f,
                )
            }

            ToggleRow(stringResource(R.string.show_titles), prefs.showTitles) { v -> onChange { it.copy(showTitles = v) } }
            ToggleRow(stringResource(R.string.show_artists), prefs.showArtists) { v -> onChange { it.copy(showArtists = v) } }

            Text(stringResource(R.string.sort_by), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sortKeys.forEach { key ->
                    FilterChip(
                        selected = prefs.sortKey == key,
                        onClick = { onChange { it.copy(sortKey = key) } },
                        label = { Text(sortLabel(key)) },
                    )
                }
            }
            if (prefs.sortKey != SortKey.CUSTOM) {
                ToggleRow(stringResource(R.string.sort_reversed), !prefs.ascending) { v -> onChange { it.copy(ascending = !v) } }
            }

            ToggleRow(stringResource(R.string.lock_layout), prefs.locked, onLockChange)
            Text(
                stringResource(if (prefs.locked) R.string.lock_hint_locked else R.string.lock_hint_unlocked),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onResetOrder != null) {
                TextButton(onClick = onResetOrder, enabled = !prefs.locked) { Text(stringResource(R.string.reset_order)) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
