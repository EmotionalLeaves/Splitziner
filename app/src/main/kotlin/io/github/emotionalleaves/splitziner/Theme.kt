package io.github.emotionalleaves.splitziner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look: drafting-office blue for actions and selection, a warm amber for things that need attention,
 * quiet neutral surfaces so drawings and the 3D model stand out. Fixed colours rather than the wallpaper's, so
 * screenshots and printed guides look the same on every phone.
 */
private val Blueprint = Color(0xFF1F4E79)
private val BlueprintLight = Color(0xFF9CC7F2)
private val Amber = Color(0xFF8A5100)
private val AmberLight = Color(0xFFFFB86B)

private val Light = lightColorScheme(
    primary = Blueprint, onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F8), onPrimaryContainer = Color(0xFF0A2540),
    secondary = Color(0xFF4F5D6E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3EC), onSecondaryContainer = Color(0xFF16202B),
    tertiary = Amber, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB8), onTertiaryContainer = Color(0xFF2C1700),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF7F8FA), onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE1E4E9), onSurfaceVariant = Color(0xFF44484F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F6),
    surfaceContainer = Color(0xFFEBEEF2), surfaceContainerHigh = Color(0xFFE5E8ED), surfaceContainerHighest = Color(0xFFDFE3E8),
    outline = Color(0xFF747880), outlineVariant = Color(0xFFC4C7CE),
)

private val Dark = darkColorScheme(
    primary = BlueprintLight, onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF1B4469), onPrimaryContainer = Color(0xFFD3E4F8),
    secondary = Color(0xFFB9C7D8), onSecondary = Color(0xFF233140),
    secondaryContainer = Color(0xFF3A4756), onSecondaryContainer = Color(0xFFDCE3EC),
    tertiary = AmberLight, onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3C00), onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF111316), onBackground = Color(0xFFE2E4E8),
    surface = Color(0xFF111316), onSurface = Color(0xFFE2E4E8),
    surfaceVariant = Color(0xFF41464E), onSurfaceVariant = Color(0xFFC4C7CE),
    surfaceContainerLowest = Color(0xFF0C0E10), surfaceContainerLow = Color(0xFF191B1E),
    surfaceContainer = Color(0xFF1D2023), surfaceContainerHigh = Color(0xFF272A2E), surfaceContainerHighest = Color(0xFF323539),
    outline = Color(0xFF8E9299), outlineVariant = Color(0xFF41464E),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun SplitzinerTheme(content: @Composable () -> Unit) {
    val base = Typography()
    // Titles a touch heavier and tighter, as in technical documents; body text untouched for reading.
    val type = base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = type, shapes = AppShapes, content = content)
}
