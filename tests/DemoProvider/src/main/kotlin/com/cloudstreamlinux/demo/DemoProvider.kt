package com.cloudstreamlinux.demo

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Offline catalogue backed by tests/mock-archive/server.py. Its loadLinks behaves like a
 * real provider with several extractors: one answers at once, one after 8 s, and one
 * host never answers, which the app must survive without getting stuck.
 */
class DemoProvider : MainAPI() {
    override var mainUrl = "http://localhost:8766"
    override var name = "Demo"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("series" to "Trending series", "movies" to "Popular movies", "classics" to "Silent classics")

    private class Title(val id: String, val name: String, val year: Int, val series: Boolean, val plot: String, val score: Double)

    private val titles = listOf(
        Title("pixel_city", "Pixel City", 2024, true, "In a city rendered one scanline at a time, a detective chases a missing frame through the compression underworld.", 8.4),
        Title("signal_lost", "Signal Lost", 2025, true, "A night-shift engineer keeps a dying broadcast tower alive while strange test patterns start answering back.", 7.9),
        Title("night_of_the_test_pattern", "Night of the Test Pattern", 1968, false, "Color bars rise from their graves.", 7.1),
        Title("the_general_test", "The General Test", 1926, false, "A silent comedy about color bars.", 8.1),
        Title("nosferatest", "Nosferatest", 1922, false, "A vampire haunts an empty broadcast schedule.", 7.8),
        Title("metropolis_bars", "Metropolis Bars", 1927, false, "A city built entirely out of SMPTE color bars.", 8.3),
        Title("his_girl_pixel", "His Girl Pixel", 1940, false, "Fast-talking editors argue over compression artifacts.", 7.6),
        Title("charade_signal", "Charade Signal", 1963, false, "Nobody is who they seem, least of all the carrier wave.", 7.7),
        Title("detour_noise", "Detour Noise", 1945, false, "A hitchhiker meets white noise on a lonely highway.", 7.2),
        Title("carnival_of_tones", "Carnival of Tones", 1962, false, "A 440 Hz sine wave will not stop following her.", 7.0),
        Title("the_kid_frame", "The Kid Frame", 1921, false, "A tramp raises an orphaned keyframe.", 8.2),
        Title("sherlock_jr_sync", "Sherlock Jr. Sync", 1924, false, "A projectionist dreams his way into an out-of-sync reel.", 8.0),
    )

    private fun poster(id: String) = "$mainUrl/media/poster_$id.jpg"

    private fun toSearch(t: Title): SearchResponse = if (t.series) {
        newTvSeriesSearchResponse(t.name, "$mainUrl/title/${t.id}", TvType.TvSeries) {
            posterUrl = poster(t.id); score = Score.from10(t.score); quality = SearchQuality.HD
        }
    } else {
        newMovieSearchResponse(t.name, "$mainUrl/title/${t.id}", TvType.Movie) {
            posterUrl = poster(t.id); year = t.year; score = Score.from10(t.score)
            quality = if (t.year > 1950) SearchQuality.HD else SearchQuality.SD
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = when (request.data) {
            "series" -> titles.filter { it.series }
            "movies" -> titles.filter { !it.series && it.year >= 1940 }
            else -> titles.filter { !it.series && it.year < 1940 }
        }
        return newHomePageResponse(request.name, list.map { toSearch(it) }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        titles.filter { it.name.contains(query, ignoreCase = true) }.map { toSearch(it) }

    override suspend fun load(url: String): LoadResponse? {
        val t = titles.find { url.endsWith("/${it.id}") } ?: return null
        val recommendations = titles.filter { it.id != t.id && it.series == t.series }.map { toSearch(it) }
        if (t.series) {
            val episodes = (1..2).flatMap { season ->
                (1..(if (season == 1) 6 else 4)).map { e ->
                    newEpisode("${t.id}|$season|$e") {
                        name = listOf("Pilot", "Dead Air", "Interlaced", "Keyframe", "Bitrate", "Static", "Afterglow", "Vertical Hold", "Snow", "Sign Off")[(season - 1) * 6 + e - 1]
                        this.season = season
                        episode = e
                        posterUrl = "$mainUrl/media/ep_${(e - 1) % 6 + 1}.jpg"
                        description = "Season $season, episode $e of ${t.name}."
                    }
                }
            }
            return newTvSeriesLoadResponse(t.name, url, TvType.TvSeries, episodes) {
                posterUrl = poster(t.id); backgroundPosterUrl = "$mainUrl/media/backdrop_${t.id}.jpg"
                plot = t.plot; year = t.year; score = Score.from10(t.score)
                tags = listOf("Sci-Fi", "Mystery", "Drama")
                this.recommendations = recommendations
                addActors(listOf("Ada Scanline", "Vic Raster", "Lena Chroma"))
            }
        }
        return newMovieLoadResponse(t.name, url, TvType.Movie, "${t.id}|0|0") {
            posterUrl = poster(t.id); backgroundPosterUrl = "$mainUrl/media/backdrop_${t.id}.jpg"
            plot = t.plot; year = t.year; score = Score.from10(t.score); duration = 94
            tags = listOf("Classic", "Public domain")
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val id = data.substringBefore('|')
        val file = "$mainUrl/download/night_of_the_test_pattern/test_film_640.mp4"
        subtitleCallback(newSubtitleFile("English", "$mainUrl/download/night_of_the_test_pattern/test_film.en.srt"))
        // Extractor 1: instant.
        callback(newExtractorLink("FastHost", "FastHost 720p", file, ExtractorLinkType.VIDEO) { quality = 720; referer = "$mainUrl/" })
        // Extractor 2: a slow host that answers after 8 seconds.
        launch {
            app.get("$mainUrl/slow?s=8")
            callback(newExtractorLink("SlowHost", "SlowHost 1080p", "$file?slow=$id", ExtractorLinkType.VIDEO) { quality = 1080; referer = "$mainUrl/" })
        }
        // Extractor 3: a dead host that never answers.
        launch {
            app.get("$mainUrl/hang")
            callback(newExtractorLink("DeadHost", "DeadHost", "$file?dead", ExtractorLinkType.VIDEO) { quality = 2160 })
        }
        true
    }
}
