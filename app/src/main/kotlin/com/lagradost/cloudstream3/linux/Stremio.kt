package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Stremio add-ons: plain HTTP + JSON (github.com/Stremio/stremio-addon-sdk, docs/protocol.md).
 *   <base>/manifest.json, <base>/catalog/<type>/<id>[/<extra>].json, <base>/meta/<type>/<id>.json,
 *   <base>/stream/<type>/<id>.json, <base>/subtitles/<type>/<id>.json
 *
 * Each installed add-on with catalogs becomes a [MainAPI] ([StremioApi]), so Home, Search,
 * the catalog editor, details and the player work with it unchanged. As in Stremio, streams
 * come from every enabled add-on that serves streams for the title, not only the one it
 * was found in. Torrent-only streams (infoHash) aren't playable here and are skipped.
 */
object StremioAddons {
    /** Cinemeta, Stremio's own catalog of films and series (metadata only, no streams). */
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    data class Addon(val url: String, val manifest: Manifest, val enabled: Boolean) {
        val base get() = url.removeSuffix("/manifest.json")
    }

    data class Catalog(val type: String, val id: String, val name: String, val searchable: Boolean, val needsExtra: Boolean)

    data class Resource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

    data class Manifest(
        val id: String, val name: String, val version: String, val description: String, val logo: String?,
        val types: List<String>, val idPrefixes: List<String>?, val catalogs: List<Catalog>, val resources: List<Resource>,
    ) {
        /** Whether this add-on answers [resource] requests for an item of [type] with [id]. */
        fun serves(resource: String, type: String, id: String): Boolean {
            val r = resources.firstOrNull { it.name == resource } ?: return false
            val types = r.types ?: this.types
            val prefixes = r.idPrefixes ?: idPrefixes
            return type in types && (prefixes == null || prefixes.any { id.startsWith(it) })
        }

        companion object {
            fun parse(json: JSONObject): Manifest {
                fun strings(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                val catalogs = json.optJSONArray("catalogs")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { c ->
                        val extras = c.optJSONArray("extra")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it) } }.orEmpty()
                        val legacyRequired = strings(c.optJSONArray("extraRequired")).orEmpty()
                        val names = extras.map { it.optString("name") } + strings(c.optJSONArray("extraSupported")).orEmpty()
                        Catalog(
                            type = c.optString("type"), id = c.optString("id"),
                            name = c.optString("name").ifBlank { c.optString("id") },
                            searchable = "search" in names,
                            // A catalog that only works with e.g. a search term can't be a Home row.
                            needsExtra = extras.any { it.optBoolean("isRequired") } || legacyRequired.isNotEmpty(),
                        )
                    }
                }.orEmpty()
                val resources = json.optJSONArray("resources")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        when (val r = arr.get(i)) {
                            is JSONObject -> Resource(r.optString("name"), strings(r.optJSONArray("types")), strings(r.optJSONArray("idPrefixes")))
                            else -> Resource(r.toString(), null, null)
                        }
                    }
                }.orEmpty()
                return Manifest(
                    id = json.getString("id"), name = json.optString("name", json.getString("id")),
                    version = json.optString("version"), description = json.optString("description"),
                    logo = json.optString("logo").takeIf { it.startsWith("http") },
                    types = strings(json.optJSONArray("types")).orEmpty(),
                    idPrefixes = strings(json.optJSONArray("idPrefixes")), catalogs = catalogs, resources = resources,
                )
            }
        }
    }

    private val prefs get() = Platform.context.getSharedPreferences("stremio_addons", 0)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val apis = mutableMapOf<String, StremioApi>()

    fun onChange(listener: () -> Unit) { listeners += listener }

    private fun changed() {
        listeners.forEach { it() }
        PluginManager.bumpVersion()
    }

    fun all(): List<Addon> {
        val arr = runCatching { JSONArray(prefs.getString("addons", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            runCatching { Addon(o.getString("url"), Manifest.parse(o.getJSONObject("manifest")), o.optBoolean("enabled", true)) }.getOrNull()
        }
    }

    private fun save(list: List<Pair<Addon, JSONObject>>) {
        val arr = JSONArray()
        list.forEach { (a, manifest) -> arr.put(JSONObject().put("url", a.url).put("manifest", manifest).put("enabled", a.enabled)) }
        prefs.edit().putString("addons", arr.toString()).apply()
        synchronized(apis) { apis.clear() }
        changed()
    }

    private fun stored(): List<Pair<Addon, JSONObject>> {
        val arr = runCatching { JSONArray(prefs.getString("addons", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            runCatching { Addon(o.getString("url"), Manifest.parse(o.getJSONObject("manifest")), o.optBoolean("enabled", true)) to o.getJSONObject("manifest") }.getOrNull()
        }
    }

    /** "stremio://host/x/manifest.json" or a base URL → https://host/x/manifest.json. */
    fun normalize(input: String): String {
        var url = input.trim()
        if (url.startsWith("stremio://")) url = "https://" + url.removePrefix("stremio://")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        url = url.substringBefore('#').trimEnd('/')
        if (!url.endsWith("/manifest.json")) url += "/manifest.json"
        return url
    }

    /** Downloads and installs (or updates) an add-on; throws with a readable message when it isn't one. */
    suspend fun install(input: String): Addon {
        val url = normalize(input)
        val response = app.get(url, timeout = 20)
        if (!response.isSuccessful) throw IllegalArgumentException("The address answered HTTP ${response.code}")
        val json = runCatching { JSONObject(response.text) }.getOrNull()
            ?: throw IllegalArgumentException("That address doesn't serve a Stremio manifest")
        val manifest = runCatching { Manifest.parse(json) }.getOrNull()
            ?: throw IllegalArgumentException("The manifest is missing its id")
        if (manifest.resources.isEmpty() && manifest.catalogs.isEmpty()) throw IllegalArgumentException("This add-on offers nothing to use")
        val addon = Addon(url, manifest, true)
        save(stored().filterNot { it.first.url == url || it.first.manifest.id == manifest.id } + (addon to json))
        return addon
    }

    fun remove(url: String) = save(stored().filterNot { it.first.url == url })

    fun setEnabled(url: String, enabled: Boolean) = save(stored().map { (a, j) -> if (a.url == url) a.copy(enabled = enabled) to j else a to j })

    /** One [MainAPI] per enabled add-on that has catalogs or metadata. */
    fun apis(): List<MainAPI> = all().filter { a ->
        // TV channel catalogs belong to Live TV ([LiveTv]); only films, series etc. become a Home/Search source.
        a.enabled && (a.manifest.catalogs.any { !LiveTv.isTvType(it.type) } || (a.manifest.resources.any { r -> r.name == "meta" } && a.manifest.types.any { !LiveTv.isTvType(it) }))
    }
        .map { a -> synchronized(apis) { apis.getOrPut(a.url) { StremioApi(a) } } }

    fun enabled(): List<Addon> = all().filter { it.enabled }

    internal fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal suspend fun json(url: String): JSONObject? = runCatching {
        val r = app.get(url, timeout = 20)
        if (r.isSuccessful) JSONObject(r.text) else null
    }.onFailure { System.err.println("W/stremio: $url: $it") }.getOrNull()

    fun tvType(type: String): TvType = when (type) {
        "movie" -> TvType.Movie
        "series" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "tv", "channel" -> TvType.Live
        else -> TvType.Others
    }
}

/** A Stremio add-on presented as a CloudStream extension. Item urls are "<base>/meta/<type>/<id>". */
class StremioApi(private val addon: StremioAddons.Addon) : MainAPI() {
    private val manifest = addon.manifest
    override var name = manifest.name
    override var mainUrl = addon.base
    override var lang = "en"
    /** Film/series catalogs only: TV channel catalogs are shown in Live TV. */
    private val catalogs = manifest.catalogs.filter { !LiveTv.isTvType(it.type) }
    override val hasMainPage = catalogs.any { !it.needsExtra }
    override val supportedTypes = manifest.types.filter { !LiveTv.isTvType(it) }.map(StremioAddons::tvType).toSet()
    override val mainPage = catalogs.filter { !it.needsExtra }.map {
        MainPageData("${it.name} · ${typeLabel(it.type)}", "${it.type}/${it.id}")
    }

    private val pageSizes = mutableMapOf<String, Int>()

    private fun typeLabel(type: String) = when (type) {
        "movie" -> "Movies"; "series" -> "Series"; "tv", "channel" -> "TV"; else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun toSearch(meta: JSONObject, fallbackType: String): SearchResponse? {
        val id = meta.optString("id").takeIf { it.isNotBlank() } ?: return null
        val type = meta.optString("type").ifBlank { fallbackType }
        return newMovieSearchResponse(meta.optString("name", id), "${addon.base}/meta/$type/${StremioAddons.encode(id)}", StremioAddons.tvType(type)) {
            posterUrl = meta.optString("poster").takeIf { it.startsWith("http") }
            year = meta.optString("releaseInfo").take(4).toIntOrNull()
            score = meta.optString("imdbRating").toDoubleOrNull()?.let { Score.from10(it) }
        }
    }

    private fun metas(json: JSONObject?, type: String) =
        json?.optJSONArray("metas")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { toSearch(it, type) } }.orEmpty()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val (type, id) = request.data.split('/', limit = 2).let { it[0] to it[1] }
        // Stremio pages with ?skip=; page sizes vary per add-on, so use the first page's size.
        val size = pageSizes[request.data] ?: 100
        val extra = if (page > 1) "/skip=${(page - 1) * size}" else ""
        val items = metas(StremioAddons.json("${addon.base}/catalog/$type/${StremioAddons.encode(id)}$extra.json"), type)
        if (page == 1 && items.isNotEmpty()) pageSizes[request.data] = items.size
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && page < 50)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        catalogs.filter { it.searchable }.map { c ->
            async {
                metas(StremioAddons.json("${addon.base}/catalog/${c.type}/${StremioAddons.encode(c.id)}/search=${StremioAddons.encode(query)}.json"), c.type)
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val rest = url.substringAfter("/meta/", "")
        val type = rest.substringBefore('/')
        val id = java.net.URLDecoder.decode(rest.substringAfter('/'), "UTF-8")
        // Metadata from this add-on if it has it, else from any other enabled add-on (e.g. Cinemeta for IMDb ids).
        val sources = listOf(addon) + StremioAddons.enabled().filter { it.url != addon.url }
        val meta = sources.filter { it.manifest.serves("meta", type, id) }.firstNotNullOfOrNull {
            StremioAddons.json("${it.base}/meta/$type/${StremioAddons.encode(id)}.json")?.optJSONObject("meta")
        } ?: return null
        val name = meta.optString("name", id)
        val videos = meta.optJSONArray("videos")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
        fun JSONObject.list(key: String) = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val fill: LoadResponse.() -> Unit = {
            posterUrl = meta.optString("poster").takeIf { it.startsWith("http") }
            backgroundPosterUrl = meta.optString("background").takeIf { it.startsWith("http") }
            logoUrl = meta.optString("logo").takeIf { it.startsWith("http") }
            plot = meta.optString("description").ifBlank { null }
            year = meta.optString("releaseInfo").take(4).toIntOrNull() ?: meta.optString("year").take(4).toIntOrNull()
            score = meta.optString("imdbRating").toDoubleOrNull()?.let { Score.from10(it) }
            tags = meta.list("genres") ?: meta.list("genre")
            duration = meta.optString("runtime").filter { it.isDigit() }.toIntOrNull()
            contentRating = meta.optString("certification").ifBlank { null }
            addActors(meta.list("cast")?.take(15))
            (meta.optString("imdb_id").takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") })?.let { addImdbId(it) }
        }
        val isSeries = type != "movie" && videos.any { it.has("season") || it.has("episode") }
        if (!isSeries) {
            // A single video (or none: then the item id itself is the video id).
            val videoId = videos.singleOrNull()?.optString("id")?.ifBlank { null } ?: id
            return newMovieLoadResponse(name, url, StremioAddons.tvType(type), video(type, videoId)) { fill() }
        }
        val episodes: List<Episode> = videos.map { v ->
            newEpisode(video(type, v.optString("id"))) {
                this.name = v.optString("name").ifBlank { v.optString("title") }.ifBlank { null }
                season = v.optInt("season").takeIf { it > 0 }
                episode = v.optInt("episode").takeIf { it > 0 } ?: v.optInt("number").takeIf { it > 0 }
                posterUrl = v.optString("thumbnail").takeIf { it.startsWith("http") }
                description = v.optString("overview").ifBlank { v.optString("description") }.ifBlank { null }
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(name, url, StremioAddons.tvType(type), episodes) { fill() }
    }

    private fun video(type: String, id: String) = JSONObject().put("type", type).put("id", id).toString()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = coroutineScope {
        val json = JSONObject(data)
        val type = json.getString("type")
        val id = json.getString("id")
        var found = false
        val addons = StremioAddons.enabled()
        val jobs = addons.filter { it.manifest.serves("stream", type, id) }.map { a ->
            async {
                val streams = withTimeoutOrNull(30_000) { StremioAddons.json("${a.base}/stream/$type/${StremioAddons.encode(id)}.json") }
                    ?.optJSONArray("streams") ?: return@async
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val link = s.optString("url").takeIf { it.startsWith("http") } ?: continue // infoHash/ytId/externalUrl: not playable here
                    val hints = s.optJSONObject("behaviorHints")
                    val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                        ?.let { h -> h.keySet().associateWith { h.optString(it) } }.orEmpty()
                    val title = listOf(s.optString("name"), s.optString("title").ifBlank { s.optString("description") })
                        .filter { it.isNotBlank() }.joinToString(" · ").replace('\n', ' ')
                    val linkType = if (link.substringBefore('?').endsWith(".m3u8")) ExtractorLinkType.M3U8 else null
                    callback(newExtractorLink(a.manifest.name, title.ifBlank { a.manifest.name }, link, linkType) {
                        this.headers = headers
                        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { referer = it.value }
                        quality = getQualityFromName(Regex("(2160|1440|1080|720|480|360)p", RegexOption.IGNORE_CASE).find(title)?.value)
                    })
                    found = true
                }
            }
        } + addons.filter { it.manifest.serves("subtitles", type, id) }.map { a ->
            async {
                val subs = StremioAddons.json("${a.base}/subtitles/$type/${StremioAddons.encode(id)}.json")?.optJSONArray("subtitles") ?: return@async
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val link = s.optString("url").takeIf { it.startsWith("http") } ?: continue
                    subtitleCallback(newSubtitleFile(OnlineSubtitles.languageName(s.optString("lang", "und")), link))
                }
            }
        }
        jobs.awaitAll()
        found
    }
}
