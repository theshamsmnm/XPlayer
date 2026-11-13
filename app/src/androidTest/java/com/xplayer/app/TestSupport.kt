package com.xplayer.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xplayer.app.core.AppDispatchers
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.data.mediastore.AudioSource
import com.xplayer.app.data.mediastore.ScannedAudio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object TestSupport {
    val context: Context get() = ApplicationProvider.getApplicationContext()

    fun inMemoryDb(): XPlayerDatabase =
        Room.inMemoryDatabaseBuilder(context, XPlayerDatabase::class.java).allowMainThreadQueries().build()

    val dispatchers = object : AppDispatchers {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    fun audio(
        id: Long, title: String = "Track $id", artist: String = "Artist", album: String = "Album",
        durationMs: Long = 200_000, modified: Long = 1, size: Long = 1000, path: String = "Music/",
    ) = ScannedAudio(
        mediaStoreId = id, uri = "content://media/external/audio/media/$id", title = title, artist = artist,
        album = album, albumArtist = "", durationMs = durationMs, trackNumber = id.toInt(), discNumber = 1,
        year = 2000, dateAddedSec = 100 + id, dateModifiedSec = modified, sizeBytes = size,
        mimeType = "audio/mpeg", displayName = "$title.mp3", relativePath = path,
    )

    class FakeSource(var files: List<ScannedAudio> = emptyList(), var token: String? = null) : AudioSource {
        override suspend fun queryAll(): List<ScannedAudio> = files
        override fun changes(): Flow<Unit> = emptyFlow()
        override fun generationToken(): String? = token
    }
}
