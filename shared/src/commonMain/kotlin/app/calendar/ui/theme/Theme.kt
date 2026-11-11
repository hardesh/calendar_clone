package app.calendar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Notion Calendar–style palette. */
@Immutable
data class AppColors(
    val dark: Boolean,
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val hover: Color,
    val selected: Color,
    val gridLine: Color,
    val gridLineStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val today: Color,
    val border: Color,
    val weekendShade: Color,
    val scrim: Color,
    val toast: Color,
    val onToast: Color,
)

val LightColors = AppColors(
    dark = false,
    background = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF9F9F8),
    surface = Color(0xFFFFFFFF),
    hover = Color(0x0F37352F),
    selected = Color(0x1A37352F),
    gridLine = Color(0xFFEDEDEC),
    gridLineStrong = Color(0xFFE3E2E0),
    text = Color(0xFF37352F),
    textSecondary = Color(0xFF787774),
    textTertiary = Color(0xFFA5A29A),
    accent = Color(0xFF2383E2),
    today = Color(0xFFEB5757),
    border = Color(0xFFE9E9E7),
    weekendShade = Color(0x05000000),
    scrim = Color(0x66000000),
    toast = Color(0xFF2F2F2F),
    onToast = Color(0xFFF1F1EF),
)

val DarkColors = AppColors(
    dark = true,
    background = Color(0xFF191919),
    sidebar = Color(0xFF202020),
    surface = Color(0xFF252525),
    hover = Color(0x14FFFFFF),
    selected = Color(0x22FFFFFF),
    gridLine = Color(0xFF2A2A2A),
    gridLineStrong = Color(0xFF333333),
    text = Color(0xFFE6E6E5),
    textSecondary = Color(0xFF9B9B9B),
    textTertiary = Color(0xFF6B6B6B),
    accent = Color(0xFF529CCA),
    today = Color(0xFFEB5757),
    border = Color(0xFF2F2F2F),
    weekendShade = Color(0x06FFFFFF),
    scrim = Color(0x99000000),
    toast = Color(0xFF3A3A3A),
    onToast = Color(0xFFF1F1EF),
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

object AppType {
    val tiny = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
    val small = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val bodyMedium = body.copy(fontWeight = FontWeight.Medium)
    val title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val eventTitle = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
    val eventTime = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
}

/** Colours for an event block derived from its calendar colour, like Notion's tinted blocks. */
@Immutable
data class EventPalette(val fill: Color, val bar: Color, val text: Color, val subText: Color)

fun eventPalette(argb: Long, colors: AppColors, faded: Boolean = false): EventPalette {
    val base = Color(argb.toInt())
    val fill = base.copy(alpha = if (colors.dark) 0.30f else 0.16f).compositeOver(colors.background)
    val text = if (colors.dark) lerp(base, Color.White, 0.70f) else lerp(base, Color.Black, 0.55f)
    val sub = text.copy(alpha = 0.75f)
    return if (faded) {
        EventPalette(lerp(fill, colors.background, 0.45f), base.copy(alpha = 0.5f), text.copy(alpha = 0.55f), sub.copy(alpha = 0.45f))
    } else {
        EventPalette(fill, base, text, sub)
    }
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, background = colors.background, surface = colors.surface,
            onSurface = colors.text, onBackground = colors.text, surfaceContainerHigh = colors.surface,
            surfaceContainer = colors.surface, outline = colors.border, error = colors.today,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, background = colors.background, surface = colors.surface,
            onSurface = colors.text, onBackground = colors.text, surfaceContainerHigh = colors.surface,
            surfaceContainer = colors.surface, outline = colors.border, error = colors.today,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
