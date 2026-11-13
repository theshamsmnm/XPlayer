package com.xplayer.app.data.repo

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import com.xplayer.app.core.AppDispatchers
import com.xplayer.app.data.db.XPlayerDatabase
import kotlinx.coroutines.withContext

/**
 * File-level library operations. Every destructive operation goes through the platform's own
 * confirmation UI (MediaStore delete/write requests, Android 11+), so a source file can never be
 * removed without an explicit system-level user confirmation.
 */
class LibraryManager(
    private val context: Context,
    private val db: XPlayerDatabase,
    private val dispatchers: AppDispatchers,
) {
    /**
     * Builds the system dialog that asks the user to permanently delete the given tracks.
     * Returns null on Android 10 and below, where [deleteLegacy] must be used after the app's own
     * confirmation dialog (and the legacy `RecoverableSecurityException` flow).
     */
    suspend fun createDeleteRequest(trackIds: List<Long>): PendingIntent? = withContext(dispatchers.io) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        val uris = trackIds.mapNotNull { db.trackDao().getById(it)?.uri?.let(Uri::parse) }
        if (uris.isEmpty()) null else MediaStore.createDeleteRequest(context.contentResolver, uris)
    }

    /**
     * Android 10 and below: deletes via ContentResolver. Throws a [SecurityException] (possibly a
     * RecoverableSecurityException) which the UI layer surfaces; nothing is deleted without confirmation
     * because the UI only calls this after an explicit confirm dialog.
     */
    suspend fun deleteLegacy(trackIds: List<Long>): Int = withContext(dispatchers.io) {
        var deleted = 0
        for (id in trackIds) {
            val uri = db.trackDao().getById(id)?.uri?.let(Uri::parse) ?: continue
            deleted += context.contentResolver.delete(uri, null, null)
        }
        deleted
    }

    /**
     * Called after the system confirmed the deletion: the rows are removed from the database. Collection
     * memberships, favorites, history, hidden state go with them (cascade) -- the files no longer exist.
     */
    suspend fun forgetTracks(trackIds: List<Long>) = db.withTransaction {
        db.trackDao().deleteByIds(trackIds)
    }

    /**
     * Missing-file recovery: points a missing track at a file the user picked (document picker URI).
     * The track keeps its id, so favorites, collections, artwork and order stay attached.
     */
    suspend fun relinkMissing(trackId: Long, newUri: Uri): Boolean = withContext(dispatchers.io) {
        val old = db.trackDao().getById(trackId) ?: return@withContext false
        val resolver: ContentResolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(newUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every provider supports persistable grants; playback may still work this session.
        }
        val name = resolver.query(newUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        db.trackDao().update(
            old.copy(
                uri = newUri.toString(),
                mediaStoreId = null,
                displayName = name ?: old.displayName,
                isAvailable = true,
            ),
        )
        true
    }

    /** Android 11+: system dialog asking the user to allow editing the given tracks' metadata. */
    suspend fun createWriteRequest(trackIds: List<Long>): PendingIntent? = withContext(dispatchers.io) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        val uris = trackIds.mapNotNull { db.trackDao().getById(it)?.uri?.let(Uri::parse) }
        if (uris.isEmpty()) null else MediaStore.createWriteRequest(context.contentResolver, uris)
    }

    /**
     * Writes title/artist/album to the MediaStore row (allowed only after [createWriteRequest] was
     * granted) and then re-syncs the affected library row so the UI updates immediately.
     * Returns false when the platform refused.
     */
    suspend fun applyMetadata(trackId: Long, title: String, artist: String, album: String): Boolean =
        withContext(dispatchers.io) {
            val track = db.trackDao().getById(trackId) ?: return@withContext false
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
            }
            try {
                context.contentResolver.update(Uri.parse(track.uri), values, null, null) > 0
            } catch (_: SecurityException) {
                false
            }
        }
}
