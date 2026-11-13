package com.xplayer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeysTest {
    @Test fun normalizeIsCaseWhitespaceAndUnicodeInsensitive() {
        assertEquals("abbey road", TextKeys.normalize("  Abbey   ROAD "))
        assertEquals(TextKeys.normalize("ﬁne"), TextKeys.normalize("fine")) // NFKC ligature
    }

    @Test fun persianZwnjIsASeparator() {
        assertEquals("می خواهم", TextKeys.normalize("می‌خواهم"))
    }

    @Test fun fingerprintIgnoresLocationButNotContent() {
        val a = TextKeys.trackFingerprint("Song", "Artist", "Album", 200_400)
        val sameSecond = TextKeys.trackFingerprint(" song ", "ARTIST", "album", 200_999)
        val other = TextKeys.trackFingerprint("Song", "Artist", "Album", 201_000)
        assertEquals(a, sameSecond)
        assertNotEquals(a, other)
    }

    @Test fun untitledAlbumsAreSeparatedPerFolder() {
        val a = TextKeys.albumKey("", "", "Music/A/")
        val b = TextKeys.albumKey("", "", "Music/B/")
        assertNotEquals(a, b)
        assertEquals(TextKeys.albumKey("X", "Y", "p1"), TextKeys.albumKey("x", "y", "p2"))
    }

    @Test fun likeEscaping() {
        assertEquals("100\\%\\_\\\\", TextKeys.escapeLike("100%_\\"))
    }

    @Test fun sortKeyIgnoresLeadingArticle() {
        assertEquals("beatles", TextKeys.sortKey("The Beatles"))
        assertTrue(TextKeys.sortKey("A Tribe") < TextKeys.sortKey("Zebra"))
    }

    @Test fun durationFormat() {
        assertEquals("0:00", formatDuration(-5))
        assertEquals("3:05", formatDuration(185_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }
}
