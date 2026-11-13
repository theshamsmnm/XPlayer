package com.xplayer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xplayer.app.TestSupport.audio
import com.xplayer.app.data.db.FavoriteEntity
import com.xplayer.app.data.repo.CollectionRepository
import com.xplayer.app.data.repo.MusicRepository
import com.xplayer.app.data.sync.LibrarySynchronizer
import com.xplayer.app.data.artwork.ArtworkStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Music library scanning, duplicate detection and persistence across rescans (needs an Android runtime). */
@RunWith(AndroidJUnit4::class)
class LibrarySyncTest {
    private lateinit var db: com.xplayer.app.data.db.XPlayerDatabase
    private lateinit var source: TestSupport.FakeSource
    private lateinit var sync: LibrarySynchronizer
    private lateinit var music: MusicRepository
    private lateinit var collections: CollectionRepository

    @Before fun setUp() {
        db = TestSupport.inMemoryDb()
        source = TestSupport.FakeSource()
        sync = LibrarySynchronizer(db, source, TestSupport.dispatchers)
        music = MusicRepository(db)
        collections = CollectionRepository(db, ArtworkStore(TestSupport.context, db, TestSupport.dispatchers))
    }

    @After fun tearDown() { db.close() }

    @Test fun firstScanGroupsTracksIntoAlbumsAndArtists() = runBlocking {
        source.files = listOf(
            audio(1, album = "A", artist = "X"), audio(2, album = "A", artist = "X"), audio(3, album = "B", artist = "Y"),
        )
        val r = sync.apply(source.files)
        assertEquals(3, r.added)
        assertEquals(2, music.observeAlbums().first().size)
        assertEquals(2, music.observeArtists().first().size)
    }

    @Test fun rescanCreatesNoDuplicates() = runBlocking {
        source.files = listOf(audio(1), audio(2))
        sync.apply(source.files)
        val second = sync.apply(source.files)
        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(2, db.trackDao().availableCount())
    }

    @Test fun duplicateRowsFromSourceAreIgnored() = runBlocking {
        val r = sync.apply(listOf(audio(1), audio(1)))
        assertEquals(1, r.added)
    }

    @Test fun removedFilesAreFlaggedNotDeletedAndUserDataSurvives() = runBlocking {
        sync.apply(listOf(audio(1), audio(2)))
        val trackId = db.trackDao().syncSnapshot().first { it.mediaStoreId == 1L }.id
        music.setTrackFavorite(trackId, true)
        val cid = collections.create("Mix", listOf(trackId))

        val r = sync.apply(listOf(audio(2)))
        assertEquals(1, r.markedMissing)
        assertEquals(1, db.trackDao().availableCount())
        // user-owned rows still there
        assertTrue(db.favoriteDao().isFavorite(FavoriteEntity.TRACK, trackId))
        assertEquals(listOf(trackId), db.collectionDao().trackIds(cid))
        assertEquals(1, music.observeMissing().first().size)

        // the file comes back (e.g. SD card remounted): same row is re-enabled
        sync.apply(listOf(audio(1), audio(2)))
        assertEquals(2, db.trackDao().availableCount())
        assertEquals(0, music.observeMissing().first().size)
    }

    @Test fun movedFileIsRelinkedByFingerprintKeepingFavorite() = runBlocking {
        sync.apply(listOf(audio(1, title = "Song", path = "Music/Old/")))
        val id = db.trackDao().syncSnapshot().single().id
        music.setTrackFavorite(id, true)

        // same recording reappears with a new MediaStore id after the old one vanished
        sync.apply(listOf(audio(99, title = "Song", path = "Music/New/")))
        val rows = db.trackDao().syncSnapshot()
        assertEquals(1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals(99L, rows.single().mediaStoreId)
        assertTrue(db.favoriteDao().isFavorite(FavoriteEntity.TRACK, id))
    }

    @Test fun changedMetadataIsUpdatedInPlace() = runBlocking {
        sync.apply(listOf(audio(1, title = "Old", modified = 1)))
        val id = db.trackDao().syncSnapshot().single().id
        val r = sync.apply(listOf(audio(1, title = "New", modified = 2)))
        assertEquals(1, r.updated)
        assertEquals("New", db.trackDao().getById(id)!!.title)
    }

    @Test fun unchangedGenerationTokenSkipsTheScan() = runBlocking {
        source.files = listOf(audio(1)); source.token = "1:5"
        val first = sync.sync(force = false, lastToken = null)
        assertFalse(first.skipped)
        val second = sync.sync(force = false, lastToken = first.token)
        assertTrue(second.skipped)
        val forced = sync.sync(force = true, lastToken = first.token)
        assertFalse(forced.skipped)
    }

    @Test fun hiddenTracksLeaveTheLibraryButStayInTheDatabase() = runBlocking {
        sync.apply(listOf(audio(1), audio(2)))
        val id = db.trackDao().syncSnapshot().first().id
        music.hideTracks(listOf(id))
        assertEquals(1, music.observeAlbums().first().sumOf { it.trackCount })
        assertEquals(1, music.observeHidden().first().size)
        music.unhideTracks(listOf(id))
        assertEquals(2, music.observeAlbums().first().sumOf { it.trackCount })
    }

    @Test fun duplicateRecordingsAreDetected() = runBlocking {
        sync.apply(listOf(audio(1, title = "Same", path = "a/"), audio(2, title = "Same", path = "b/"), audio(3, title = "Other")))
        // two different MediaStore ids with an identical fingerprint -> the second becomes a relink? No: both are
        // available, so the second is inserted as its own row and reported as duplicate.
        assertEquals(2, music.observeDuplicates().first().size)
    }

    @Test fun searchFindsTracksByTitleArtistAndAlbum() = runBlocking {
        sync.apply(listOf(audio(1, title = "Yesterday", artist = "Beatles", album = "Help")))
        assertEquals(1, music.search("yester").tracks.size)
        assertEquals(1, music.search("beat").tracks.size)
        assertEquals(1, music.search("help").albums.size)
        assertEquals(1, music.search("beatles").artists.size)
        assertEquals(0, music.search("zzz").tracks.size)
        assertNotNull(music.search("100%").tracks) // wildcard characters are escaped, no crash
    }
}
