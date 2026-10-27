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

/** App settings (separate from the key store extensions use). */
object Settings {
    private val prefs: SharedPreferences get() = Platform.context.getSharedPreferences("linux_settings", 0)

    private fun string(key: String, def: String) = prefs.getString(key, null) ?: def
    private fun put(key: String, value: Any) {
        prefs.edit().putString(key, value.toString()).apply()
    }

    /** builtin = mpv inside the app window; mpv = mpv in its own window; vlc; ffplay. */
    var player: String
        get() = string("player", "builtin").let { if (it == "mpv" || it in Player.supported || it == "builtin") it else "builtin" }
        set(v) = put("player", v)

    /** Lower-power playback for low-end PCs and battery: cheap scaling, no extras. */
    var batterySaver: Boolean
        get() = string("battery_saver", "false").toBoolean()
        set(v) = put("battery_saver", v)

    var autoPlayNext: Boolean
        get() = string("auto_play_next", "true").toBoolean()
        set(v) = put("auto_play_next", v)

    /** mpv --slang value, e.g. "en" or "ar,en"; empty = no subtitles by default. */
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

    var mpvArgs: String
        get() = string("mpv_args", "")
        set(v) = put("mpv_args", v)

    /** Upper bound for one provider's loadLinks call; links found before it are kept. */
    var sourcesTimeoutSeconds: Int
        get() = string("sources_timeout", "60").toInt()
        set(v) = put("sources_timeout", v)

    var autoUpdateExtensions: Boolean
        get() = string("auto_update_extensions", "true").toBoolean()
        set(v) = put("auto_update_extensions", v)

    var lastHomeProvider: String?
        get() = prefs.getString("last_home_provider", null)
        set(v) = put("last_home_provider", v ?: "")
}
