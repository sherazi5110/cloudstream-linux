package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Site addresses: streaming sites often move to a new domain before their extension is
 * updated. Overriding an extension's mainUrl lets it keep working meanwhile, like the
 * "clone site" option in the Android app.
 */
object SiteOverrides {
    private val prefs get() = Platform.context.getSharedPreferences("site_overrides", 0)
    /** Each extension's own address, remembered before any override is applied. */
    private val originals = mutableMapOf<String, String>()

    fun original(api: MainAPI): String = originals.getOrPut(api.name) { api.mainUrl }

    fun get(api: MainAPI): String? = prefs.getString(api.name, null)?.takeIf { it.isNotBlank() }

    fun set(api: MainAPI, url: String?) {
        original(api)
        val clean = url?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        prefs.edit().putString(api.name, clean ?: "").apply()
        apply(api)
    }

    /** Applies the saved address (or restores the original) to a loaded extension. */
    fun apply(api: MainAPI) {
        val base = original(api)
        val wanted = get(api) ?: base
        if (api.mainUrl != wanted) runCatching { api.mainUrl = wanted }
    }
}

/** Checks the project's GitHub releases for a newer version than this one. */
object Updates {
    const val REPO = "sherazi5110/cloudstream-linux"
    const val RELEASES = "https://github.com/$REPO/releases/latest"

    data class Release(val version: String, val url: String)

    /** The latest release if it's newer than [current], else null. */
    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(app.get("https://api.github.com/repos/$REPO/releases/latest", timeout = 15).text)
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return@withContext null
        if (newer(tag, current)) Release(tag.trimStart('v', 'V'), json.optString("html_url", RELEASES)) else null
    }

    /** Compares dotted versions, ignoring a leading v/V: "V4.0.2" > "4.0.1". */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    var lastCheck: Long
        get() = Platform.context.getSharedPreferences("linux_settings", 0).getString("update_last_check", null)?.toLongOrNull() ?: 0
        set(v) { Platform.context.getSharedPreferences("linux_settings", 0).edit().putString("update_last_check", v.toString()).apply() }
}
