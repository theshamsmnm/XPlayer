package com.xplayer.app.backup

import com.xplayer.app.data.backup.BackupArchive
import com.xplayer.app.data.backup.BackupArtwork
import com.xplayer.app.data.backup.BackupCollection
import com.xplayer.app.data.backup.BackupCollectionTrack
import com.xplayer.app.data.backup.BackupException
import com.xplayer.app.data.backup.BackupManifest
import com.xplayer.app.data.backup.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    private fun manifest(
        version: Int = BackupManifest.CURRENT_VERSION,
        format: String = BackupManifest.FORMAT,
        collections: List<BackupCollection> = emptyList(),
        artwork: List<BackupArtwork> = emptyList(),
        settings: BackupSettings = BackupSettings("AMOLED", true, true, false, 0),
    ) = BackupManifest(format = format, version = version, createdAt = 1L, appVersion = "t", settings = settings, collections = collections, artwork = artwork)

    private fun zip(manifest: BackupManifest?, extra: Map<String, ByteArray> = emptyMap(), rawManifest: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val text = rawManifest ?: manifest?.let { BackupArchive.json.encodeToString(BackupManifest.serializer(), it) }
            if (text != null) { z.putNextEntry(ZipEntry(BackupManifest.MANIFEST_ENTRY)); z.write(text.toByteArray()); z.closeEntry() }
            extra.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun kind(block: () -> Unit): BackupException.Kind {
        try { block() } catch (e: BackupException) { return e.kind }
        fail("expected BackupException"); throw IllegalStateException()
    }

    @Test fun roundTrip() {
        val m = manifest(collections = listOf(BackupCollection(7, "Gym", 5, 0, listOf(BackupCollectionTrack("a|b|c|1", 0, 9)))))
        val parsed = BackupArchive.parse(ByteArrayInputStream(zip(m)))
        assertEquals(m, parsed.manifest)
    }

    @Test fun artworkEntriesAreLoaded() {
        val m = manifest(artwork = listOf(BackupArtwork("ALBUM", "k", "art_0.jpg")))
        val parsed = BackupArchive.parse(ByteArrayInputStream(zip(m, mapOf("artwork/art_0.jpg" to byteArrayOf(1, 2, 3)))))
        assertEquals(3, parsed.artwork.getValue("art_0.jpg").size)
    }

    @Test fun missingArtworkFileIsCorrupt() {
        val m = manifest(artwork = listOf(BackupArtwork("ALBUM", "k", "art_0.jpg")))
        assertEquals(BackupException.Kind.CORRUPT, kind { BackupArchive.parse(ByteArrayInputStream(zip(m))) })
    }

    @Test fun notAZipIsRejected() {
        assertEquals(BackupException.Kind.NOT_A_BACKUP, kind { BackupArchive.parse(ByteArrayInputStream("hello".toByteArray())) })
    }

    @Test fun zipWithoutManifestIsRejected() {
        assertEquals(BackupException.Kind.NOT_A_BACKUP, kind { BackupArchive.parse(ByteArrayInputStream(zip(null, mapOf("x.txt" to byteArrayOf(1))))) })
    }

    @Test fun wrongFormatTag() {
        assertEquals(BackupException.Kind.NOT_A_BACKUP, kind { BackupArchive.parse(ByteArrayInputStream(zip(manifest(format = "other")))) })
    }

    @Test fun newerVersionIsRefused() {
        assertEquals(BackupException.Kind.TOO_NEW, kind { BackupArchive.parse(ByteArrayInputStream(zip(manifest(version = 99)))) })
    }

    @Test fun versionZeroIsTooOld() {
        assertEquals(BackupException.Kind.TOO_OLD, kind { BackupArchive.parse(ByteArrayInputStream(zip(manifest(version = 0)))) })
    }

    @Test fun truncatedJsonIsCorrupt() {
        assertEquals(BackupException.Kind.CORRUPT, kind { BackupArchive.parse(ByteArrayInputStream(zip(null, rawManifest = "{\"format\":\"xplayer-backup\","))) })
    }

    @Test fun duplicateCollectionIdsAreCorrupt() {
        val c = BackupCollection(1, "a", 0, 0)
        assertEquals(BackupException.Kind.CORRUPT, kind { BackupArchive.parse(ByteArrayInputStream(zip(manifest(collections = listOf(c, c))))) })
    }

    @Test fun invalidSettingsAreCorrupt() {
        assertEquals(BackupException.Kind.CORRUPT, kind { BackupArchive.parse(ByteArrayInputStream(zip(manifest(settings = BackupSettings("NEON", true, true, false, 0))))) })
        assertEquals(BackupException.Kind.CORRUPT, kind { BackupArchive.parse(ByteArrayInputStream(zip(manifest(settings = BackupSettings("DARK", true, true, false, 9))))) })
    }

    @Test fun traversalEntriesAreIgnored() {
        val m = manifest()
        val parsed = BackupArchive.parse(ByteArrayInputStream(zip(m, mapOf("artwork/../../evil.jpg" to byteArrayOf(1), "../evil" to byteArrayOf(1)))))
        assertTrue(parsed.artwork.isEmpty())
    }

    @Test fun unknownFieldsFromNewerMinorVersionsAreTolerated() {
        val text = BackupArchive.json.encodeToString(BackupManifest.serializer(), manifest()).dropLast(1) + ",\"futureField\":1}"
        BackupArchive.parse(ByteArrayInputStream(zip(null, rawManifest = text)))
    }
}
