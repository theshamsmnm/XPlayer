package com.xplayer.app.playback

import androidx.room.withTransaction
import com.xplayer.app.data.db.QueueItemEntity
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.data.repo.SavedPlayback
import com.xplayer.app.data.repo.SettingsRepository

/** A queue restored from storage: track ids in queue order plus where to resume. */
data class RestoredQueue(val trackIds: List<Long>, val index: Int, val positionMs: Long)

/**
 * Persists the playback queue (Room) and the resume position (DataStore) so playback can be restored
 * after the process is recreated. The *player* remains the source of truth while alive; this is only
 * a snapshot the service writes.
 */
class QueueStore(private val db: XPlayerDatabase, private val settings: SettingsRepository) {

    suspend fun saveQueue(trackIds: List<Long>) = db.withTransaction {
        db.queueDao().clear()
        db.queueDao().insertAll(trackIds.mapIndexed { i, id -> QueueItemEntity(i, id) })
    }

    suspend fun savePosition(index: Int, positionMs: Long) = settings.savePlayback(index, positionMs)

    suspend fun restore(): RestoredQueue? {
        val ids = db.queueDao().all().map { it.trackId }
        if (ids.isEmpty()) return null
        val saved: SavedPlayback = settings.savedPlayback() ?: SavedPlayback(0, 0)
        return RestoredQueue(ids, saved.index.coerceIn(0, ids.lastIndex), saved.positionMs.coerceAtLeast(0))
    }

    suspend fun clear() {
        db.queueDao().clear()
        settings.clearSavedPlayback()
    }

    companion object {
        /**
         * After dropping unavailable tracks from a restored queue the saved index may point elsewhere:
         * returns the new index of the originally current track, or the nearest valid one.
         */
        fun remapIndex(originalIds: List<Long>, originalIndex: Int, keptIds: List<Long>): Int {
            if (keptIds.isEmpty()) return 0
            val current = originalIds.getOrNull(originalIndex)
            val direct = keptIds.indexOf(current)
            if (direct >= 0) return direct
            // The current track vanished: use the first kept track that followed it in the original order.
            val kept = keptIds.toHashSet()
            val next = originalIds.drop(originalIndex + 1).firstOrNull { it in kept }
            return next?.let { keptIds.indexOf(it) } ?: keptIds.lastIndex
        }
    }
}
