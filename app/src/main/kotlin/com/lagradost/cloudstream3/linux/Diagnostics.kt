package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Runs the same steps the UI does (home -> search -> load -> sources) against one
 * provider and reports where it breaks. This is what users attach to bug reports.
 */
object ProviderTest {
    data class Step(val name: String, val ok: Boolean, val detail: String, val ms: Long)

    fun run(api: MainAPI): Flow<Step> = flow {
        var sample: SearchResponse? = null

        suspend fun <T> timed(block: suspend () -> T): Pair<Result<T>, Long> {
            val start = System.currentTimeMillis()
            val result = runCatching { block() }
            return result to System.currentTimeMillis() - start
        }

        if (api.hasMainPage && api.mainPage.isNotEmpty()) {
            val (r, ms) = timed { Engine.homePage(api, api.mainPage.first(), 1) }
            val items = r.getOrNull()?.items?.flatMap { it.list }.orEmpty()
            sample = items.firstOrNull()
            emit(Step("Home page", items.isNotEmpty(), r.exceptionOrNull()?.toString() ?: "${items.size} items in “${api.mainPage.first().name}”", ms))
        }

        val query = sample?.name?.split(' ')?.firstOrNull { it.length > 2 } ?: "the"
        val (s, sms) = timed { withTimeoutOrNull(45_000) { api.search(query, 1)?.items }.orEmpty() }
        val results = s.getOrNull().orEmpty()
        emit(Step("Search “$query”", results.isNotEmpty(), s.exceptionOrNull()?.toString() ?: "${results.size} results", sms))
        sample = sample ?: results.firstOrNull()

        val item = sample ?: run {
            emit(Step("Details", false, "Nothing to open: home and search returned no items", 0))
            return@flow
        }
        val (l, lms) = timed { Engine.load(api, item.url) }
        val response = l.getOrNull()
        val playables = response?.let { Engine.playables(it) }.orEmpty()
        emit(Step("Details “${item.name}”", response != null && playables.isNotEmpty(),
            l.exceptionOrNull()?.toString() ?: if (response == null) "returned nothing" else "${playables.size} playable item(s)", lms))
        val first = playables.firstOrNull() ?: return@flow

        val start = System.currentTimeMillis()
        var links = 0
        var subs = 0
        var error: Throwable? = null
        var timedOut = false
        Engine.links(api, first.data).collect {
            when (it) {
                is LinkEvent.Link -> links++
                is LinkEvent.Subtitle -> subs++
                is LinkEvent.Done -> { error = it.error; timedOut = it.timedOut }
            }
        }
        val detail = buildString {
            append("$links source(s), $subs subtitle(s)")
            if (timedOut) append(" · stopped at the time limit")
            error?.let { append(" · error: $it") }
        }
        emit(Step("Sources for “${first.label}”", links > 0, detail, System.currentTimeMillis() - start))
    }
}

/** Sizes and cleanup of the app's on-disk data. */
object StorageInfo {
    data class Entry(val label: String, val description: String, val dir: File, val clearable: Boolean)

    fun entries(): List<Entry> {
        val cache = Platform.context.getCacheDir()
        return listOf(
            Entry("Poster cache", "Artwork downloaded for browsing", File(cache, "images"), true),
            Entry("Network cache", "Cached HTTP responses", File(cache, "http_cache"), true),
            Entry("Translated extensions", "Android extensions converted for Linux; rebuilt on next start", File(cache, "translated"), true),
            Entry("Logs", "App and player logs", File(AppDirs.data, "logs"), true),
            Entry("Extensions", "Installed extension files", RepositoryManager.extensionsDir, false),
        )
    }

    fun size(dir: File): Long = if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Empties a directory but keeps it (the HTTP cache keeps its journal in use). */
    fun clear(dir: File) {
        dir.listFiles()?.forEach { if (it.name != "journal") it.deleteRecursively() }
    }
}
