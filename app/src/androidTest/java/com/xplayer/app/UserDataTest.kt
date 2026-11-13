package com.xplayer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xplayer.app.TestSupport.audio
import com.xplayer.app.core.LayoutLockedException
import com.xplayer.app.data.artwork.ArtworkStore
import com.xplayer.app.data.backup.BackupManager
import com.xplayer.app.data.db.FavoriteEntity
import com.xplayer.app.data.repo.CollectionRepository
import com.xplayer.app.data.repo.LayoutRepository
import com.xplayer.app.data.repo.MusicRepository
import com.xplayer.app.data.repo.SettingsRepository
import com.xplayer.app.data.sync.LibrarySynchronizer
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Collections, favorites, layout lock, album order and backup/restore on a real (in-memory) Room database. */
@RunWith(AndroidJUnit4::class)
class UserDataTest {
    private lateinit var db: com.xplayer.app.data.db.XPlayerDatabase
    private lateinit var music: MusicRepository
    private lateinit var collections: CollectionRepository
    private lateinit var layouts: LayoutRepository
    private lateinit var backup: BackupManager
    private lateinit var settings: SettingsRepository

    @Before fun setUp() = runBlocking {
        db = TestSupport.inMemoryDb()
        val artwork = ArtworkStore(TestSupport.context, db, TestSupport.dispatchers)
        music = MusicRepository(db)
        collections = CollectionRepository(db, artwork)
        layouts = LayoutRepository(db)
        settings = SettingsRepository(TestSupport.context)
        backup = BackupManager(db, settings, artwork, TestSupport.dispatchers, "test")
        LibrarySynchronizer(db, TestSupport.FakeSource(), TestSupport.dispatchers).apply(
            listOf(audio(1, album = "A"), audio(2, album = "B"), audio(3, album = "C")),
        ).let { }
        Unit
    }

    @After fun tearDown() { db.close() }

    private suspend fun trackIds() = db.trackDao().syncSnapshot().sortedBy { it.id }.map { it.id }
    private suspend fun albumIds() = music.observeAlbums().first().map { it.id }

    // ---- collections ---------------------------------------------------------------------------------

    @Test fun collectionLifecycleNeverTouchesTheLibrary() = runBlocking {
        val t = trackIds()
        val id = collections.create("Road trip", t.take(2))
        assertEquals(2, collections.observeTracks(id).first().size)

        collections.removeTracks(id, listOf(t[0]))
        assertEquals(1, collections.observeTracks(id).first().size)
        assertEquals(3, db.trackDao().availableCount()) // library unchanged

        collections.rename(id, "Trip")
        assertEquals("Trip", collections.observeCollection(id).first()!!.name)

        collections.delete(id)
        assertEquals(null, collections.observeCollection(id).first())
        assertEquals(3, db.trackDao().availableCount())
    }

    @Test fun addingTheSameTrackTwiceDoesNotDuplicate() = runBlocking {
        val t = trackIds()
        val id = collections.create("X", listOf(t[0]))
        assertEquals(0, collections.addTracks(id, listOf(t[0])))
        assertEquals(1, collections.addTracks(id, listOf(t[0], t[1])))
    }

    @Test fun collectionTrackOrderIsIndependentAndLockable() = runBlocking {
        val t = trackIds()
        val id = collections.create("Ordered", t)
        val scope = LayoutPrefs.collectionScope(id)
        layouts.saveCollectionTrackOrder(id, listOf(t[2], t[0], t[1]))
        assertEquals(listOf(t[2], t[0], t[1]), collections.observeTracks(id).first().map { it.track.id })

        layouts.setLocked(scope, true)
        try {
            layouts.saveCollectionTrackOrder(id, t)
            fail("locked layout accepted a reorder")
        } catch (_: LayoutLockedException) {}
        assertEquals(listOf(t[2], t[0], t[1]), collections.observeTracks(id).first().map { it.track.id })
    }

    // ---- favorites ---------------------------------------------------------------------------------------

    @Test fun favoritesToggleAndBatch() = runBlocking {
        val t = trackIds()
        assertTrue(music.toggleTrackFavorite(t[0]))
        assertFalse(music.toggleTrackFavorite(t[0]))
        assertTrue(music.toggleTracksFavorite(t))        // mixed -> all favorited
        assertEquals(3, music.observeFavoriteTracks().first().size)
        assertFalse(music.toggleTracksFavorite(t))       // all favorited -> all removed
        assertEquals(0, music.observeFavoriteTracks().first().size)
        music.setAlbumFavorite(albumIds().first(), true)
        assertEquals(1, music.observeFavoriteAlbums().first().size)
    }

    @Test fun historyIsRecordedAndCleared() = runBlocking {
        val t = trackIds()
        music.recordPlay(t[0], 1000); music.recordPlay(t[1], 2000)
        assertEquals(listOf(t[1], t[0]), music.observeHistory().first().map { it.track.id })
        assertEquals(t[1], music.observeRecentlyPlayed().first().first().id)
        music.clearHistory()
        assertEquals(0, music.observeHistory().first().size)
        assertEquals(0, music.observeRecentlyPlayed().first().size)
    }

    // ---- album order + lock --------------------------------------------------------------------------------

    @Test fun albumOrderPersistsAndSurvivesDisplayChanges() = runBlocking {
        val a = albumIds()
        val wanted = listOf(a[2], a[0], a[1])
        layouts.saveAlbumOrder("library", wanted)
        assertEquals(wanted, music.observeAlbums().first().sortedBy { it.orderPosition }.map { it.id })

        // changing columns / artwork size / titles / sorting must not reset order or lock
        layouts.setLocked("library", true)
        layouts.update("library") { it.copy(columns = 4, artworkScale = 0.7f, showTitles = false, sortKey = SortKey.YEAR, locked = false) }
        val prefs = layouts.get("library")
        assertEquals(4, prefs.columns)
        assertTrue("lock must survive display changes", prefs.locked)
        assertEquals(wanted, music.observeAlbums().first().sortedBy { it.orderPosition }.map { it.id })
    }

    @Test fun lockBlocksReorderAndResetButNotDisplayEdits() = runBlocking {
        layouts.setLocked("library", true)
        try { layouts.saveAlbumOrder("library", albumIds()); fail() } catch (_: LayoutLockedException) {}
        try { layouts.resetAlbumOrder("library"); fail() } catch (_: LayoutLockedException) {}
        layouts.update("library") { it.copy(columns = 2) }
        assertEquals(2, layouts.get("library").columns)
    }

    @Test fun layoutLocksAreIndependentPerScope() = runBlocking {
        layouts.setLocked("library", true)
        assertFalse(layouts.get("albums").locked)
        assertFalse(layouts.get(LayoutPrefs.collectionScope(5)).locked)
    }

    @Test fun resetOrderRestoresFallbackWhenUnlocked() = runBlocking {
        layouts.saveAlbumOrder("library", albumIds().reversed())
        layouts.resetAlbumOrder("library")
        assertTrue(music.observeAlbums().first().all { it.orderPosition == Int.MAX_VALUE })
    }

    // ---- backup / restore --------------------------------------------------------------------------------------

    private suspend fun exportBytes(): ByteArray {
        val out = ByteArrayOutputStream(); backup.export(out); return out.toByteArray()
    }

    @Test fun backupRestoreRoundTripReplacesUserData() = runBlocking {
        val t = trackIds()
        val cid = collections.create("Keep", t.take(2))
        music.setTrackFavorite(t[2], true)
        layouts.saveAlbumOrder("library", albumIds().reversed())
        layouts.setLocked("library", true)
        val bytes = exportBytes()

        // mutate everything, then restore
        collections.delete(cid)
        music.setTrackFavorite(t[2], false)
        layouts.setLocked("library", false)
        layouts.resetAlbumOrder("library")

        val parsed = backup.parse(ByteArrayInputStream(bytes))
        val summary = backup.restore(parsed)
        assertEquals(1, summary.collections)
        assertEquals(0, summary.skippedTracks)

        val restored = collections.observeCollections().first().single()
        assertEquals("Keep", restored.name)
        assertEquals(2, collections.observeTracks(restored.id).first().size)
        assertTrue(db.favoriteDao().isFavorite(FavoriteEntity.TRACK, t[2]))
        assertTrue(layouts.get("library").locked)
        assertEquals(albumIds().reversed(), music.observeAlbums().first().sortedBy { it.orderPosition }.map { it.id })
    }

    @Test fun invalidArchiveIsRejectedBeforeAnyDataChanges() = runBlocking {
        val t = trackIds()
        collections.create("Original", t)
        music.setTrackFavorite(t[0], true)
        try {
            backup.parse(ByteArrayInputStream("definitely not a zip".toByteArray()))
            fail("garbage accepted as a backup")
        } catch (e: com.xplayer.app.data.backup.BackupException) {
            // expected: parsing happens fully before restore() could touch the database
        }
        assertEquals(listOf("Original"), collections.observeCollections().first().map { it.name })
        assertEquals(1, music.observeFavoriteTracks().first().size)
    }
}
