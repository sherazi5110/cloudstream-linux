package io.github.sherazi5110.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper

/** What the user watched last for one title; drives Continue Watching and Resume. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchEntry(
    val apiName: String,
    val url: String,
    val name: String,
    val posterUrl: String?,
    val episodeData: String,
    val episodeLabel: String?,
    val position: Double,
    val duration: Double,
    val updatedAt: Long,
) {
    val progress: Double get() = if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0
    val finished: Boolean get() = progress > 0.92
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Bookmark(
    val apiName: String,
    val url: String,
    val name: String,
    val posterUrl: String?,
    val type: String?,
    val addedAt: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Progress(val position: Double, val duration: Double)

/** History, per-episode progress and bookmarks, persisted as JSON in the app data dir. */
object Library {
    private val prefs get() = App.context.getSharedPreferences(Profiles.file("library"), 0)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Returns a function that unregisters the listener. */
    fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private inline fun <reified T> read(key: String, default: T): T =
        prefs.getString(key, null)?.let { runCatching { mapper.readValue<T>(it) }.getOrNull() } ?: default

    private fun write(key: String, value: Any) {
        prefs.edit().putString(key, mapper.writeValueAsString(value)).apply()
        listeners.forEach { it() }
    }

    fun history(): List<WatchEntry> = read<List<WatchEntry>>("history", emptyList()).sortedByDescending { it.updatedAt }

    fun continueWatching(): List<WatchEntry> = history().filter { it.progress in 0.02..0.92 }

    fun lastWatched(apiName: String, url: String): WatchEntry? = history().find { it.apiName == apiName && it.url == url }

    fun progress(apiName: String, episodeData: String): Progress? =
        read<Map<String, Progress>>("progress", emptyMap())["$apiName|$episodeData"]

    @Synchronized
    fun record(entry: WatchEntry) {
        write("history", (listOf(entry) + history().filterNot { it.apiName == entry.apiName && it.url == entry.url }).take(500))
        val progress = read<Map<String, Progress>>("progress", emptyMap()).toMutableMap()
        progress["${entry.apiName}|${entry.episodeData}"] = Progress(entry.position, entry.duration)
        write("progress", progress)
    }

    @Synchronized
    fun removeHistory(apiName: String, url: String) = write("history", history().filterNot { it.apiName == apiName && it.url == url })

    fun clearHistory() = write("history", emptyList<WatchEntry>())

    /**
     * Marks episodes (or a film) as watched: each gets a finished progress, and the title's
     * history entry points at the last one, so it leaves Continue Watching and shows as watched.
     */
    @Synchronized
    fun markWatched(apiName: String, url: String, name: String, posterUrl: String?, episodes: List<Pair<String, String?>>) {
        if (episodes.isEmpty()) return
        val progress = read<Map<String, Progress>>("progress", emptyMap()).toMutableMap()
        episodes.forEach { (data, _) ->
            val d = progress["$apiName|$data"]?.duration?.takeIf { it > 0 } ?: 1.0
            progress["$apiName|$data"] = Progress(d, d)
        }
        val (lastData, lastLabel) = episodes.last()
        val d = progress["$apiName|$lastData"]!!.duration
        val entry = WatchEntry(apiName, url, name, posterUrl, lastData, lastLabel, d, d, System.currentTimeMillis())
        prefs.edit()
            .putString("progress", mapper.writeValueAsString(progress))
            .putString("history", mapper.writeValueAsString((listOf(entry) + history().filterNot { it.apiName == apiName && it.url == url }).take(500)))
            .apply()
        listeners.forEach { it() }
    }

    /** The opposite of [markWatched]: forgets the progress of those episodes and the history entry. */
    @Synchronized
    fun markUnwatched(apiName: String, url: String, episodes: List<String>) {
        val progress = read<Map<String, Progress>>("progress", emptyMap()).toMutableMap()
        episodes.forEach { progress.remove("$apiName|$it") }
        prefs.edit()
            .putString("progress", mapper.writeValueAsString(progress))
            .putString("history", mapper.writeValueAsString(history().filterNot { it.apiName == apiName && it.url == url }))
            .apply()
        listeners.forEach { it() }
    }

    /** Marks the episode a Continue Watching entry is on as watched. */
    fun markWatched(e: WatchEntry) = markWatched(e.apiName, e.url, e.name, e.posterUrl, listOf(e.episodeData to e.episodeLabel))

    fun isWatched(apiName: String, episodeData: String) =
        progress(apiName, episodeData)?.let { it.duration > 0 && it.position / it.duration > 0.92 } == true

    fun bookmarks(): List<Bookmark> = read<List<Bookmark>>("bookmarks", emptyList()).sortedByDescending { it.addedAt }

    fun isBookmarked(apiName: String, url: String) = bookmarks().any { it.apiName == apiName && it.url == url }

    /** Returns true if the title is now bookmarked. */
    @Synchronized
    fun toggleBookmark(b: Bookmark): Boolean {
        val current = bookmarks()
        val exists = current.any { it.apiName == b.apiName && it.url == b.url }
        write("bookmarks", if (exists) current.filterNot { it.apiName == b.apiName && it.url == b.url } else listOf(b) + current)
        return !exists
    }
}
