package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Something the user can press play on: a movie, or one episode of a series. */
data class Playable(
    val label: String,
    val data: String,
    val season: Int? = null,
    val episode: Int? = null,
    val name: String? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val dub: String? = null,
)

data class Links(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>)

sealed interface LinkEvent {
    data class Link(val link: ExtractorLink) : LinkEvent
    data class Subtitle(val subtitle: SubtitleFile) : LinkEvent
    data class Done(val error: Throwable?, val timedOut: Boolean) : LinkEvent
}

data class SearchBatch(val api: MainAPI, val results: List<SearchResponse>, val error: Throwable?)

/**
 * The provider call sequence the Android UI drives through APIRepository:
 * getMainPage / search -> load -> loadLinks. Everything streams: results reach the
 * UI as each provider, section or extractor finishes, and slow ones never block fast ones.
 */
object Engine {
    private val threads = AtomicInteger()

    /** Extension code runs on its own pool so a misbehaving extension cannot starve the UI or IO. */
    val dispatcher = Executors.newFixedThreadPool(64) { r ->
        Thread(r, "extension-${threads.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private const val DEFAULT_TIMEOUT = 45_000L

    /** Sorted by name: extensions load in parallel, so registration order isn't stable. */
    fun providers(): List<MainAPI> = APIHolder.allProviders.toList().sortedBy { it.name.lowercase() }

    fun provider(name: String): MainAPI? = providers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun log(api: MainAPI, what: String, t: Throwable) {
        if (t !is CancellationException) System.err.println("W/${api.name}: $what failed: $t")
    }

    /** Home sections in mainPage order; each one is emitted with its index as soon as it loads. */
    fun home(api: MainAPI): Flow<IndexedValue<HomePageList>> = channelFlow {
        api.mainPage.forEachIndexed { index, section ->
            launch(dispatcher) {
                val response = homePage(api, section, 1)
                response?.items?.filter { it.list.isNotEmpty() }?.forEachIndexed { i, list ->
                    send(IndexedValue(index * 100 + i, list))
                }
            }
        }
    }

    suspend fun homePage(api: MainAPI, section: MainPageData, page: Int): HomePageResponse? = withContext(dispatcher) {
        try {
            withTimeoutOrNull(api.getMainPageTimeoutMs ?: DEFAULT_TIMEOUT) {
                api.getMainPage(page, MainPageRequest(section.name, section.data, section.horizontalImages))
            }
        } catch (t: Throwable) {
            log(api, "home '${section.name}'", t)
            null
        }
    }

    /** One batch per provider, in completion order. */
    fun search(query: String, apis: List<MainAPI> = providers()): Flow<SearchBatch> = channelFlow {
        apis.forEach { api ->
            launch(dispatcher) {
                val batch = try {
                    val results = withTimeout(api.searchTimeoutMs ?: DEFAULT_TIMEOUT) { api.search(query, 1)?.items.orEmpty() }
                    SearchBatch(api, results, null)
                } catch (t: Throwable) {
                    log(api, "search", t)
                    SearchBatch(api, emptyList(), t)
                }
                send(batch)
            }
        }
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? = withContext(dispatcher) {
        withTimeout(api.loadTimeoutMs ?: DEFAULT_TIMEOUT) { api.load(url) }
    }

    fun playables(response: LoadResponse): List<Playable> = when (response) {
        is MovieLoadResponse -> listOf(Playable(response.name, response.dataUrl))
        is LiveStreamLoadResponse -> listOf(Playable(response.name, response.dataUrl))
        is TorrentLoadResponse -> listOfNotNull(response.magnet ?: response.torrent).map { Playable(response.name, it) }
        is TvSeriesLoadResponse -> response.episodes.map { it.toPlayable(null) }
        is AnimeLoadResponse -> response.episodes.flatMap { (dub, eps) -> eps.map { it.toPlayable(dub.name) } }
        else -> emptyList()
    }

    private fun com.lagradost.cloudstream3.Episode.toPlayable(dub: String?): Playable {
        val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString("")
        val label = listOf(code, name ?: "").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Episode" }
        return Playable(label, data, season, episode, name, posterUrl, description, dub)
    }

    /**
     * Streams links and subtitles as the extension reports them. The call is bounded by
     * the extension's own loadLinksTimeoutMs or the user's setting; links found before
     * the deadline are kept, which is how the Android player behaves too.
     */
    fun links(api: MainAPI, data: String): Flow<LinkEvent> = channelFlow {
        val job = launch(dispatcher) {
            var error: Throwable? = null
            val timeout = api.loadLinksTimeoutMs ?: (Settings.sourcesTimeoutSeconds * 1000L)
            val finished = try {
                withTimeoutOrNull(timeout) {
                    api.loadLinks(
                        data, false,
                        { trySend(LinkEvent.Subtitle(it)) },
                        { trySend(LinkEvent.Link(it)) },
                    )
                    true
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log(api, "loadLinks", t)
                error = t
                true
            }
            send(LinkEvent.Done(error, timedOut = finished == null))
            close()
        }
        awaitClose { job.cancel() }
    }

    /** Collects everything (used by the CLI). */
    suspend fun allLinks(api: MainAPI, data: String): Links {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        links(api, data).collect {
            when (it) {
                is LinkEvent.Link -> links += it.link
                is LinkEvent.Subtitle -> subtitles += it.subtitle
                is LinkEvent.Done -> Unit
            }
        }
        return Links(rank(links.distinctBy { it.url }), subtitles.distinctBy { it.url })
    }

    /** Best first: closest to the preferred quality without exceeding it, then higher. */
    fun rank(links: List<ExtractorLink>): List<ExtractorLink> {
        val preferred = Settings.preferredQuality.takeIf { it > 0 } ?: Int.MAX_VALUE
        return links.sortedWith(compareBy<ExtractorLink> { if (it.quality <= preferred) 0 else 1 }
            .thenByDescending { if (it.quality <= preferred) it.quality else -it.quality })
    }
}
