package com.cloudstreamlinux.archive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/** Public-domain feature films from the Internet Archive (archive.org). */
class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "feature_films" to "Most downloaded feature films",
        "film_noir" to "Film noir",
        "SciFi_Horror" to "Sci-fi & horror",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Doc(
        @JsonProperty("identifier") val identifier: String,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Docs(@JsonProperty("docs") val docs: List<Doc> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(@JsonProperty("response") val response: Docs? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ItemMeta(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: Any? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("date") val date: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ItemFile(
        @JsonProperty("name") val name: String,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("height") val height: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(
        @JsonProperty("metadata") val metadata: ItemMeta? = null,
        @JsonProperty("files") val files: List<ItemFile> = emptyList(),
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun query(q: String, sortByDownloads: Boolean): List<SearchResponse> {
        val url = "$mainUrl/advancedsearch.php?q=${enc(q)}&fl[]=identifier&fl[]=title&fl[]=year&rows=40&output=json" +
            if (sortByDownloads) "&sort[]=${enc("downloads desc")}" else ""
        val docs = app.get(url).parsedSafe<SearchResult>()?.response?.docs ?: return emptyList()
        return docs.map { doc ->
            newMovieSearchResponse(doc.title ?: doc.identifier, "$mainUrl/details/${doc.identifier}", TvType.Movie) {
                posterUrl = "$mainUrl/services/img/${doc.identifier}"
                year = doc.year?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = query("collection:(${request.data}) AND mediatype:(movies)", sortByDownloads = true)
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        query("collection:(feature_films) AND mediatype:(movies) AND title:(${query})", sortByDownloads = false)

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/details/").substringBefore("/")
        val item = app.get("$mainUrl/metadata/$id").parsedSafe<Item>() ?: return null
        val meta = item.metadata
        return newMovieLoadResponse(meta?.title ?: id, url, TvType.Movie, id) {
            posterUrl = "$mainUrl/services/img/$id"
            year = (meta?.year ?: meta?.date)?.take(4)?.toIntOrNull()
            plot = (meta?.description as? List<*>)?.joinToString("\n")?.let(::stripTags)
                ?: (meta?.description as? String)?.let(::stripTags)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = app.get("$mainUrl/metadata/$data").parsedSafe<Item>() ?: return false
        var found = false
        for (file in item.files) {
            val fileUrl = "$mainUrl/download/$data/${file.name.split('/').joinToString("/") { enc(it).replace("+", "%20") }}"
            when {
                file.name.endsWith(".mp4", true) || file.name.endsWith(".ogv", true) -> {
                    found = true
                    callback(newExtractorLink(name, "$name ${file.format ?: ""}".trim(), fileUrl, ExtractorLinkType.VIDEO) {
                        quality = file.height?.toIntOrNull() ?: Qualities.Unknown.value
                        referer = "$mainUrl/"
                    })
                }
                file.name.endsWith(".srt", true) || file.name.endsWith(".vtt", true) ->
                    subtitleCallback(newSubtitleFile(file.name.substringBeforeLast('.').substringAfterLast('.'), fileUrl))
            }
        }
        return found
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "").trim()
}
