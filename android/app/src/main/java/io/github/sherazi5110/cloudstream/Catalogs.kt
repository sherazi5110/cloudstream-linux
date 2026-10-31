package io.github.sherazi5110.cloudstream

import android.content.SharedPreferences
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import org.json.JSONArray

/**
 * What the home screen shows. Each extension's catalogs are its `mainPage` sections;
 * the user can hide and reorder them per extension, and can combine several extensions
 * into one feed. Catalogs are stored by name, so an extension update that adds a
 * catalog shows it (at the end) and one that removes a catalog simply drops it.
 */
object Catalogs {
    data class Entry(val index: Int, val section: MainPageData, val enabled: Boolean)

    private val prefs: SharedPreferences get() = App.context.getSharedPreferences("home_catalogs", 0)
    private val listeners = mutableListOf<() -> Unit>()

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun list(key: String): List<String> = prefs.getString(key, null)?.let { raw ->
        runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
    }.orEmpty()

    private fun putList(key: String, value: List<String>) {
        prefs.edit().putString(key, JSONArray(value).toString()).apply()
    }

    /** Every catalog of [api] in the user's order, with its original index and on/off state. */
    fun all(api: MainAPI): List<Entry> {
        val disabled = list("disabled:${api.name}").toSet()
        val order = list("order:${api.name}").withIndex().associate { it.value to it.index }
        return api.mainPage.withIndex()
            .sortedBy { (i, s) -> order[s.name] ?: (order.size + i) }
            .map { (i, s) -> Entry(i, s, s.name !in disabled) }
    }

    /** The catalogs to load for [api]: enabled ones, in order. */
    fun enabled(api: MainAPI): List<Entry> = all(api).filter { it.enabled }

    fun save(api: MainAPI, entries: List<Entry>) {
        putList("order:${api.name}", entries.map { it.section.name })
        putList("disabled:${api.name}", entries.filter { !it.enabled }.map { it.section.name })
        changed()
    }

    /** Applies a whole Home-manager session at once, so the home screen reloads only once. */
    fun saveAll(edits: Map<MainAPI, List<Entry>>, multiFeed: Boolean, feed: List<String>) {
        val editor = prefs.edit()
        edits.forEach { (api, entries) ->
            editor.putString("order:${api.name}", JSONArray(entries.map { it.section.name }).toString())
            editor.putString("disabled:${api.name}", JSONArray(entries.filter { !it.enabled }.map { it.section.name }).toString())
        }
        editor.putString("multi_feed", multiFeed.toString())
        editor.putString("feed", JSONArray(feed).toString())
        editor.apply()
        changed()
    }

    fun reset(api: MainAPI) {
        prefs.edit().remove("order:${api.name}").remove("disabled:${api.name}").apply()
        changed()
    }

    fun isCustomized(api: MainAPI) = all(api).let { e -> e.any { !it.enabled } || e.map { it.index } != e.indices.toList() }

    /** Multi-extension feed: when on, home shows [feed] providers' catalogs one after another. */
    var multiFeed: Boolean
        get() = prefs.getString("multi_feed", null) == "true"
        set(v) { prefs.edit().putString("multi_feed", v.toString()).apply(); changed() }

    var feed: List<String>
        get() = list("feed")
        set(v) { putList("feed", v); changed() }

    /** The feed's providers, in order, limited to ones that are installed and have a home page. */
    fun feedProviders(available: List<MainAPI>): List<MainAPI> {
        val byName = available.associateBy { it.name }
        return feed.mapNotNull { byName[it] }
    }
}
