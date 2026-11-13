package com.xplayer.app.domain.sort

import com.xplayer.app.core.TextKeys
import com.xplayer.app.domain.model.AlbumItem
import com.xplayer.app.domain.model.SortKey
import com.xplayer.app.domain.model.TrackItem

/**
 * Pure sorting + reordering logic (unit-tested). Sorting never mutates the persisted custom order:
 * the custom order is only changed by an explicit drag-and-drop through [Reorder].
 */
object AlbumSorter {
    fun sort(albums: List<AlbumItem>, key: SortKey, ascending: Boolean): List<AlbumItem> {
        val comparator: Comparator<AlbumItem> = when (key) {
            // Custom: saved position first; albums never arranged yet follow in alphabetical order.
            SortKey.CUSTOM -> compareBy<AlbumItem> { it.orderPosition }
                .thenBy { TextKeys.sortKey(it.title) }
                .thenBy { it.id }
            SortKey.ALPHABETICAL, SortKey.TITLE -> compareBy<AlbumItem> { TextKeys.sortKey(it.title) }.thenBy { it.id }
            SortKey.ARTIST -> compareBy<AlbumItem> { TextKeys.sortKey(it.artist) }
                .thenBy { TextKeys.sortKey(it.title) }.thenBy { it.id }
            SortKey.RECENTLY_ADDED -> compareByDescending<AlbumItem> { it.dateAddedSec }.thenBy { it.id }
            SortKey.RECENTLY_PLAYED -> compareByDescending<AlbumItem> { it.lastPlayedAt }
                .thenBy { TextKeys.sortKey(it.title) }
            SortKey.YEAR -> compareByDescending<AlbumItem> { it.year }.thenBy { TextKeys.sortKey(it.title) }
            SortKey.TRACK_COUNT -> compareByDescending<AlbumItem> { it.trackCount }
                .thenBy { TextKeys.sortKey(it.title) }
        }
        val sorted = albums.sortedWith(comparator)
        // `ascending` = the key's natural direction (A-Z, newest first, highest first); false reverses it.
        val reverse = !ascending
        return if (key == SortKey.CUSTOM || !reverse) sorted else sorted.reversed()
    }
}

object TrackSorter {
    fun sort(tracks: List<TrackItem>, key: SortKey, ascending: Boolean): List<TrackItem> {
        val comparator: Comparator<TrackItem> = when (key) {
            SortKey.CUSTOM -> return tracks
            SortKey.ALPHABETICAL, SortKey.TITLE -> compareBy<TrackItem> { TextKeys.sortKey(it.title) }.thenBy { it.id }
            SortKey.ARTIST -> compareBy<TrackItem> { TextKeys.sortKey(it.artist) }
                .thenBy { TextKeys.sortKey(it.albumTitle) }
                .thenBy { it.discNumber }.thenBy { it.trackNumber }
            SortKey.RECENTLY_ADDED -> compareByDescending<TrackItem> { it.dateAddedSec }.thenBy { it.id }
            SortKey.RECENTLY_PLAYED -> compareByDescending<TrackItem> { it.lastPlayedAt }.thenBy { it.id }
            SortKey.YEAR -> compareByDescending<TrackItem> { it.year }.thenBy { TextKeys.sortKey(it.title) }
            SortKey.TRACK_COUNT -> compareBy<TrackItem> { TextKeys.sortKey(it.albumTitle) }
                .thenBy { it.discNumber }.thenBy { it.trackNumber }
        }
        val sorted = tracks.sortedWith(comparator)
        val reverse = !ascending
        return if (reverse) sorted.reversed() else sorted
    }
}

/** List-move helpers shared by library order, collection order and the playback queue. */
object Reorder {
    /** Returns a copy of [list] with the element at [from] moved to [to]. Out-of-range indexes are no-ops. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}
