package com.xplayer.app.core

import java.text.Normalizer
import java.util.Locale

/**
 * Normalisation helpers that produce the *stable identifiers* used to match library
 * entities across rescans, file moves and backup/restore.
 */
object TextKeys {
    private val whitespace = Regex("\\s+")

    /** Lower-cased, NFKC-normalised, whitespace-collapsed form of [value]. */
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('‌', ' ') // Persian ZWNJ is treated as a word separator
            .trim()
            .replace(whitespace, " ")
    }

    /**
     * Identity of a *recording* independent of its location in storage. Used to relink a track
     * whose file was moved (new MediaStore id) and to resolve tracks when restoring a backup.
     */
    fun trackFingerprint(title: String, artist: String, album: String, durationMs: Long): String =
        listOf(normalize(title), normalize(artist), normalize(album), (durationMs / 1000).toString())
            .joinToString("|")

    /** Identity of an album: normalised title + album artist. */
    fun albumKey(album: String, albumArtist: String, folderHint: String?): String {
        val title = normalize(album)
        return if (title.isEmpty()) {
            // Untitled albums are separated per folder so unrelated loose files are not merged.
            "\u0000unknown|${normalize(albumArtist)}|${normalize(folderHint)}"
        } else {
            "$title|${normalize(albumArtist)}"
        }
    }

    /** Escapes `%`, `_` and `\` for use in a SQL `LIKE ... ESCAPE '\'` pattern. */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Sort key that ignores case and a leading English article. */
    fun sortKey(value: String): String {
        val n = normalize(value)
        return when {
            n.startsWith("the ") -> n.removePrefix("the ")
            n.startsWith("an ") -> n.removePrefix("an ")
            n.startsWith("a ") -> n.removePrefix("a ")
            else -> n
        }
    }
}
