package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Something the user can press play on: a movie, or one episode of a series. */
data class Playable(val label: String, val data: String, val season: Int? = null, val episode: Int? = null)

data class Links(val links: List<ExtractorLink>, val subtitles: List<SubtitleFile>)

/**
 * The provider call sequence the Android UI drives through APIRepository:
 * getMainPage / search -> load -> loadLinks, with the same per-call timeouts.
 */
object Engine {
    private const val DEFAULT_TIMEOUT = 60_000L

    fun providers(): List<MainAPI> = APIHolder.allProviders.toList()

    fun provider(name: String): MainAPI? =
        providers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Fans the query out to every provider in parallel, like the Android search screen. */
    suspend fun search(query: String, apis: List<MainAPI> = providers()): List<Pair<MainAPI, SearchResponse>> =
        coroutineScope {
            apis.map { api ->
                async {
                    val results = withTimeoutOrNull(api.searchTimeoutMs ?: DEFAULT_TIMEOUT) {
                        runCatching { api.search(query, 1)?.items }
                            .onFailure { System.err.println("${api.name} search failed: $it") }
                            .getOrNull()
                    }
                    results.orEmpty().map { api to it }
                }
            }.awaitAll().flatten()
        }

    suspend fun mainPage(api: MainAPI, page: Int = 1): List<HomePageList> {
        if (!api.hasMainPage) return emptyList()
        return api.mainPage.flatMap { section ->
            withTimeoutOrNull(api.getMainPageTimeoutMs ?: DEFAULT_TIMEOUT) {
                runCatching {
                    api.getMainPage(page, MainPageRequest(section.name, section.data, section.horizontalImages))?.items
                }.onFailure { System.err.println("${api.name} main page '${section.name}' failed: $it") }.getOrNull()
            }.orEmpty()
        }
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? =
        withTimeoutOrNull(api.loadTimeoutMs ?: DEFAULT_TIMEOUT) { api.load(url) }

    fun playables(response: LoadResponse): List<Playable> = when (response) {
        is MovieLoadResponse -> listOf(Playable(response.name, response.dataUrl))
        is LiveStreamLoadResponse -> listOf(Playable(response.name, response.dataUrl))
        is TorrentLoadResponse -> listOfNotNull(response.magnet ?: response.torrent).map { Playable(response.name, it) }
        is TvSeriesLoadResponse -> response.episodes.map { it.toPlayable() }
        is AnimeLoadResponse -> response.episodes.flatMap { (dub, eps) ->
            eps.map { ep -> ep.toPlayable().let { it.copy(label = "[${dub.name}] ${it.label}") } }
        }
        else -> emptyList()
    }

    private fun com.lagradost.cloudstream3.Episode.toPlayable(): Playable {
        val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString("")
        return Playable(listOf(code, name ?: "").filter { it.isNotBlank() }.joinToString(" "), data, season, episode)
    }

    /** Collects every ExtractorLink and SubtitleFile the provider emits, best quality first. */
    suspend fun links(api: MainAPI, data: String): Links {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()
        withTimeoutOrNull(api.loadLinksTimeoutMs ?: DEFAULT_TIMEOUT) {
            api.loadLinks(
                data,
                false,
                { synchronized(subtitles) { subtitles += it } },
                { synchronized(links) { links += it } },
            )
        }
        return Links(
            synchronized(links) { links.distinctBy { it.url }.sortedByDescending { it.quality } },
            synchronized(subtitles) { subtitles.distinctBy { it.url } },
        )
    }
}
