package com.xplayer.app.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import com.xplayer.app.core.AppDispatchers
import com.xplayer.app.data.db.CustomArtworkEntity
import com.xplayer.app.data.db.XPlayerDatabase
import com.xplayer.app.domain.model.ArtKind
import com.xplayer.app.domain.model.ArtworkRef
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Custom + embedded artwork.
 *
 * Custom images are stored as *files* in app-private storage (a 1024px JPEG plus a 256px thumbnail);
 * Room keeps only their paths and a monotonically increasing `version` that feeds the image-cache key,
 * so replacing or restoring artwork invalidates every cache layer (grid, mini player, notification).
 */
class ArtworkStore(
    private val context: Context,
    private val db: XPlayerDatabase,
    private val dispatchers: AppDispatchers,
) {
    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }

    /** Decodes an image the user picked, optionally cropped to [crop] (fractions of the source). */
    suspend fun saveCustom(
        kind: ArtKind,
        ownerId: Long,
        source: Uri,
        crop: CropRect? = null,
    ): Boolean = withContext(dispatchers.io) {
        val bitmap = decodeSampled(source, FULL_SIZE) ?: return@withContext false
        val squared = cropToSquare(bitmap, crop)
        val full = Bitmap.createScaledBitmap(squared, minOf(FULL_SIZE, squared.width), minOf(FULL_SIZE, squared.height), true)
        val thumb = Bitmap.createScaledBitmap(squared, THUMB_SIZE, THUMB_SIZE, true)

        val version = System.currentTimeMillis()
        val base = "${kind.name.lowercase()}_${ownerId}_$version"
        val fullFile = File(dir, "$base.jpg")
        val thumbFile = File(dir, "${base}_t.jpg")
        try {
            FileOutputStream(fullFile).use { full.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            FileOutputStream(thumbFile).use { thumb.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } catch (e: Exception) {
            fullFile.delete(); thumbFile.delete()
            return@withContext false
        } finally {
            if (squared !== bitmap) squared.recycle()
            bitmap.recycle()
        }

        val old = db.artworkDao().get(kind.name, ownerId)
        db.artworkDao().upsert(
            CustomArtworkEntity(kind.name, ownerId, fullFile.absolutePath, thumbFile.absolutePath, version, version),
        )
        old?.let { File(it.fullPath).delete(); File(it.thumbPath).delete() }
        true
    }

    /**
     * Writes the files for artwork restored from a backup and returns the row to insert (the caller
     * inserts it inside its own transaction). Returns null if [bytes] is not a decodable image.
     */
    suspend fun prepareFromBytes(kind: ArtKind, ownerId: Long, bytes: ByteArray): CustomArtworkEntity? =
        withContext(dispatchers.io) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, FULL_SIZE) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            val thumb = Bitmap.createScaledBitmap(bmp, THUMB_SIZE, THUMB_SIZE, true)
            val version = System.currentTimeMillis()
            val base = "${kind.name.lowercase()}_${ownerId}_$version"
            val fullFile = File(dir, "$base.jpg")
            val thumbFile = File(dir, "${base}_t.jpg")
            try {
                FileOutputStream(fullFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                FileOutputStream(thumbFile).use { thumb.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            } catch (e: Exception) {
                fullFile.delete(); thumbFile.delete()
                return@withContext null
            } finally {
                bmp.recycle()
            }
            CustomArtworkEntity(kind.name, ownerId, fullFile.absolutePath, thumbFile.absolutePath, version, version)
        }

    /** Deletes the image files of rows that were replaced or rolled back. */
    fun deleteFiles(rows: List<CustomArtworkEntity>) {
        rows.forEach { File(it.fullPath).delete(); File(it.thumbPath).delete() }
    }

    /** Decodes a down-sampled preview (<= ~1024px) of a user-picked image for the crop screen. */
    suspend fun decodePreview(source: Uri): Bitmap? = withContext(dispatchers.io) { decodeSampled(source, FULL_SIZE) }

    /** Removes custom artwork so the original embedded artwork (or fallback) shows again. */
    suspend fun restoreOriginal(kind: ArtKind, ownerId: Long) = withContext(dispatchers.io) {
        val old = db.artworkDao().get(kind.name, ownerId) ?: return@withContext
        db.artworkDao().delete(kind.name, ownerId)
        File(old.fullPath).delete()
        File(old.thumbPath).delete()
    }

    suspend fun hasCustom(kind: ArtKind, ownerId: Long): Boolean =
        db.artworkDao().get(kind.name, ownerId) != null

    /**
     * Loads artwork of [ref] scaled to about [sizePx]. Order: custom (track -> album) then embedded.
     * Returns null when nothing is available (the UI then draws the generated fallback).
     */
    suspend fun load(ref: ArtworkRef, sizePx: Int): Bitmap? = withContext(dispatchers.io) {
        val useThumb = sizePx <= THUMB_SIZE
        customFile(ref, useThumb)?.let { file ->
            decodeFile(file, sizePx)?.let { return@withContext it }
        }
        embedded(ref, sizePx)
    }

    /** A bitmap suitable for notifications / MediaSession (never null: falls back to a generated cover). */
    suspend fun loadOrFallback(ref: ArtworkRef, sizePx: Int): Bitmap =
        load(ref, sizePx) ?: fallbackBitmap(ref.label, sizePx)

    private suspend fun customFile(ref: ArtworkRef, thumb: Boolean): File? {
        val dao = db.artworkDao()
        val candidates = when (ref.kind) {
            ArtKind.TRACK -> {
                val albumId = db.trackDao().getById(ref.id)?.albumId
                listOfNotNull(
                    dao.get(ArtKind.TRACK.name, ref.id),
                    albumId?.let { dao.get(ArtKind.ALBUM.name, it) },
                )
            }
            else -> listOfNotNull(dao.get(ref.kind.name, ref.id))
        }
        val row = candidates.firstOrNull() ?: return null
        val file = File(if (thumb) row.thumbPath else row.fullPath)
        // Never silently drop a reference: if the file is gone we simply fall through to embedded art.
        return file.takeIf { it.exists() }
    }

    private suspend fun embedded(ref: ArtworkRef, sizePx: Int): Bitmap? {
        val uri = when (ref.kind) {
            ArtKind.TRACK -> db.trackDao().getById(ref.id)?.uri
            ArtKind.ALBUM -> firstTrackUri(ref.id)
            ArtKind.COLLECTION -> collectionCoverUri(ref.id)
        } ?: return null
        return loadEmbedded(Uri.parse(uri), sizePx)
    }

    private suspend fun firstTrackUri(albumId: Long): String? =
        db.trackDao().firstUriOfAlbum(albumId)

    private suspend fun collectionCoverUri(collectionId: Long): String? {
        val ids = db.collectionDao().trackIds(collectionId)
        for (id in ids.take(5)) {
            val t = db.trackDao().getById(id) ?: continue
            if (t.isAvailable) return t.uri
        }
        return null
    }

    private fun loadEmbedded(uri: Uri, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } catch (_: Exception) {
                // fall through to the retriever
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val sample = sampleSize(opts.outWidth, opts.outHeight, sizePx)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun decodeFile(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx) }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeSampled(uri: Uri, maxSize: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize) }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    private fun cropToSquare(src: Bitmap, crop: CropRect?): Bitmap {
        val rect = if (crop != null) {
            val x = (crop.left * src.width).toInt().coerceIn(0, src.width - 1)
            val y = (crop.top * src.height).toInt().coerceIn(0, src.height - 1)
            val s = (crop.size * minOf(src.width, src.height)).toInt().coerceAtLeast(1)
            Rect(x, y, minOf(src.width, x + s), minOf(src.height, y + s))
        } else {
            val s = minOf(src.width, src.height)
            Rect((src.width - s) / 2, (src.height - s) / 2, (src.width + s) / 2, (src.height + s) / 2)
        }
        val side = minOf(rect.width(), rect.height())
        return Bitmap.createBitmap(src, rect.left, rect.top, side, side)
    }

    companion object {
        const val FULL_SIZE = 1024
        const val THUMB_SIZE = 256

        fun sampleSize(w: Int, h: Int, target: Int): Int {
            var sample = 1
            if (w <= 0 || h <= 0) return 1
            while (w / (sample * 2) >= target && h / (sample * 2) >= target) sample *= 2
            return sample
        }

        /** Deterministic dark-gray cover with the label's initial; used when no artwork exists. */
        fun fallbackBitmap(label: String, sizePx: Int): Bitmap {
            val size = sizePx.coerceIn(64, 1024)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val shade = 24 + (label.hashCode().mod(5)) * 6
            canvas.drawColor(Color.rgb(shade, shade, shade))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 255, 255, 255)
                textSize = size * 0.42f
                textAlign = Paint.Align.CENTER
            }
            val initial = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪"
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(initial, size / 2f, y, paint)
            return bmp
        }
    }
}

/** Crop square in fractions: [left], [top] of the source, [size] as a fraction of the shorter side. */
data class CropRect(val left: Float, val top: Float, val size: Float)
