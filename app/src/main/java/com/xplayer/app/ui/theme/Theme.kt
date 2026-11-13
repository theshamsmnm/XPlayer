package com.xplayer.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xplayer.app.domain.model.ThemeMode

private val AmoledScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFB5B5B5),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
    error = Color(0xFFFF8A80),
)

private val DarkScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF121212),
    secondary = Color(0xFFBDBDBD),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFA5A5A5),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF2C2C2C),
    error = Color(0xFFFF8A80),
)

private val LightScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color(0xFF555555),
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainer = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFB3261E),
)

/** Very small corner radii everywhere; artwork uses [ArtworkShape]. */
private val XShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
)

val ArtworkShape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

private val XTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** True when the active scheme is dark (used for system-bar icon colours). */
val LocalIsDark = staticCompositionLocalOf { true }

@Composable
fun XPlayerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val scheme: ColorScheme = when (mode) {
        ThemeMode.AMOLED -> AmoledScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.LIGHT -> LightScheme
    }
    CompositionLocalProvider(LocalIsDark provides (mode != ThemeMode.LIGHT)) {
        MaterialTheme(colorScheme = scheme, shapes = XShapes, typography = XTypography, content = content)
    }
}
