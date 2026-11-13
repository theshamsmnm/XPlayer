package com.xplayer.app.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview

/** One audio file as reported by MediaStore. */
data class ScannedAudio(
    val mediaStoreId: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val displayName: String?,
    val relativePath: String?,
)

/** Abstraction over MediaStore so the synchroniser can be unit-tested with a fake source. */
interface AudioSource {
    /** All supported audio files currently visible to the app. */
    suspend fun queryAll(): List<ScannedAudio>

    /** Emits (debounced) whenever the system audio collection changes. */
    fun changes(): Flow<Unit>

    /** MediaStore version + generation; changes when the collection changed. Null if unsupported. */
    fun generationToken(): String?
}

class MediaStoreScanner(private val context: Context) : AudioSource {

    private val collection: Uri =
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override suspend fun queryAll(): List<ScannedAudio> {
        val resolver = context.contentResolver
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.DISC_NUMBER)
            }
        }.toTypedArray()

        // Only real music; skips ringtones, alarms, notifications and podcasts' system sounds.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(MIN_DURATION_MS.toString())

        val out = ArrayList<ScannedAudio>(1024)
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val albumArtistCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val discCol = c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.getString(nameCol)
                val rawTrack = c.getInt(trackCol)
                // MediaStore encodes disc as the thousands digit of TRACK on older versions.
                val (disc, track) = if (discCol >= 0 && !c.isNull(discCol)) {
                    parseDisc(c.getString(discCol)) to rawTrack % 1000
                } else if (rawTrack >= 1000) {
                    rawTrack / 1000 to rawTrack % 1000
                } else {
                    1 to rawTrack
                }
                out += ScannedAudio(
                    mediaStoreId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = cleanText(c.getString(titleCol)) ?: displayName?.substringBeforeLast('.') ?: "",
                    artist = cleanText(c.getString(artistCol)).orEmpty(),
                    album = cleanText(c.getString(albumCol)).orEmpty(),
                    albumArtist = if (albumArtistCol >= 0) cleanText(c.getString(albumArtistCol)).orEmpty() else "",
                    durationMs = c.getLong(durCol),
                    trackNumber = track,
                    discNumber = disc,
                    year = c.getInt(yearCol),
                    dateAddedSec = c.getLong(addedCol),
                    dateModifiedSec = c.getLong(modCol),
                    sizeBytes = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol),
                    displayName = displayName,
                    relativePath = if (pathCol >= 0) c.getString(pathCol) else null,
                )
            }
        }
        return out
    }

    @OptIn(FlowPreview::class)
    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.debounce(OBSERVER_DEBOUNCE_MS)

    override fun generationToken(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val v = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
            val g = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
            "$v:$g"
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDisc(raw: String?): Int =
        raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1

    private fun cleanText(value: String?): String? {
        val v = value?.trim()
        // MediaStore reports "<unknown>" for missing tags.
        return if (v.isNullOrEmpty() || v == MediaStore.UNKNOWN_STRING || v == "<unknown>") null else v
    }

    companion object {
        /** Files shorter than this are almost never music (UI sounds, voice notes). */
        const val MIN_DURATION_MS = 10_000L
        const val OBSERVER_DEBOUNCE_MS = 1_500L
    }
}
