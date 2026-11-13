package com.xplayer.app.feature.artwork

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xplayer.app.R
import com.xplayer.app.appContainer
import com.xplayer.app.data.artwork.CropRect
import com.xplayer.app.domain.model.ArtKind
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Artwork replacement flow: system photo picker -> preview with pan/zoom square crop -> confirm/cancel.
 * Also offers "restore original" when custom artwork exists. Pure UI over [com.xplayer.app.data.artwork.ArtworkStore].
 *
 * @param onFinished called after confirm, restore or cancel (with true when something changed).
 */
@Composable
fun ArtworkEditorDialog(kind: ArtKind, ownerId: Long, onFinished: (changed: Boolean) -> Unit) {
    val context = LocalContext.current
    val store = context.appContainer.artwork
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var hasCustom by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(CropRect(0f, 0f, 1f)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) onFinished(false) else pickedUri = uri
    }

    LaunchedEffect(Unit) {
        hasCustom = store.hasCustom(kind, ownerId)
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        val bmp = store.decodePreview(uri)
        if (bmp == null) failed = true else preview = bmp
    }

    val bmp = preview
    if (bmp == null && !failed) return // picker is on screen

    AlertDialog(
        onDismissRequest = { onFinished(false) },
        title = { Text(stringResource(R.string.change_artwork)) },
        text = {
            Column {
                if (bmp != null) {
                    CropArea(bmp, onCropChanged = { crop = it })
                    Text(
                        stringResource(R.string.crop_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.artwork_load_failed))
                }
                if (hasCustom) {
                    TextButton(onClick = {
                        scope.launch { store.restoreOriginal(kind, ownerId); onFinished(true) }
                    }) { Text(stringResource(R.string.restore_original_artwork)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bmp != null,
                onClick = {
                    val uri = pickedUri ?: return@TextButton
                    scope.launch {
                        val ok = store.saveCustom(kind, ownerId, uri, crop)
                        onFinished(ok)
                    }
                },
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = { onFinished(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Square viewport over the image. The user pans and pinch-zooms; the visible square is reported as a
 * [CropRect] in the fractions the store expects (left/top of the source, size relative to its shorter side).
 */
@Composable
private fun CropArea(bitmap: Bitmap, onCropChanged: (CropRect) -> Unit) {
    val density = LocalDensity.current
    val viewport = 260.dp
    val viewportPx = with(density) { viewport.toPx() }
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val baseScale = viewportPx / min(w, h) // fits the shorter side exactly
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) } // top-left of the image relative to the viewport

    fun clamp(o: Offset, z: Float): Offset {
        val k = baseScale * z
        val minX = viewportPx - w * k
        val minY = viewportPx - h * k
        return Offset(o.x.coerceIn(min(minX, 0f), 0f), o.y.coerceIn(min(minY, 0f), 0f))
    }

    fun report() {
        val k = baseScale * zoom
        onCropChanged(CropRect(left = -offset.x / k / w, top = -offset.y / k / h, size = (viewportPx / k) / min(w, h)))
    }

    LaunchedEffect(bitmap) {
        // Start centered.
        val k = baseScale
        offset = clamp(Offset((viewportPx - w * k) / 2f, (viewportPx - h * k) / 2f), 1f)
        report()
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        Modifier
            .size(viewport)
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                    val oldK = baseScale * zoom
                    val newK = baseScale * newZoom
                    // Keep the point under the fingers fixed while zooming.
                    val imagePoint = (centroid - offset) / oldK
                    val zoomed = centroid - imagePoint * newK
                    zoom = newZoom
                    offset = clamp(zoomed + pan, newZoom)
                    report()
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.None,
            alignment = Alignment.TopStart,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    scaleX = baseScale * zoom
                    scaleY = baseScale * zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
    }
}
