package app.photos

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens. The interface stays photo-first: true black, neutral charcoal, one quiet accent for status. */
object Palette {
    val Black = Color(0xFF000000)
    val Bar = Color(0xFF0B0B0B)
    val Surface = Color(0xFF121212)
    val Card = Color(0xFF181818)
    val CardHigh = Color(0xFF222222)
    val Line = Color(0xFF2A2A2A)
    val Text = Color(0xFFF5F5F5)
    val Muted = Color(0xFFA3A3A3)
    val Faint = Color(0xFF6E6E6E)
    val Placeholder = Color(0xFF161616)
    val Verified = Color(0xFF6FD08C)
    val Warning = Color(0xFFF0B856)
    val Danger = Color(0xFFFF8A80)
}

private val colors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Palette.CardHigh, onPrimaryContainer = Color.White,
    secondary = Color(0xFFE5E5E5), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color.White,
    tertiary = Palette.Verified, onTertiary = Color.Black,
    background = Palette.Black, onBackground = Palette.Text,
    surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.CardHigh, onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Black, surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Card, surfaceContainerHigh = Palette.CardHigh,
    surfaceContainerHighest = Color(0xFF2C2C2C),
    outline = Color(0xFF4A4A4A), outlineVariant = Palette.Line, surfaceTint = Color.Transparent,
    error = Palette.Danger, onError = Color.Black,
    inverseSurface = Color(0xFFEDEDED), inverseOnSurface = Color.Black,
)

private val base = Typography()
private val type = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PhotoSTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, shapes = shapes) {
    // Full-screen dialogs draw on plain black without a Surface; without this their text defaulted to black-on-black.
    CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
}
