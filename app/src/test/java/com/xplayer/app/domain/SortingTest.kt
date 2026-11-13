package com.xplayer.app.domain

import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.ArtworkRef
import com.xplayer.app.domain.model.LayoutPrefs
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.ViewMode
import com.xplayer.app.domain.sort.AlbumSorter
import com.xplayer.app.domain.sort.Reorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortingTest {
    private fun album(id: Long, title: String, artist: String = "", year: Int = 0, added: Long = 0, played: Long = 0, tracks: Int = 1, pos: Int = Int.MAX_VALUE) =
        AlbumItem(id, title, artist, year, tracks, added, played, pos, false, ArtworkRef(ArtKind.ALBUM, id, 0, title))

    private val albums = listOf(
        album(1, "The Wall", "Pink Floyd", 1979, added = 10, played = 0, tracks = 26),
        album(2, "Abbey Road", "The Beatles", 1969, added = 30, played = 500, tracks = 17),
        album(3, "Zen", "Anon", 2001, added = 20, played = 900, tracks = 5),
    )

    private fun ids(l: List<AlbumItem>) = l.map { it.id }

    @Test fun alphabeticalIgnoresArticles() {
        assertEquals(listOf(2L, 1L, 3L), ids(AlbumSorter.sort(albums, SortKey.ALPHABETICAL, true))) // abbey, wall (article ignored), zen
    }

    @Test fun alphabeticalReversed() {
        assertEquals(listOf(3L, 1L, 2L), ids(AlbumSorter.sort(albums, SortKey.ALPHABETICAL, false)))
    }

    @Test fun artistSort() {
        assertEquals(listOf(3L, 2L, 1L), ids(AlbumSorter.sort(albums, SortKey.ARTIST, true)))
    }

    @Test fun recencyAndCountsAreNewestOrLargestFirst() {
        assertEquals(listOf(2L, 3L, 1L), ids(AlbumSorter.sort(albums, SortKey.RECENTLY_ADDED, true)))
        assertEquals(listOf(3L, 2L, 1L), ids(AlbumSorter.sort(albums, SortKey.RECENTLY_PLAYED, true)))
        assertEquals(listOf(3L, 1L, 2L), ids(AlbumSorter.sort(albums, SortKey.YEAR, true)))
        assertEquals(listOf(1L, 2L, 3L), ids(AlbumSorter.sort(albums, SortKey.TRACK_COUNT, true)))
    }

    @Test fun customOrderUsesStoredPositionAndPutsNewAlbumsLast() {
        val custom = listOf(album(1, "B", pos = 1), album(2, "A", pos = 0), album(3, "New"))
        assertEquals(listOf(2L, 1L, 3L), ids(AlbumSorter.sort(custom, SortKey.CUSTOM, true)))
        // `ascending` never reverses a custom order
        assertEquals(listOf(2L, 1L, 3L), ids(AlbumSorter.sort(custom, SortKey.CUSTOM, false)))
    }

    @Test fun sortingNeverMutatesInput() {
        val copy = albums.toList()
        AlbumSorter.sort(albums, SortKey.YEAR, true)
        assertEquals(copy, albums)
    }

    @Test fun reorderMove() {
        assertEquals(listOf(2, 3, 1), Reorder.move(listOf(1, 2, 3), 0, 2))
        assertEquals(listOf(3, 1, 2), Reorder.move(listOf(1, 2, 3), 2, 0))
        assertEquals(listOf(1, 2, 3), Reorder.move(listOf(1, 2, 3), 1, 1))
        assertEquals(listOf(1, 2, 3), Reorder.move(listOf(1, 2, 3), 5, 0)) // out of range = no-op
    }

    @Test fun reorderOnlyWhenUnlockedCustomGrid() {
        val base = LayoutPrefs("library")
        assertTrue(base.canReorder)
        assertFalse(base.copy(locked = true).canReorder)
        assertFalse(base.copy(sortKey = SortKey.YEAR).canReorder)
        assertFalse(base.copy(viewMode = ViewMode.LIST).canReorder)
    }
}
