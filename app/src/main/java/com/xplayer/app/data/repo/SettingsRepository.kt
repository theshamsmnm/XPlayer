package com.xplayer.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xplayer.app.domain.model.AppSettings
import com.xplayer.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "xplayer_settings")

/** Saved playback position + current queue index; the queue itself lives in Room. */
data class SavedPlayback(val index: Int, val positionMs: Long)

/** App-wide preferences (DataStore). Layout/order/lock state belongs to Room, not here. */
class SettingsRepository(private val context: Context) {

    private val store get() = context.dataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            theme = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.AMOLED,
            autoScan = p[AUTO_SCAN] ?: true,
            resumePlayback = p[RESUME] ?: true,
            shuffle = p[SHUFFLE] ?: false,
            repeatMode = (p[REPEAT] ?: 0).coerceIn(0, 2),
        )
    }

    val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.name }
    suspend fun setAutoScan(enabled: Boolean) = store.edit { it[AUTO_SCAN] = enabled }
    suspend fun setResumePlayback(enabled: Boolean) = store.edit { it[RESUME] = enabled }
    suspend fun setShuffle(enabled: Boolean) = store.edit { it[SHUFFLE] = enabled }
    suspend fun setRepeatMode(mode: Int) = store.edit { it[REPEAT] = mode.coerceIn(0, 2) }
    suspend fun setOnboardingDone() = store.edit { it[ONBOARDING_DONE] = true }

    suspend fun lastSyncToken(): String? = store.data.first()[SYNC_TOKEN]
    suspend fun setLastSyncToken(token: String?) = store.edit {
        if (token == null) it.remove(SYNC_TOKEN) else it[SYNC_TOKEN] = token
    }

    suspend fun savedPlayback(): SavedPlayback? {
        val p = store.data.first()
        val index = p[SAVED_INDEX] ?: return null
        return SavedPlayback(index, p[SAVED_POSITION] ?: 0L)
    }

    suspend fun savePlayback(index: Int, positionMs: Long) = store.edit {
        it[SAVED_INDEX] = index
        it[SAVED_POSITION] = positionMs
    }

    suspend fun clearSavedPlayback() = store.edit {
        it.remove(SAVED_INDEX)
        it.remove(SAVED_POSITION)
    }

    /** Replaces the restorable preferences (used by restore). Onboarding/sync token are left alone. */
    suspend fun replaceFrom(settings: AppSettings) = store.edit {
        it[THEME] = settings.theme.name
        it[AUTO_SCAN] = settings.autoScan
        it[RESUME] = settings.resumePlayback
        it[SHUFFLE] = settings.shuffle
        it[REPEAT] = settings.repeatMode.coerceIn(0, 2)
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val RESUME = booleanPreferencesKey("resume_playback")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat_mode")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SYNC_TOKEN = stringPreferencesKey("sync_token")
        val SAVED_INDEX = intPreferencesKey("saved_index")
        val SAVED_POSITION = longPreferencesKey("saved_position")
    }
}
