package com.xplayer.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xplayer.app.XPlayerApp
import com.xplayer.app.core.Permissions
import com.xplayer.app.data.mediastore.AudioSource
import com.xplayer.app.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Decides *when* to synchronise:
 *  - on app start (cheap: skipped when the MediaStore generation token is unchanged),
 *  - whenever MediaStore reports a change while the app process is alive (debounced ContentObserver),
 *  - periodically in the background via WorkManager,
 *  - on demand (Settings > Rescan = forced full pass).
 * Nothing here requires user interaction after the single permission grant.
 */
class SyncCoordinator(
    private val context: Context,
    private val source: AudioSource,
    private val synchronizer: LibrarySynchronizer,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private var observerJob: Job? = null

    /** Incremental sync unless [force]. Safe to call repeatedly (serialised + token-skipped). */
    suspend fun syncNow(force: Boolean = false): SyncOutcome? {
        if (!Permissions.hasAudio(context)) return null
        val outcome = synchronizer.sync(force, if (force) null else settings.lastSyncToken())
        settings.setLastSyncToken(outcome.token)
        return outcome
    }

    /** Starts observing MediaStore (idempotent). Call after the permission is granted. */
    fun startObserving() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            source.changes().collect {
                if (settings.current().autoScan) runCatching { syncNow(force = false) }
            }
        }
    }

    fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
    }

    /** Schedules the background periodic sync (keeps the library fresh while the app is closed). */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    /** One-off background sync (used right after the permission is granted). */
    fun enqueueOneTime() {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LibrarySyncWorker>().build())
    }

    companion object {
        const val PERIODIC_WORK = "xplayer.sync.periodic"
        const val ONE_TIME_WORK = "xplayer.sync.once"
    }
}

class LibrarySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as XPlayerApp).container
        if (!Permissions.hasAudio(applicationContext)) return Result.success()
        if (!container.settings.current().autoScan) return Result.success()
        return try {
            container.syncCoordinator.syncNow(force = false)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
