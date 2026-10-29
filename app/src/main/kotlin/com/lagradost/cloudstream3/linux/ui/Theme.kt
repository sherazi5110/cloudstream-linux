package com.lagradost.cloudstream3.linux.ui

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    AMOLED("AMOLED black"),
    MIDNIGHT("Midnight blue"),
    LIGHT("Light"),
    GLASS("Liquid glass"),
}

/** Ready-made looks: a mode plus an accent, applied together. */
enum class ThemePreset(val label: String, val mode: ThemeMode, val accent: String) {
    CRIMSON_NIGHT("Crimson Night", ThemeMode.DARK, "#e8194f"),
    AURORA_GLASS("Aurora Glass", ThemeMode.GLASS, "#8b5cf6"),
    OCEAN_GLASS("Ocean Glass", ThemeMode.GLASS, "#22b8f0"),
    ROSE_GLASS("Rose Glass", ThemeMode.GLASS, "#f0468c"),
    PURE_BLACK("Pure Black", ThemeMode.AMOLED, "#10b981"),
    DEEP_SEA("Deep Sea", ThemeMode.MIDNIGHT, "#3b82f6"),
    SUNSET_DUSK("Sunset Dusk", ThemeMode.DARK, "#f97316"),
    PAPER("Paper", ThemeMode.LIGHT, "#e8194f"),
    MINT_DAY("Mint Day", ThemeMode.LIGHT, "#0f9f75"),
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

/**
 * The look of the app: a base mode (surfaces/text) plus an accent colour and an optional
 * wallpaper. Applied as CSS looked-up colours on the root, so every page follows live.
 */
object Theme {
    /** Each profile has its own look. */
    private val prefs get() = com.lagradost.cloudstream3.linux.Settings.profilePrefs
    private val listeners = mutableListOf<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    fun changed() = listeners.forEach { it() }

    var mode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "DARK") }.getOrDefault(ThemeMode.DARK)
        set(v) { prefs.edit().putString("theme_mode", v.name).apply(); changed() }

    /** Accent as #rrggbb; one of [Accent] or a custom colour. */
    var accent: String
        get() = prefs.getString("theme_accent", null)?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: Accent.OCEAN.hex
        set(v) { prefs.edit().putString("theme_accent", v).apply(); changed() }

    /** Optional wallpaper image file behind every page. */
    var wallpaper: String?
        get() = prefs.getString("theme_wallpaper", null)?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
        set(v) { prefs.edit().putString("theme_wallpaper", v ?: "").apply(); changed() }

    /** How strongly the wallpaper is darkened (or lightened in light mode), 0..1. */
    var wallpaperDim: Double
        get() = prefs.getString("theme_wallpaper_dim", null)?.toDoubleOrNull() ?: 0.65
        set(v) { prefs.edit().putString("theme_wallpaper_dim", v.toString()).apply(); changed() }

    var wallpaperBlur: Boolean
        get() = prefs.getString("theme_wallpaper_blur", null) != "false"
        set(v) { prefs.edit().putString("theme_wallpaper_blur", v.toString()).apply(); changed() }

    val isLight get() = mode == ThemeMode.LIGHT
    val isGlass get() = mode == ThemeMode.GLASS

    fun apply(preset: ThemePreset) {
        prefs.edit().putString("theme_mode", preset.mode.name).putString("theme_accent", preset.accent).apply()
        changed()
    }

    /** The preset matching the current mode and accent, if any. */
    val preset: ThemePreset? get() = ThemePreset.entries.firstOrNull { it.mode == mode && it.accent.equals(accent, true) }

    /**
     * Liquid glass backdrop when no wallpaper is set: soft light blobs in the accent and two
     * companion colours over deep ink, which the translucent panels then frost.
     */
    fun glassBackdropStyle(): String {
        val a = accent
        return "-fx-background-color: " +
            "radial-gradient(center 18% 12%, radius 60%, ${rgba(a, 0.72)}, ${rgba(a, 0.0)}), " +
            "radial-gradient(center 88% 22%, radius 50%, rgba(56,189,248,0.50), rgba(56,189,248,0.0)), " +
            "radial-gradient(center 62% 105%, radius 65%, rgba(236,72,153,0.48), rgba(236,72,153,0.0)), " +
            "radial-gradient(center 0% 95%, radius 45%, rgba(34,211,238,0.22), rgba(34,211,238,0.0)), " +
            "linear-gradient(to bottom right, #0b0820, #120a2c 55%, #070512);"
    }

    private data class Palette(
        val bg: String, val rail: String, val surface: String, val surface2: String, val surface3: String,
        val line: String, val text: String, val muted: String, val faint: String, val scrim: String, val chip: String,
    )

    private fun palette() = when (mode) {
        ThemeMode.DARK -> Palette("#0b0b10", "#101016", "#15151c", "#1d1d26", "#282832", "rgba(255,255,255,0.07)", "#f3f3f6", "#a0a0ad", "#6c6c79", "rgba(0,0,0,0.66)", "rgba(255,255,255,0.06)")
        ThemeMode.AMOLED -> Palette("#000000", "#000000", "#0b0b0d", "#131316", "#1d1d21", "rgba(255,255,255,0.08)", "#f5f5f7", "#9d9da8", "#66666f", "rgba(0,0,0,0.75)", "rgba(255,255,255,0.06)")
        ThemeMode.MIDNIGHT -> Palette("#0a0f1c", "#0d1324", "#121a2e", "#18223a", "#22304d", "rgba(160,190,255,0.10)", "#eef2fb", "#9aa6c2", "#66728e", "rgba(3,6,15,0.7)", "rgba(160,190,255,0.07)")
        // Translucent layers: whatever is behind (glass backdrop or wallpaper) shows through.
        ThemeMode.GLASS -> Palette("#0b0820", "rgba(255,255,255,0.09)", "rgba(255,255,255,0.075)", "rgba(255,255,255,0.11)", "rgba(255,255,255,0.17)", "rgba(255,255,255,0.16)", "#ffffff", "rgba(236,236,255,0.74)", "rgba(220,220,245,0.50)", "rgba(8,6,24,0.55)", "rgba(255,255,255,0.10)")
        ThemeMode.LIGHT -> Palette("#f4f4f7", "#ffffff", "#ffffff", "#f0f0f4", "#e3e3ea", "rgba(0,0,0,0.09)", "#15151b", "#5d5d6b", "#8a8a98", "rgba(20,20,30,0.45)", "rgba(0,0,0,0.05)")
    }

    /** Inline style for the scene root: overrides the looked-up colours in theme.css. */
    fun rootStyle(): String {
        val p = palette()
        val a = accent
        return "-cs-bg: ${p.bg}; -cs-rail: ${p.rail}; -cs-surface: ${p.surface}; -cs-surface-2: ${p.surface2}; " +
            "-cs-surface-3: ${p.surface3}; -cs-line: ${p.line}; -cs-text: ${p.text}; -cs-muted: ${p.muted}; " +
            "-cs-faint: ${p.faint}; -cs-scrim: ${p.scrim}; -cs-chip: ${p.chip}; " +
            "-cs-accent: $a; -cs-accent-hover: derive($a, 18%); -cs-accent-soft: ${rgba(a, 0.16)}; -cs-accent-glow: ${rgba(a, 0.55)}; " +
            "-cs-bg-0: ${rgba(p.bg, 0.0)}; -cs-bg-60: ${rgba(p.bg, 0.6)}; -cs-bg-85: ${rgba(p.bg, 0.85)}; -cs-bg-96: ${rgba(p.bg, 0.96)}; " +
            // -fx-base feeds derive() in modena, so it must be opaque.
            "-fx-base: ${if (isGlass) "#2a2645" else p.surface2}; -fx-control-inner-background: ${if (isGlass) "#1c1838" else p.surface};"
    }

    fun rgba(hex: String, alpha: Double): String {
        if (hex.startsWith("rgba")) return hex
        val c = hex.removePrefix("#")
        val (r, g, b) = listOf(0, 2, 4).map { c.substring(it, it + 2).toInt(16) }
        return "rgba($r,$g,$b,$alpha)"
    }

    val background: String get() = palette().bg
}
