package app.tubehaus.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The classic, bright look of the TubeHaus web app: white pages, black titles, grey details,
// blue links and the red logo.
val Red = Color(0xFFFF0033)
val Blue = Color(0xFF065FD4)
/** the TubeHaus teal (the house in the logo); on a TV it marks what the remote has selected */
val Teal = Color(0xFF2DD4BF)

private val light = lightColorScheme(
    primary = Color(0xFF0F0F0F), onPrimary = Color.White,
    secondary = Blue, onSecondary = Color.White,
    background = Color.White, onBackground = Color(0xFF0F0F0F),
    surface = Color.White, onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF606060),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFE8E8E8),
    outline = Color(0xFFE5E5E5), error = Color(0xFFCC0000),
)

/** dark with pure black behind everything: the pixels of an AMOLED screen switch off */
private val black by lazy {
    dark.copy(
        background = Color.Black, surface = Color.Black,
        surfaceVariant = Color(0xFF1A1A1A), surfaceContainer = Color(0xFF121212), surfaceContainerHigh = Color(0xFF1E1E1E),
    )
}

private val dark = darkColorScheme(
    primary = Color(0xFFF1F1F1), onPrimary = Color(0xFF0F0F0F),
    secondary = Color(0xFF3EA6FF), onSecondary = Color(0xFF0F0F0F),
    background = Color(0xFF0F0F0F), onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF0F0F0F), onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF272727), onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceContainer = Color(0xFF212121), surfaceContainerHigh = Color(0xFF303030),
    outline = Color(0xFF303030), error = Color(0xFFFF4E45),
)

private val type = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TubeTheme(mode: Int, wallpaper: ((Boolean) -> ColorScheme?)? = null, content: @Composable () -> Unit) {
    // 0 follow the system, 1 light, 2 dark, 3 black (pure black for AMOLED screens),
    // 4 the wallpaper's colours (Material You, Android 12+), light or dark with the system
    val darkOn = when (mode) { 1 -> false; 2, 3 -> true; else -> isSystemInDarkTheme() }
    val scheme = when {
        mode == 3 -> black
        mode == 4 -> wallpaper?.invoke(darkOn) ?: if (darkOn) dark else light
        darkOn -> dark
        else -> light
    }
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
