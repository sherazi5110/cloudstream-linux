package io.github.sherazi5110.cloudstream.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.R

/** The Linux app's dark look (themes and accent colours come with Settings in phase 2). */
object Palette {
    val background = Color(0xFF0C0C10)
    val surface = Color(0xFF17171C)
    val surfaceHigh = Color(0xFF25252C)
    val outline = Color(0xFF34343C)
    val accent = Color(0xFF3B82F6)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFFA1A1AA)
    val tmdb = Color(0xFF01B4E4)
    val imdb = Color(0xFFF5C518)
    val watched = Color(0xFF4ADE80)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val InterDisplay = FontFamily(Font(R.font.inter_display_extrabold, FontWeight.ExtraBold))

private val base = TextStyle(fontFamily = Inter, color = Palette.text)

private val typography = Typography(
    displayLarge = base.copy(fontFamily = InterDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    displayMedium = base.copy(fontFamily = InterDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp),
    headlineSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Palette.muted),
    bodySmall = base.copy(fontSize = 11.sp, color = Palette.muted),
    labelLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
)

@Composable
fun CloudStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerHighest = Palette.surfaceHigh,
            outline = Palette.outline,
            secondaryContainer = Palette.accent.copy(alpha = 0.25f),
            onSecondaryContainer = Palette.text,
        ),
        typography = typography,
        content = content,
    )
}
