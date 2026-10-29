package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Platform

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    AMOLED("AMOLED black"),
    MIDNIGHT("Midnight blue"),
    LIGHT("Light"),
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
    private val prefs get() = Platform.context.getSharedPreferences("linux_settings", 0)
    private val listeners = mutableListOf<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    private fun changed() = listeners.forEach { it() }

    var mode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "DARK") }.getOrDefault(ThemeMode.DARK)
        set(v) { prefs.edit().putString("theme_mode", v.name).apply(); changed() }

    /** Accent as #rrggbb; one of [Accent] or a custom colour. */
    var accent: String
        get() = prefs.getString("theme_accent", null)?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: Accent.CRIMSON.hex
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

    private data class Palette(
        val bg: String, val rail: String, val surface: String, val surface2: String, val surface3: String,
        val line: String, val text: String, val muted: String, val faint: String, val scrim: String, val chip: String,
    )

    private fun palette() = when (mode) {
        ThemeMode.DARK -> Palette("#0b0b10", "#101016", "#15151c", "#1d1d26", "#282832", "rgba(255,255,255,0.07)", "#f3f3f6", "#a0a0ad", "#6c6c79", "rgba(0,0,0,0.66)", "rgba(255,255,255,0.06)")
        ThemeMode.AMOLED -> Palette("#000000", "#000000", "#0b0b0d", "#131316", "#1d1d21", "rgba(255,255,255,0.08)", "#f5f5f7", "#9d9da8", "#66666f", "rgba(0,0,0,0.75)", "rgba(255,255,255,0.06)")
        ThemeMode.MIDNIGHT -> Palette("#0a0f1c", "#0d1324", "#121a2e", "#18223a", "#22304d", "rgba(160,190,255,0.10)", "#eef2fb", "#9aa6c2", "#66728e", "rgba(3,6,15,0.7)", "rgba(160,190,255,0.07)")
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
            "-fx-base: ${p.surface2}; -fx-control-inner-background: ${p.surface};"
    }

    fun rgba(hex: String, alpha: Double): String {
        if (hex.startsWith("rgba")) return hex
        val c = hex.removePrefix("#")
        val (r, g, b) = listOf(0, 2, 4).map { c.substring(it, it + 2).toInt(16) }
        return "rgba($r,$g,$b,$alpha)"
    }

    val background: String get() = palette().bg
}
