package com.xplayer.app.data.backup

import com.xplayer.app.domain.model.ThemeMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A backup parsed into memory (nothing has touched the app's data yet). */
class ParsedBackup(val manifest: BackupManifest, val artwork: Map<String, ByteArray>)

/**
 * Pure (Android-free, unit-tested) reading and validation of backup archives: bounded reads, entry name
 * allow-list (no path traversal), format/version/consistency checks.
 */
object BackupArchive {
    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Blocking; call from an IO dispatcher. Throws [BackupException]. */
    fun parse(input: InputStream): ParsedBackup {
        var manifestText: String? = null
        val images = HashMap<String, ByteArray>()
        try {
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val isManifest = name == BackupManifest.MANIFEST_ENTRY
                    val isArt = name.startsWith(BackupManifest.ARTWORK_DIR) &&
                        name.length > BackupManifest.ARTWORK_DIR.length &&
                        !name.substring(BackupManifest.ARTWORK_DIR.length).contains('/') &&
                        !name.contains("..")
                    if (entry.isDirectory || (!isManifest && !isArt)) continue
                    val bytes = readLimited(zip, if (isManifest) MAX_MANIFEST_BYTES else MAX_IMAGE_BYTES)
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw BackupException(BackupException.Kind.CORRUPT, "too large")
                    if (isManifest) manifestText = String(bytes, Charsets.UTF_8)
                    else images[name.removePrefix(BackupManifest.ARTWORK_DIR)] = bytes
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            throw BackupException(BackupException.Kind.NOT_A_BACKUP, e.message)
        }
        val text = manifestText ?: throw BackupException(BackupException.Kind.NOT_A_BACKUP, "no manifest")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException(BackupException.Kind.CORRUPT, e.message)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupException.Kind.CORRUPT, e.message)
        }
        validate(manifest, images)
        return ParsedBackup(manifest, images)
    }

    /** Compatibility + consistency checks. */
    fun validate(manifest: BackupManifest, images: Map<String, ByteArray>) {
        if (manifest.format != BackupManifest.FORMAT) throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        if (manifest.version > BackupManifest.CURRENT_VERSION) throw BackupException(BackupException.Kind.TOO_NEW)
        if (manifest.version < 1) throw BackupException(BackupException.Kind.TOO_OLD)
        if (manifest.artwork.any { it.file !in images }) {
            throw BackupException(BackupException.Kind.CORRUPT, "missing artwork file")
        }
        if (manifest.collections.map { it.originalId }.toSet().size != manifest.collections.size) {
            throw BackupException(BackupException.Kind.CORRUPT, "duplicate collection ids")
        }
        if (manifest.settings.repeatMode !in 0..2) throw BackupException(BackupException.Kind.CORRUPT, "repeat mode")
        if (ThemeMode.entries.none { it.name == manifest.settings.theme }) {
            throw BackupException(BackupException.Kind.CORRUPT, "theme")
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw BackupException(BackupException.Kind.CORRUPT, "entry too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
