package com.lagradost.cloudstream3.linux

import android.content.SharedPreferences

/** DNS-over-HTTPS resolvers, the same list the Android app offers (RequestsHelper.kt / DohProviders.kt). */
enum class DnsProvider(val label: String, val url: String?, val ips: List<String>) {
    SYSTEM("System default", null, emptyList()),
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    GOOGLE("Google", "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")),
    ADGUARD("AdGuard", "https://dns.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    DNS_SB("DNS.SB", "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11")),
    DNS_WATCH("DNS.Watch", "https://resolver2.dns.watch/dns-query", listOf("84.200.69.80", "84.200.70.40")),
    CANADIAN_SHIELD("Canadian Shield", "https://private.canadianshield.cira.ca/dns-query", listOf("149.112.121.10", "149.112.122.10")),
}

/**
 * App settings (separate from the key store extensions use). App-wide ones live in
 * "linux_settings"; the look and player preferences belong to the current [Profiles] profile.
 */
object Settings {
    private val prefs: SharedPreferences get() = Platform.context.getSharedPreferences("linux_settings", 0)

    /** The current profile's preferences (theme, player, home). */
    val profilePrefs: SharedPreferences get() = Platform.context.getSharedPreferences(Profiles.file("profile_settings"), 0)

    private val PROFILE_KEYS = setOf(
        "auto_play_next", "subtitle_language", "preferred_quality", "player_volume", "hero_slider", "anti_spoiler",
        "player_equalizer", "audio_language", "subtitle_size", "subtitle_color", "subtitle_outline", "subtitle_background",
        "player_zoom", "last_home_provider", "home_section",
        "theme_mode", "theme_accent", "theme_wallpaper", "theme_wallpaper_dim", "theme_wallpaper_blur",
    )

    private fun store(key: String) = if (key in PROFILE_KEYS) profilePrefs else prefs
    private fun string(key: String, def: String) = store(key).getString(key, null) ?: def
    private fun put(key: String, value: Any) {
        store(key).edit().putString(key, value.toString()).apply()
    }

    /** Before profiles (v5 and older) these were app-wide: they become the first profile's. */
    fun migrateToProfiles() {
        if (prefs.contains("profiles_migrated")) return
        val target = Platform.context.getSharedPreferences("profile_settings", 0).edit()
        prefs.getAll().forEach { (k, v) -> if (k in PROFILE_KEYS && v is String) target.putString(k, v) }
        target.apply()
        prefs.edit().putString("profiles_migrated", "true").apply()
    }

    /** Lower-power playback for low-end PCs and battery: skip H.264 deblocking, buffer more. */
    var batterySaver: Boolean
        get() = string("battery_saver", "false").toBoolean()
        set(v) = put("battery_saver", v)

    var autoPlayNext: Boolean
        get() = string("auto_play_next", "true").toBoolean()
        set(v) = put("auto_play_next", v)

    /** Subtitle languages to turn on automatically, e.g. "en" or "ar,en"; empty = off by default. */
    var subtitleLanguage: String
        get() = string("subtitle_language", "")
        set(v) = put("subtitle_language", v)

    /** Start the best source automatically once sources have been found. */
    var autoPlay: Boolean
        get() = string("auto_play", "true").toBoolean()
        set(v) = put("auto_play", v)

    /** Preferred maximum quality in pixels; higher sources are ranked below it. 0 = best available. */
    var preferredQuality: Int
        get() = string("preferred_quality", "1080").toInt()
        set(v) = put("preferred_quality", v)

    var dns: DnsProvider
        get() = runCatching { DnsProvider.valueOf(string("dns", "SYSTEM")) }.getOrDefault(DnsProvider.SYSTEM)
        set(v) = put("dns", v.name)

    /** Use system DNS for a while when the DNS-over-HTTPS resolver fails. */
    var dnsFallback: Boolean
        get() = string("dns_fallback", "true").toBoolean()
        set(v) = put("dns_fallback", v)

    /** Upper bound for one provider's loadLinks call; links found before it are kept. */
    var sourcesTimeoutSeconds: Int
        get() = string("sources_timeout", "60").toInt()
        set(v) = put("sources_timeout", v)

    var autoUpdateExtensions: Boolean
        get() = string("auto_update_extensions", "true").toBoolean()
        set(v) = put("auto_update_extensions", v)

    /** Built-in player volume, 0–200 (VLC allows boosting above 100). */
    var playerVolume: Int
        get() = string("player_volume", "100").toIntOrNull()?.coerceIn(0, 200) ?: 100
        set(v) = put("player_volume", v)

    /** Look for a newer release on GitHub once a day at startup. */
    var checkUpdates: Boolean
        get() = string("check_updates", "true") == "true"
        set(v) = put("check_updates", v)

    /** Home: rotate the featured banner through the first catalog's top titles. */
    var heroSlider: Boolean
        get() = string("hero_slider", "true") == "true"
        set(v) = put("hero_slider", v)

    /** Hide thumbnails and descriptions of episodes not watched yet. */
    var antiSpoiler: Boolean
        get() = string("anti_spoiler", "false") == "true"
        set(v) = put("anti_spoiler", v)

    /** VLC equalizer preset name ("Rock", "Classical", …); empty = off. */
    var playerEqualizer: String
        get() = string("player_equalizer", "")
        set(v) = put("player_equalizer", v)

    /** Audio languages to pick automatically, e.g. "en" or "ja,en"; empty = the stream's default. */
    var audioLanguage: String
        get() = string("audio_language", "")
        set(v) = put("audio_language", v.trim())

    /** Subtitle look: size SMALL/NORMAL/LARGE/HUGE, text colour, outline 0–6, background box. */
    var subtitleSize: String
        get() = string("subtitle_size", "NORMAL")
        set(v) = put("subtitle_size", v)
    var subtitleColor: String
        get() = string("subtitle_color", "#ffffff")
        set(v) = put("subtitle_color", v)
    var subtitleOutline: Int
        get() = string("subtitle_outline", "4").toIntOrNull()?.coerceIn(0, 6) ?: 4
        set(v) = put("subtitle_outline", v)
    var subtitleBackground: Boolean
        get() = string("subtitle_background", "false") == "true"
        set(v) = put("subtitle_background", v)

    /** Built-in player zoom mode, a [com.lagradost.cloudstream3.linux.ui.Zoom] name. */
    var playerZoom: String
        get() = string("player_zoom", "FIT")
        set(v) = put("player_zoom", v)

    var lastHomeProvider: String?
        get() = string("last_home_provider", "").ifBlank { null }
        set(v) = put("last_home_provider", v ?: "")

    /** The Home section last picked in the top bar (a [com.lagradost.cloudstream3.linux.ui.Section] name). */
    var homeSection: String
        get() = string("home_section", "HOME")
        set(v) = put("home_section", v)
}
