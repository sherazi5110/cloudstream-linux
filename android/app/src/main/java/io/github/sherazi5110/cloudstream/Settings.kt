package io.github.sherazi5110.cloudstream

import android.content.SharedPreferences
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/** DNS-over-HTTPS resolvers, the same list the Android app offers (RequestsHelper.kt / DohProviders.kt). */
enum class DnsProvider(val label: String, val url: String?, val ips: List<String>) {
    SYSTEM("System default", null, emptyList()),
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    GOOGLE("Google", "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")),
    ADGUARD("AdGuard", "https://dns.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
}

/** App settings, stored apart from the key store extensions use. The Settings screen comes in phase 2. */
object Settings {
    private val prefs: SharedPreferences get() = App.context.getSharedPreferences("app_settings", 0)

    private fun string(key: String, def: String) = prefs.getString(key, null) ?: def
    private fun put(key: String, value: Any) {
        prefs.edit().putString(key, value.toString()).apply()
    }

    /** Start the best source automatically once sources have been found. */
    var autoPlay: Boolean
        get() = string("auto_play", "true").toBoolean()
        set(v) = put("auto_play", v)

    var autoPlayNext: Boolean
        get() = string("auto_play_next", "true").toBoolean()
        set(v) = put("auto_play_next", v)

    /** Preferred maximum quality in pixels; higher sources are ranked below it. 0 = best available. */
    var preferredQuality: Int
        get() = string("preferred_quality", "1080").toInt()
        set(v) = put("preferred_quality", v)

    /** How long an extension may look for sources before the player gives up waiting. */
    var sourcesTimeoutSeconds: Int
        get() = string("sources_timeout", "60").toInt()
        set(v) = put("sources_timeout", v)

    var dns: DnsProvider
        get() = runCatching { DnsProvider.valueOf(string("dns", "SYSTEM")) }.getOrDefault(DnsProvider.SYSTEM)
        set(v) = put("dns", v.name)

    var dnsFallback: Boolean
        get() = string("dns_fallback", "true").toBoolean()
        set(v) = put("dns_fallback", v)

    /** The extension Home shows. */
    var homeProvider: String?
        get() = prefs.getString("last_home_provider", null)
        set(v) = put("last_home_provider", v ?: "")

    /** Like the official app's NSFW setting; off by default (the switch arrives with Settings in phase 2). */
    var showNsfw: Boolean
        get() = string("show_nsfw", "false").toBoolean()
        set(v) = put("show_nsfw", v)

    /** "auto" (detect Android TV), "phone" or "tv": for TV boxes that report themselves wrongly. */
    var layout: String
        get() = string("layout", "auto")
        set(v) = put("layout", v)

    var playerZoom: String
        get() = string("player_zoom", "FIT")
        set(v) = put("player_zoom", v)
}

/** What the user may see. Kids profiles join this in phase 2; for now it hides adult content. */
object ContentFilter {
    val nsfwAllowed get() = Settings.showNsfw

    fun allows(api: MainAPI): Boolean = TvType.NSFW !in api.supportedTypes || nsfwAllowed
    fun allows(item: SearchResponse): Boolean = item.type != TvType.NSFW || nsfwAllowed
    fun allows(response: LoadResponse): Boolean = response.type != TvType.NSFW || nsfwAllowed
}
