package com.xplayer.app.data.sync

import androidx.room.withTransaction
import com.xplayer.app.core.AppDispatchers
import com.xplayer.app.core.TextKeys
import com.xplayer.app.data.db.AlbumEntity
import com.xplayer.app.data.db.ArtistEntity
import com.xplayer.app.data.db.TrackEntity
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.data.mediastore.AudioSource
import com.xplayer.app.data.mediastore.ScannedAudio
import com.xplayer.app.domain.model.SyncResult
import com.xplayer.app.domain.model.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Incremental MediaStore -> Room synchronisation.
 *
 * Invariants (verified by unit tests):
 *  1. No duplicates: a file is matched by its MediaStore id (unique index).
 *  2. Rows are never deleted: vanished files are flagged `isAvailable = 0`, so favorites, collections,
 *     hidden state, custom artwork and ordering survive. Reappearing files are re-enabled.
 *  3. Moved/re-encoded files are relinked by fingerprint to the old row (user data stays attached).
 *  4. Only scanner-owned tables (artists, albums, tracks) are written. User-owned tables are untouched.
 *  5. Unchanged files (same modified time + size) are not rewritten.
 *  6. All writes of one pass happen in a single transaction (all-or-nothing).
 */
class LibrarySynchronizer(
    private val db: XPlayerDatabase,
    private val source: AudioSource,
    private val dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    /**
     * Runs a synchronisation. With [force] false and an unchanged MediaStore generation token, the pass
     * is skipped entirely. Concurrent calls are serialised.
     */
    suspend fun sync(force: Boolean = false, lastToken: String? = null): SyncOutcome = mutex.withLock {
        withContext(dispatchers.io) {
            val token = source.generationToken()
            if (!force && token != null && token == lastToken) {
                return@withContext SyncOutcome(SyncResult(totalAvailable = db.trackDao().availableCount()), token, skipped = true)
            }
            try {
                _state.value = SyncState.Running(0, 0, force)
                val scanned = source.queryAll()
                _state.value = SyncState.Running(0, scanned.size, force)
                val result = apply(scanned)
                _state.value = SyncState.Done(result)
                SyncOutcome(result, token, skipped = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = SyncState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = SyncState.Failed(e.message)
                throw e
            }
        }
    }

    /** Applies a snapshot of the audio source to the database. Exposed for tests. */
    suspend fun apply(scanned: List<ScannedAudio>): SyncResult = db.withTransaction {
        val trackDao = db.trackDao()
        val artistDao = db.artistDao()
        val albumDao = db.albumDao()

        val snapshot = trackDao.syncSnapshot()
        val byMediaId = HashMap<Long, com.xplayer.app.data.db.TrackSyncRow>(snapshot.size)
        snapshot.forEach { row -> row.mediaStoreId?.let { byMediaId[it] = row } }

        val artistIds = HashMap<String, Long>().apply {
            artistDao.idsByName().forEach { put(it.normalizedName, it.id) }
        }
        val albumIds = HashMap<String, Long>().apply {
            albumDao.idsByKey().forEach { put(it.albumKey, it.id) }
        }

        suspend fun artistId(name: String): Long? {
            val key = TextKeys.normalize(name)
            if (key.isEmpty()) return null
            return artistIds.getOrPut(key) { artistDao.insert(ArtistEntity(name = name, normalizedName = key)) }
        }

        suspend fun albumId(a: ScannedAudio): Long {
            val albumArtist = a.albumArtist.ifBlank { a.artist }
            val folder = a.relativePath
            val key = TextKeys.albumKey(a.album, albumArtist, folder)
            return albumIds.getOrPut(key) {
                albumDao.insert(
                    AlbumEntity(
                        albumKey = key,
                        title = a.album,
                        artistId = artistId(albumArtist),
                        artistName = albumArtist,
                        year = a.year,
                        dateAddedSec = a.dateAddedSec,
                    ),
                )
            }
        }

        var added = 0
        var updated = 0
        var relinked = 0
        val seenMediaIds = HashSet<Long>(scanned.size)
        val reported = scanned.mapTo(HashSet(scanned.size)) { it.mediaStoreId }

        // Rows that currently have no file: flagged missing, manually linked (no MediaStore id), or about to
        // vanish in this very pass. A *new* file with the same fingerprint takes over such a row (moved file).
        val relinkPool = HashMap<String, ArrayDeque<com.xplayer.app.data.db.TrackSyncRow>>()
        snapshot.filter { it.mediaStoreId == null || it.mediaStoreId !in reported }
            .sortedBy { it.id }
            .forEach { relinkPool.getOrPut(it.fingerprint) { ArrayDeque() }.addLast(it) }
        val claimed = HashSet<Long>()

        for (a in scanned) {
            if (!seenMediaIds.add(a.mediaStoreId)) continue // duplicate row from the source
            val fingerprint = TextKeys.trackFingerprint(a.title, a.artist, a.album, a.durationMs)
            val existing = byMediaId[a.mediaStoreId]

            if (existing != null) {
                val changed = existing.dateModifiedSec != a.dateModifiedSec ||
                    existing.sizeBytes != a.sizeBytes || !existing.isAvailable ||
                    existing.fingerprint != fingerprint // tags edited (here or by another app)
                if (changed) {
                    val old = trackDao.getById(existing.id) ?: continue
                    trackDao.update(toEntity(a, old.id, albumId(a), artistId(a.artist), fingerprint, old.lastPlayedAt))
                    updated++
                }
                continue
            }

            // New MediaStore id: maybe a previously seen recording whose file moved.
            val candidate = relinkPool[fingerprint]?.removeFirstOrNull()
            if (candidate != null) {
                claimed += candidate.id
                val old = trackDao.getById(candidate.id)
                if (old != null) {
                    trackDao.update(toEntity(a, old.id, albumId(a), artistId(a.artist), fingerprint, old.lastPlayedAt))
                    relinked++
                    continue
                }
            }

            trackDao.insert(toEntity(a, 0, albumId(a), artistId(a.artist), fingerprint, 0))
            added++
        }

        // Anything previously available but not reported any more is flagged (never deleted).
        val missing = snapshot.filter { row ->
            row.isAvailable && row.mediaStoreId != null && row.mediaStoreId !in seenMediaIds && row.id !in claimed
        }.map { it.id }
        missing.chunked(500).forEach { trackDao.markUnavailable(it) }

        albumDao.refreshAggregates()

        SyncResult(
            added = added,
            updated = updated,
            markedMissing = missing.size,
            relinked = relinked,
            totalAvailable = trackDao.availableCount(),
        )
    }

    private fun toEntity(
        a: ScannedAudio,
        id: Long,
        albumId: Long,
        artistId: Long?,
        fingerprint: String,
        lastPlayedAt: Long,
    ) = TrackEntity(
        id = id,
        mediaStoreId = a.mediaStoreId,
        uri = a.uri,
        title = a.title,
        artistId = artistId,
        artistName = a.artist,
        albumId = albumId,
        trackNumber = a.trackNumber,
        discNumber = a.discNumber,
        durationMs = a.durationMs,
        year = a.year,
        dateAddedSec = a.dateAddedSec,
        dateModifiedSec = a.dateModifiedSec,
        sizeBytes = a.sizeBytes,
        mimeType = a.mimeType,
        displayName = a.displayName,
        relativePath = a.relativePath,
        fingerprint = fingerprint,
        searchKey = TextKeys.normalize("${a.title} ${a.artist} ${a.album}"),
        isAvailable = true,
        lastPlayedAt = lastPlayedAt,
    )
}

data class SyncOutcome(val result: SyncResult, val token: String?, val skipped: Boolean)
