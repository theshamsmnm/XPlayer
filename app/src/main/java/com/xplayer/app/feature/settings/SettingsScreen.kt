package com.xplayer.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.xplayer.app.BuildConfig
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.SyncState
import com.xplayer.app.domain.model.ThemeMode
import com.xplayer.app.feature.common.appViewModel
import com.xplayer.app.feature.nowplaying.SleepTimerDialog
import com.xplayer.app.ui.components.ConfirmDialog
import com.xplayer.app.ui.components.LayoutSheet
import com.xplayer.app.ui.components.SectionHeader
import com.xplayer.app.ui.components.XScaffold

@Composable
fun SettingsScreen(onOpenManage: () -> Unit) {
    val context = LocalContext.current
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val count by vm.trackCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()
    val libraryPrefs by vm.libraryPrefs.collectAsStateWithLifecycle()
    val sleep by context.appContainer.sleepTimer.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showLayout by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(context.getString(it.res, *it.args.toTypedArray()))
            vm.messageShown()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.export(uri, context.contentResolver)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareRestore(uri, context.contentResolver)
    }

    XScaffold(stringResource(R.string.nav_settings), snackbar) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState())) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionHeader(stringResource(R.string.appearance))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = settings.theme == mode, onClick = { vm.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) {
                        Text(stringResource(when (mode) { ThemeMode.AMOLED -> R.string.theme_amoled; ThemeMode.DARK -> R.string.theme_dark; ThemeMode.LIGHT -> R.string.theme_light }))
                    }
                }
            }
            ClickRow(stringResource(R.string.library_layout), stringResource(R.string.library_layout_summary)) { showLayout = true }

            SectionHeader(stringResource(R.string.library))
            SwitchRow(stringResource(R.string.auto_scan), stringResource(R.string.auto_scan_summary), settings.autoScan, vm::setAutoScan)
            val syncSummary = when (val s = sync) {
                is SyncState.Running -> stringResource(R.string.scanning)
                is SyncState.Failed -> stringResource(R.string.scan_failed)
                else -> stringResource(R.string.library_count, count)
            }
            ClickRow(stringResource(R.string.rescan_library), syncSummary, onClick = vm::rescan)
            if (sync is SyncState.Running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            ClickRow(stringResource(R.string.manage_library), stringResource(R.string.manage_library_summary), onClick = onOpenManage)

            SectionHeader(stringResource(R.string.playback))
            SwitchRow(stringResource(R.string.resume_playback), stringResource(R.string.resume_playback_summary), settings.resumePlayback, vm::setResume)
            SwitchRow(stringResource(R.string.shuffle_pref), null, settings.shuffle, vm::setShuffle)
            Text(stringResource(R.string.repeat_pref), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf(
                    Player.REPEAT_MODE_OFF to R.string.repeat_off,
                    Player.REPEAT_MODE_ALL to R.string.repeat_all,
                    Player.REPEAT_MODE_ONE to R.string.repeat_one,
                ).forEachIndexed { i, (mode, label) ->
                    SegmentedButton(selected = settings.repeatMode == mode, onClick = { vm.setRepeat(mode) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                        Text(stringResource(label))
                    }
                }
            }
            ClickRow(stringResource(R.string.sleep_timer), stringResource(R.string.sleep_timer_summary)) { showSleep = true }

            SectionHeader(stringResource(R.string.data))
            ClickRow(stringResource(R.string.backup), stringResource(R.string.backup_summary)) { exportLauncher.launch(vm.suggestedBackupName()) }
            ClickRow(stringResource(R.string.restore), stringResource(R.string.restore_summary)) { importLauncher.launch(arrayOf("*/*")) }
            ClickRow(stringResource(R.string.clear_history), null) { confirmHistory = true }
            ClickRow(stringResource(R.string.reset_layout_prefs), stringResource(R.string.reset_layout_prefs_summary)) { confirmReset = true }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text(
                stringResource(R.string.about_fmt, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (showLayout) {
        LayoutSheet(
            prefs = libraryPrefs,
            sortKeys = SortKey.entries.filter { it != SortKey.ALPHABETICAL },
            onChange = vm::updateLibraryPrefs, onLockChange = vm::setLibraryLocked,
            onResetOrder = null, onDismiss = { showLayout = false },
        )
    }
    if (showSleep) SleepTimerDialog(sleep) { showSleep = false }
    if (confirmHistory) {
        ConfirmDialog(
            stringResource(R.string.clear_history), stringResource(R.string.clear_history_message), stringResource(R.string.clear),
            onConfirm = vm::clearHistory, onDismiss = { confirmHistory = false }, destructive = true,
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            stringResource(R.string.reset_layout_prefs), stringResource(R.string.reset_layout_prefs_message), stringResource(R.string.reset),
            onConfirm = vm::resetLayoutPreferences, onDismiss = { confirmReset = false },
        )
    }
    if (pending != null) {
        val m = pending!!.manifest
        ConfirmDialog(
            title = stringResource(R.string.restore_confirm_title),
            message = stringResource(
                R.string.restore_confirm_message,
                m.collections.size, m.favoriteTracks.size + m.favoriteAlbums.size,
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(m.createdAt)),
            ),
            confirmLabel = stringResource(R.string.restore), destructive = true,
            onConfirm = vm::confirmRestore, onDismiss = vm::cancelRestore,
        )
    }
}

@Composable
private fun ClickRow(title: String, summary: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
