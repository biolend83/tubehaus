package app.tubehaus.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The look of the TubeHaus web app 1.0 (web/y/app.css, "Flow look"): one calm background, soft grey
// boxes instead of lines, big round corners, pill buttons and tabs, a soft teal for "you are here",
// bolder titles, and the Pretendard font.
val Red = Color(0xFFFF0033)
val Blue = Color(0xFF065FD4)
/** the TubeHaus teal (the house in the logo); on a TV it marks what the remote has selected */
val Teal = Color(0xFF2DD4BF)

/** the teal of "you are here" and of switches that are on: deeper on a light page, as on the web (--acc) */
val accent: Color
    @Composable @ReadOnlyComposable get() =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Teal else Color(0xFF0D9488)

/** the soft teal behind the open tab and the chosen section (--acc-soft) */
val accentSoft: Color
    @Composable @ReadOnlyComposable get() =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Teal.copy(alpha = 0.18f) else Color(0xFF14B8A6).copy(alpha = 0.16f)

// surfaceVariant: chips, pills, picture placeholders (--chip); surfaceContainer: the boxes that hold
// rows, settings and descriptions (--box); surfaceContainerHigh: what sits on a box, such as the circle
// behind a row's icon (--box2); secondaryContainer: the pill behind the open tab (--acc-soft)
private val light = lightColorScheme(
    primary = Color(0xFF0F0F0F), onPrimary = Color.White,
    secondary = Blue, onSecondary = Color.White,
    secondaryContainer = Color(0x2914B8A6), onSecondaryContainer = Color(0xFF0F0F0F),
    background = Color.White, onBackground = Color(0xFF0F0F0F),
    surface = Color.White, onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFEEF0F2), onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFF2F3F5), surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE1E4E8),
    outline = Color(0xFFE6E8EB), outlineVariant = Color(0xFFE6E8EB), error = Color(0xFFCC0000),
)

private val dark = darkColorScheme(
    primary = Color(0xFFF1F1F1), onPrimary = Color(0xFF0F0F0F),
    secondary = Color(0xFF3EA6FF), onSecondary = Color(0xFF0F0F0F),
    secondaryContainer = Color(0x2E2DD4BF), onSecondaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF0F0F0F), onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF0F0F0F), onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF232428), onSurfaceVariant = Color(0xFFA7ABB2),
    surfaceContainerLowest = Color(0xFF0F0F0F), surfaceContainerLow = Color(0xFF16171A),
    surfaceContainer = Color(0xFF1C1D20), surfaceContainerHigh = Color(0xFF2B2D31), surfaceContainerHighest = Color(0xFF303237),
    outline = Color(0xFF2A2C30), outlineVariant = Color(0xFF2A2C30), error = Color(0xFFFF4E45),
)

/** dark with pure black behind everything: the pixels of an AMOLED screen switch off */
private val black by lazy {
    dark.copy(
        background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0C),
        surfaceVariant = Color(0xFF1C1D20), surfaceContainer = Color(0xFF141517), surfaceContainerHigh = Color(0xFF232428),
    )
}

/** the web's type: bold titles drawn a touch tighter, quiet details */
private fun type(f: FontFamily?): Typography {
    val d = Typography()
    fun TextStyle.f() = if (f != null) copy(fontFamily = f) else this
    return Typography(
        displayLarge = d.displayLarge.f(), displayMedium = d.displayMedium.f(), displaySmall = d.displaySmall.f(),
        headlineLarge = d.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp).f(),
        headlineMedium = d.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp).f(),
        headlineSmall = d.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp).f(),
        titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp, letterSpacing = (-0.4).sp).f(),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, letterSpacing = (-0.1).sp).f(),
        titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, letterSpacing = (-0.1).sp).f(),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp).f(),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp).f(),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp).f(),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold).f(),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp).f(),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp).f(),
    )
}

/** rounder everything: menus 16, cards 18, sheets and dialogs 24 */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * How things move (the Material 3 motion tokens): short for presses and small things, medium for
 * pages, long for big surfaces. Pages arrive on the emphasized curve: quick out, a soft landing.
 */
object Motion {
    const val SHORT = 150
    const val MEDIUM = 250
    const val LONG = 350
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedIn = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedOut = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** the web's --e-out: things that arrive (pages, cards) */
    val out = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

@Composable
fun TubeTheme(mode: Int, wallpaper: ((Boolean) -> ColorScheme?)? = null, font: FontFamily? = null, content: @Composable () -> Unit) {
    // 0 follow the system, 1 light, 2 dark, 3 black (pure black for AMOLED screens),
    // 4 the wallpaper's colours (Material You, Android 12+), light or dark with the system
    val darkOn = when (mode) { 1 -> false; 2, 3 -> true; else -> isSystemInDarkTheme() }
    val scheme = when {
        mode == 3 -> black
        mode == 4 -> wallpaper?.invoke(darkOn) ?: if (darkOn) dark else light
        darkOn -> dark
        else -> light
    }
    val typography = androidx.compose.runtime.remember(font) { type(font) }
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
