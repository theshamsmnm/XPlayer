package com.xplayer.app.ui.art

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xplayer.app.domain.model.ArtworkRef
import com.xplayer.app.ui.theme.ArtworkShape

/**
 * Square artwork with a generated fallback (dark tile + the label's initial) that is shown while loading
 * and when no artwork exists. [sizePx] bounds the decoded bitmap, so lists never decode full images.
 */
@Composable
fun ArtworkImage(
    art: ArtworkRef?,
    modifier: Modifier = Modifier,
    sizePx: Int = 400,
    shape: RoundedCornerShape = ArtworkShape,
    fallbackTextSize: TextUnit = 28.sp,
) {
    val context = LocalContext.current
    var failed by remember(art) { mutableStateOf(false) }
    val shade = remember(art?.label) { 0.10f + ((art?.label?.hashCode() ?: 0).mod(5)) * 0.02f }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Color(shade, shade, shade)),
        contentAlignment = Alignment.Center,
    ) {
        if (art == null || failed) {
            Text(
                text = art?.label?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = fallbackTextSize,
                fontWeight = FontWeight.Light,
            )
        } else {
            val request = remember(art, sizePx) {
                ImageRequest.Builder(context)
                    .data(art)
                    .size(sizePx)
                    .crossfade(true)
                    .listener(onError = { _, _ -> failed = true })
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
