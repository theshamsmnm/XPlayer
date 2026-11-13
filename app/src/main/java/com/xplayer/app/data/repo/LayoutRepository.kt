package com.xplayer.app.data.repo

import androidx.room.withTransaction
import com.xplayer.app.core.LayoutLockedException
import com.xplayer.app.data.db.CollectionOrderEntity
import com.xplayer.app.data.db.CollectionTrackCrossRef
import com.xplayer.app.data.db.LibraryOrderEntity
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.sort.Reorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns per-scope display preferences, the layout lock and every custom ordering write.
 *
 * Rules enforced here (not in the UI, so they cannot be bypassed):
 *  - Any order write on a locked scope throws [LayoutLockedException] and changes nothing.
 *  - Display preference changes (columns, size, spacing, titles, sort) go through [update], which only
 *    copies those fields: the lock flag and the stored order are never reset by them.
 *  - Custom order is independent of the playback queue (separate tables, separate code).
 */
class LayoutRepository(private val db: XPlayerDatabase) {
    private val layoutDao = db.layoutDao()

    fun observe(scope: String, defaults: LayoutPrefs = LayoutPrefs(scope)): Flow<LayoutPrefs> =
        layoutDao.observe(scope).map { it?.toModel() ?: defaults.copy(scopeKey = scope) }

    suspend fun get(scope: String, defaults: LayoutPrefs = LayoutPrefs(scope)): LayoutPrefs =
        layoutDao.get(scope)?.toModel() ?: defaults.copy(scopeKey = scope)

    /** Applies [transform] to the display fields only. Lock state is always carried over unchanged. */
    suspend fun update(scope: String, defaults: LayoutPrefs = LayoutPrefs(scope), transform: (LayoutPrefs) -> LayoutPrefs) {
        db.withTransaction {
            val current = get(scope, defaults)
            val next = transform(current)
            layoutDao.upsert(next.copy(scopeKey = scope, locked = current.locked).toEntity())
        }
    }

    suspend fun setLocked(scope: String, locked: Boolean, defaults: LayoutPrefs = LayoutPrefs(scope)) {
        db.withTransaction {
            val current = get(scope, defaults)
            layoutDao.upsert(current.copy(locked = locked).toEntity())
        }
    }

    /** Resets display preferences (not order, not lock) of every scope to defaults. Used by Settings. */
    suspend fun resetDisplayPreferences() {
        db.withTransaction {
            val all = layoutDao.all()
            layoutDao.upsertAll(
                all.map {
                    LayoutPrefs(scopeKey = it.scopeKey, locked = it.locked, sortKey = SortKey.CUSTOM).toEntity()
                },
            )
        }
    }

    private suspend fun requireUnlocked(scope: String) {
        if (get(scope).locked) throw LayoutLockedException(scope)
    }

    // ---- Album order in the library ------------------------------------------------------------

    /**
     * Persists a drag of the album at [from] to [to] inside [displayedIds] (the order currently on
     * screen, custom order). The whole visible order is written so that the arrangement is stable even
     * if some albums have never been positioned before.
     */
    suspend fun moveAlbum(scope: String, displayedIds: List<Long>, from: Int, to: Int) =
        saveAlbumOrder(scope, Reorder.move(displayedIds, from, to))

    /** Persists the complete visible order (what the drag produced). Refused while locked. */
    suspend fun saveAlbumOrder(scope: String, orderedIds: List<Long>) {
        db.withTransaction {
            requireUnlocked(scope)
            db.albumDao().upsertOrder(orderedIds.mapIndexed { i, id -> LibraryOrderEntity(id, i) })
        }
    }

    /** Clears the saved custom album order (alphabetical fallback). Refused while locked. */
    suspend fun resetAlbumOrder(scope: String) {
        db.withTransaction {
            requireUnlocked(scope)
            db.albumDao().clearOrder()
        }
    }

    // ---- Collection list order ----------------------------------------------------------------

    suspend fun saveCollectionOrder(orderedIds: List<Long>) {
        db.withTransaction {
            requireUnlocked(LayoutPrefs.SCOPE_COLLECTIONS)
            db.collectionDao().upsertOrder(orderedIds.mapIndexed { i, id -> CollectionOrderEntity(id, i) })
        }
    }

    suspend fun moveCollection(displayedIds: List<Long>, from: Int, to: Int) =
        saveCollectionOrder(Reorder.move(displayedIds, from, to))

    // ---- Track order inside a collection ------------------------------------------------------

    suspend fun saveCollectionTrackOrder(collectionId: Long, orderedTrackIds: List<Long>) {
        db.withTransaction {
            requireUnlocked(LayoutPrefs.collectionScope(collectionId))
            val dao = db.collectionDao()
            val refs = dao.crossRefs(collectionId).associateBy { it.trackId }
            dao.upsertTracks(orderedTrackIds.mapIndexedNotNull { i, id -> refs[id]?.copy(position = i) })
        }
    }

    suspend fun moveCollectionTrack(collectionId: Long, displayedTrackIds: List<Long>, from: Int, to: Int) =
        saveCollectionTrackOrder(collectionId, Reorder.move(displayedTrackIds, from, to))

    /** Collection track order reset: back to the order tracks were added. */
    suspend fun resetCollectionOrder(collectionId: Long) {
        db.withTransaction {
            requireUnlocked(LayoutPrefs.collectionScope(collectionId))
            val dao = db.collectionDao()
            val sorted: List<CollectionTrackCrossRef> = dao.crossRefs(collectionId).sortedWith(
                compareBy({ it.addedAt }, { it.trackId }),
            )
            dao.upsertTracks(sorted.mapIndexed { i, r -> r.copy(position = i) })
        }
    }
}
