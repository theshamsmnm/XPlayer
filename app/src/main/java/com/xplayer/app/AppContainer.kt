package com.xplayer.app

import android.content.Context
import com.xplayer.app.core.DefaultDispatchers
import com.xplayer.app.data.artwork.ArtworkStore
import com.xplayer.app.data.backup.BackupManager
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.data.mediastore.MediaStoreScanner
import com.xplayer.app.data.repo.CollectionRepository
import com.xplayer.app.data.repo.LayoutRepository
import com.xplayer.app.data.repo.LibraryManager
import com.xplayer.app.data.repo.MusicRepository
import com.xplayer.app.data.repo.SettingsRepository
import com.xplayer.app.data.sync.LibrarySynchronizer
import com.xplayer.app.data.sync.SyncCoordinator
import com.xplayer.app.playback.PlaybackController
import com.xplayer.app.playback.QueueStore
import com.xplayer.app.playback.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Manual dependency container (kept deliberately small instead of adding a DI framework).
 * One instance lives in [XPlayerApp]; the activity, view models, the playback service and the
 * background worker all obtain their collaborators from here, so there is a single database,
 * a single synchroniser and a single artwork store in the process.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val dispatchers = DefaultDispatchers
    /** Process-lifetime scope for work that must outlive any screen (sync, history writes). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: XPlayerDatabase = XPlayerDatabase.build(appContext)
    val settings = SettingsRepository(appContext)
    val scanner = MediaStoreScanner(appContext)
    val synchronizer = LibrarySynchronizer(database, scanner, dispatchers)
    val artwork = ArtworkStore(appContext, database, dispatchers)
    val music = MusicRepository(database)
    val layouts = LayoutRepository(database)
    val collections = CollectionRepository(database, artwork)
    val libraryManager = LibraryManager(appContext, database, dispatchers)
    val queueStore = QueueStore(database, settings)
    val backup = BackupManager(database, settings, artwork, dispatchers, BuildConfig.VERSION_NAME)
    val syncCoordinator = SyncCoordinator(appContext, scanner, synchronizer, settings, appScope)

    /** UI-side handle to the playback service. */
    val playback = PlaybackController(appContext)

    /** Mirror of the sleep timer that lives inside the playback service (same process). */
    val sleepTimerState: MutableStateFlow<SleepTimerState> = MutableStateFlow(SleepTimerState.Off)
    val sleepTimer: StateFlow<SleepTimerState> get() = sleepTimerState

    init {
        // Custom artwork changed (set / replaced / restored): refresh the artwork of queued items.
        appScope.launch {
            database.artworkDao().observeAll().drop(1).collect {
                delay(150)
                val ids = playback.state.value.queue.map { it.trackId }
                if (ids.isNotEmpty()) {
                    val fresh = music.tracksByIds(ids).associate { it.id to it.art }
                    playback.refreshArtwork(fresh)
                }
            }
        }
    }
}
