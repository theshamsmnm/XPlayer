package com.xplayer.app.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xplayer.app.AppContainer
import com.xplayer.app.R
import com.xplayer.app.data.backup.BackupException
import com.xplayer.app.data.backup.ParsedBackup
import com.xplayer.app.domain.model.AppSettings
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SyncState
import com.xplayer.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of a backup/restore interaction, shown as a message. */
data class SettingsMessage(val res: Int, val args: List<Any> = emptyList())

class SettingsViewModel(private val c: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = c.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val syncState: StateFlow<SyncState> = c.synchronizer.state
    val trackCount: StateFlow<Int> = c.music.observeTrackCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** A validated backup waiting for the user's confirmation to replace existing data. */
    private val _pendingRestore = MutableStateFlow<ParsedBackup?>(null)
    val pendingRestore: StateFlow<ParsedBackup?> = _pendingRestore.asStateFlow()

    /** Display/sort preferences of the main library grid (scope "library"), edited from Settings too. */
    val libraryPrefs: StateFlow<LayoutPrefs> = c.layouts.observe(LayoutPrefs.SCOPE_LIBRARY)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LayoutPrefs(LayoutPrefs.SCOPE_LIBRARY))
    fun updateLibraryPrefs(t: (LayoutPrefs) -> LayoutPrefs) { viewModelScope.launch { c.layouts.update(LayoutPrefs.SCOPE_LIBRARY, transform = t) } }
    fun setLibraryLocked(locked: Boolean) { viewModelScope.launch { c.layouts.setLocked(LayoutPrefs.SCOPE_LIBRARY, locked) } }

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { c.settings.setTheme(mode) } }

    fun setAutoScan(enabled: Boolean) {
        viewModelScope.launch {
            c.settings.setAutoScan(enabled)
            if (enabled) {
                c.syncCoordinator.startObserving(); c.syncCoordinator.schedulePeriodic()
                c.syncCoordinator.syncNow(false)
            } else {
                c.syncCoordinator.stopObserving(); c.syncCoordinator.cancelPeriodic()
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            try {
                val outcome = c.syncCoordinator.syncNow(force = true)
                val r = outcome?.result
                if (r == null) _message.value = SettingsMessage(R.string.msg_permission_needed)
                else _message.value = SettingsMessage(R.string.msg_rescan_done, listOf(r.added, r.updated, r.markedMissing))
            } catch (e: Exception) {
                _message.value = SettingsMessage(R.string.msg_rescan_failed)
            }
        }
    }

    fun setResume(enabled: Boolean) { viewModelScope.launch { c.settings.setResumePlayback(enabled) } }
    fun setShuffle(enabled: Boolean) { viewModelScope.launch { c.settings.setShuffle(enabled) } }
    fun setRepeat(mode: Int) { viewModelScope.launch { c.settings.setRepeatMode(mode) } }

    fun clearHistory() {
        viewModelScope.launch { c.music.clearHistory(); _message.value = SettingsMessage(R.string.msg_history_cleared) }
    }

    fun resetLayoutPreferences() {
        viewModelScope.launch { c.layouts.resetDisplayPreferences(); _message.value = SettingsMessage(R.string.msg_layout_reset) }
    }

    // ---- Backup / restore (system document pickers) ------------------------------------------------------

    fun export(target: Uri, resolver: android.content.ContentResolver) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val out = resolver.openOutputStream(target, "wt") ?: error("cannot open")
                out.use { c.backup.export(it) }
                _message.value = SettingsMessage(R.string.msg_backup_saved)
            } catch (e: Exception) {
                _message.value = SettingsMessage(R.string.msg_backup_failed)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Step 1: read + validate without changing anything. Step 2 (after confirmation): [confirmRestore]. */
    fun prepareRestore(source: Uri, resolver: android.content.ContentResolver) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val input = resolver.openInputStream(source) ?: throw BackupException(BackupException.Kind.NOT_A_BACKUP)
                _pendingRestore.value = input.use { c.backup.parse(it) }
            } catch (e: BackupException) {
                _message.value = SettingsMessage(
                    when (e.kind) {
                        BackupException.Kind.NOT_A_BACKUP -> R.string.msg_restore_not_backup
                        BackupException.Kind.TOO_NEW -> R.string.msg_restore_too_new
                        BackupException.Kind.TOO_OLD -> R.string.msg_restore_too_old
                        else -> R.string.msg_restore_corrupt
                    },
                )
            } catch (e: Exception) {
                _message.value = SettingsMessage(R.string.msg_restore_corrupt)
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancelRestore() { _pendingRestore.value = null }

    fun confirmRestore() {
        val parsed = _pendingRestore.value ?: return
        _pendingRestore.value = null
        viewModelScope.launch {
            _busy.value = true
            try {
                val s = c.backup.restore(parsed)
                _message.value = SettingsMessage(R.string.msg_restore_done, listOf(s.collections, s.favorites, s.skippedTracks))
            } catch (e: Exception) {
                // The database transaction rolled back; nothing was changed.
                _message.value = SettingsMessage(R.string.msg_restore_failed)
            } finally {
                _busy.value = false
            }
        }
    }

    fun suggestedBackupName(): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "xplayer-backup-${fmt.format(java.util.Date())}.xpbackup"
    }
}
