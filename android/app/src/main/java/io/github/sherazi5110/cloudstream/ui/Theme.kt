package io.github.sherazi5110.cloudstream.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.R

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    AMOLED("AMOLED black"),
    MIDNIGHT("Midnight blue"),
    GLASS("Liquid glass"),
}

/** Ready-made looks: a mode plus an accent, applied together (the Linux app's presets, minus the light ones). */
enum class ThemePreset(val label: String, val mode: ThemeMode, val accent: String) {
    CRIMSON_NIGHT("Crimson Night", ThemeMode.DARK, "#e8194f"),
    AURORA_GLASS("Aurora Glass", ThemeMode.GLASS, "#8b5cf6"),
    OCEAN_GLASS("Ocean Glass", ThemeMode.GLASS, "#22b8f0"),
    ROSE_GLASS("Rose Glass", ThemeMode.GLASS, "#f0468c"),
    PURE_BLACK("Pure Black", ThemeMode.AMOLED, "#10b981"),
    DEEP_SEA("Deep Sea", ThemeMode.MIDNIGHT, "#3b82f6"),
    SUNSET_DUSK("Sunset Dusk", ThemeMode.DARK, "#f97316"),
}

enum class Accent(val label: String, val hex: String) {
    CRIMSON("Crimson", "#e8194f"),
    SUNSET("Sunset", "#f97316"),
    AMBER("Amber", "#f5b014"),
    EMERALD("Emerald", "#10b981"),
    OCEAN("Ocean", "#3b82f6"),
    VIOLET("Violet", "#8b5cf6"),
    ROSE("Rose", "#ec4899"),
}

fun colorOf(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/**
 * The look of the current profile: a mode (surfaces) and an accent. Kept as Compose state, so
 * changing it in Settings, or switching profile, repaints every screen at once.
 */
object ThemeState {
    private val prefs get() = io.github.sherazi5110.cloudstream.Settings.profilePrefs
    var mode by mutableStateOf(ThemeMode.DARK)
        private set
    var accentHex by mutableStateOf(Accent.OCEAN.hex)
        private set

    /** Reads the current profile's look (at start and after switching profile). */
    fun load() {
        mode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "DARK") }.getOrDefault(ThemeMode.DARK)
        // The Linux app's LIGHT mode isn't available here yet: fall back to dark.
        accentHex = prefs.getString("theme_accent", null)?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: Accent.OCEAN.hex
    }

    fun changeMode(m: ThemeMode) { mode = m; prefs.edit().putString("theme_mode", m.name).apply() }
    fun changeAccent(hex: String) { accentHex = hex; prefs.edit().putString("theme_accent", hex).apply() }
    fun apply(p: ThemePreset) { changeMode(p.mode); changeAccent(p.accent) }
    val preset: ThemePreset? get() = ThemePreset.entries.firstOrNull { it.mode == mode && it.accent.equals(accentHex, true) }
}

/** Colours of the current look (read in composition, so they follow [ThemeState]). */
object Palette {
    private val mode get() = ThemeState.mode
    val background: Color get() = when (mode) {
        ThemeMode.DARK -> Color(0xFF0C0C10)
        ThemeMode.AMOLED -> Color(0xFF000000)
        ThemeMode.MIDNIGHT -> Color(0xFF0A0F1C)
        ThemeMode.GLASS -> Color(0xFF0B0820)
    }
    val rail: Color get() = when (mode) {
        ThemeMode.DARK -> Color(0xFF09090C)
        ThemeMode.AMOLED -> Color(0xFF000000)
        ThemeMode.MIDNIGHT -> Color(0xFF0D1324)
        ThemeMode.GLASS -> Color.White.copy(alpha = 0.07f)
    }
    val surface: Color get() = when (mode) {
        ThemeMode.DARK -> Color(0xFF17171C)
        ThemeMode.AMOLED -> Color(0xFF0B0B0D)
        ThemeMode.MIDNIGHT -> Color(0xFF121A2E)
        ThemeMode.GLASS -> Color.White.copy(alpha = 0.08f)
    }
    val surfaceHigh: Color get() = when (mode) {
        ThemeMode.DARK -> Color(0xFF25252C)
        ThemeMode.AMOLED -> Color(0xFF17171B)
        ThemeMode.MIDNIGHT -> Color(0xFF1C2742)
        ThemeMode.GLASS -> Color.White.copy(alpha = 0.13f)
    }
    val outline: Color get() = if (mode == ThemeMode.GLASS) Color.White.copy(alpha = 0.18f) else Color(0xFF34343C)
    val accent: Color get() = colorOf(ThemeState.accentHex)
    val text = Color(0xFFFFFFFF)
    val muted: Color get() = if (mode == ThemeMode.MIDNIGHT) Color(0xFF9AA6C2) else Color(0xFFA1A1AA)
    val tmdb = Color(0xFF01B4E4)
    val imdb = Color(0xFFF5C518)
    val watched = Color(0xFF4ADE80)
}

/** Liquid glass: soft light blobs in the accent and two companion colours over deep ink. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val a = Palette.accent
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF0B0820), Color(0xFF120A2C), Color(0xFF070512))))
        fun blob(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(
            Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = androidx.compose.ui.geometry.Offset(size.width * cx, size.height * cy), radius = size.maxDimension * r),
            radius = size.maxDimension * r, center = androidx.compose.ui.geometry.Offset(size.width * cx, size.height * cy))
        blob(0.18f, 0.12f, 0.6f, a.copy(alpha = 0.55f))
        blob(0.88f, 0.22f, 0.5f, Color(0xFF38BDF8).copy(alpha = 0.38f))
        blob(0.62f, 1.05f, 0.65f, Color(0xFFEC4899).copy(alpha = 0.36f))
    }
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
