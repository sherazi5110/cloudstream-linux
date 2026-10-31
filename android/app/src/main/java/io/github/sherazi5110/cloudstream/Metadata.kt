package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.imdbUrlToIdNullable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Metadata & integrations: fills in what extensions leave out (ratings, wide artwork, title
 * logos, cast photos, trailers, similar titles), matched by title, year and IMDb ID.
 *  - TMDB with the user's own free API key (v3 key or v4 read token);
 *  - Cinemeta (Stremio's free catalog) for IMDb ratings, no key;
 *  - AniList for anime scores and banners, no key.
 * Results (including "no match") are cached on disk for a week.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
object Metadata {
    data class Person(val name: String, val role: String?, val photo: String?)
    data class Similar(val title: String, val year: Int?, val poster: String?, val vote: Double?)
    data class Info(
        val title: String? = null,
        val tmdbId: Int? = null,
        val mediaType: String? = null,
        val tmdbVote: Double? = null,
        val tmdbVotes: Int? = null,
        val imdbId: String? = null,
        val imdbRating: Double? = null,
        val anilistScore: Double? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val overview: String? = null,
        val contentRating: String? = null,
        val cast: List<Person> = emptyList(),
        val trailer: String? = null,
        val similar: List<Similar> = emptyList(),
    )

    private val prefs get() = App.context.getSharedPreferences("metadata", 0)
    private fun flag(key: String, def: Boolean) = prefs.getString(key, null)?.let { it == "true" } ?: def
    private fun setFlag(key: String, v: Boolean) { prefs.edit().putString(key, v.toString()).apply(); changed() }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners += l }
    private fun changed() = listeners.forEach { it() }

    var tmdbKey: String
        get() = prefs.getString("tmdb_key", "")!!.trim()
        set(v) { prefs.edit().putString("tmdb_key", v.trim()).apply(); clearMemory(); changed() }
    var tmdbEnabled: Boolean get() = flag("tmdb", true); set(v) = setFlag("tmdb", v)
    var posterRatings: Boolean get() = flag("poster_ratings", true); set(v) = setFlag("poster_ratings", v)
    var artwork: Boolean get() = flag("artwork", true); set(v) = setFlag("artwork", v)
    var extras: Boolean get() = flag("extras", true); set(v) = setFlag("extras", v)
    var replaceExtension: Boolean get() = flag("replace", false); set(v) = setFlag("replace", v)
    var anilist: Boolean get() = flag("anilist", true); set(v) = setFlag("anilist", v)
    var cinemeta: Boolean get() = flag("cinemeta", true); set(v) = setFlag("cinemeta", v)
    /** TMDB language, e.g. "en-US", "ar-SA", "hi-IN". */
    var language: String
        get() = prefs.getString("language", null) ?: "en-US"
        set(v) { prefs.edit().putString("language", v).apply(); clearMemory(); changed() }

    val hasTmdb get() = tmdbEnabled && tmdbKey.isNotBlank()

    private val tmdbBase get() = System.getProperty("cs.tmdb.base") ?: "https://api.themoviedb.org/3"
    private val imageBase get() = System.getProperty("cs.tmdb.images") ?: "https://image.tmdb.org/t/p/"
    private val anilistBase get() = System.getProperty("cs.anilist") ?: "https://graphql.anilist.co"
    private val cinemetaBase get() = System.getProperty("cs.subtitles.meta") ?: "https://v3-cinemeta.strem.io"

    private val cacheDir get() = File(App.context.cacheDir, "metadata").apply { mkdirs() }
    private val permits = Semaphore(4)
    private val memory = object : LinkedHashMap<String, JSONObject>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>) = size > 2000
    }

    private fun clearMemory() = synchronized(memory) { memory.clear() }

    fun clearCache() {
        clearMemory()
        cacheDir.listFiles()?.forEach { it.delete() }
        changed()
    }

    fun cacheStats(): Pair<Int, Long> = cacheDir.listFiles().orEmpty().let { f -> f.size to f.sumOf { it.length() } }

    // ---------- HTTP ----------

    /** A v3 key is 32 hex characters; anything longer is a v4 read access token (Bearer). */
    private fun tmdb(path: String, params: Map<String, String> = emptyMap(), key: String = tmdbKey): JSONObject? {
        val v3 = key.matches(Regex("[0-9a-fA-F]{32}"))
        val query = (params + (if (v3) mapOf("api_key" to key) else emptyMap())).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val request = Request.Builder().url("$tmdbBase$path${if (query.isEmpty()) "" else "?$query"}").apply {
            if (!v3) header("Authorization", "Bearer $key")
            header("Accept", "application/json")
        }.build()
        app.baseClient.newCall(request).execute().use { r ->
            if (r.code == 401) throw SecurityException("TMDB refused the API key")
            if (!r.isSuccessful) return null
            return JSONObject(r.body.string())
        }
    }

    /** Checks a key; returns null when it works, else the reason. */
    suspend fun testKey(key: String): String? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext "Enter a key first"
        try {
            if (tmdb("/configuration", key = key.trim()) != null) null else "TMDB didn't answer; try again"
        } catch (e: SecurityException) {
            "That key doesn't work (TMDB said it's invalid)"
        } catch (e: Exception) {
            "Couldn't reach TMDB: ${e.message ?: e}"
        }
    }

    private fun image(path: String?, size: String) = path?.takeIf { it.isNotBlank() }?.let { "$imageBase$size$it" }

    // ---------- caching ----------

    private fun cacheKey(vararg parts: Any?) = MessageDigest.getInstance("SHA-1")
        .digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun cached(key: String, compute: suspend () -> JSONObject?): JSONObject? {
        synchronized(memory) { memory[key] }?.let { return it.takeIf { j -> !j.optBoolean("_none") } }
        val file = File(cacheDir, "$key.json")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L) {
            runCatching { JSONObject(file.readText()) }.getOrNull()?.let { j ->
                synchronized(memory) { memory[key] = j }
                return j.takeIf { !it.optBoolean("_none") }
            }
        }
        val value = withContext(Dispatchers.IO) { permits.withPermit { runCatching { compute() }.onFailure { android.util.Log.w("metadata", "$it") }.getOrNull() } }
        val stored = value ?: JSONObject().put("_none", true)
        synchronized(memory) { memory[key] = stored }
        runCatching { file.writeText(stored.toString()) }
        return value
    }

    // ---------- matching ----------

    private val releaseJunk = Regex(
        "\\b(web[- ]?dl|webrip|web|hdrip|brrip|bluray|blu-ray|dvdrip|hdtv|hdcam|camrip|hc|x264|x265|h\\.?264|h\\.?265|hevc|10bit|" +
            "2160p|1080p|720p|480p|4k|uhd|hdr|dd5\\.1|ddp5\\.1|aac|esub|esubs|dual audio|multi audio|hindi|tamil|telugu|english|org|dubbed|uncut|extended)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "Avaraachan & Sons (2026) WEB-DL [Hindi (DD5.1)]" → ("Avaraachan & Sons", 2026). */
    fun cleanTitle(raw: String): Pair<String, Int?> {
        val year = Regex("[(\\[ ](19\\d{2}|20\\d{2})[)\\] ]").find(" $raw ")?.groupValues?.get(1)?.toIntOrNull()
        var t = raw.replace(Regex("\\[[^]]*]|\\{[^}]*}"), " ")
        t = t.replace(Regex("\\((19|20)\\d{2}\\).*$"), " ")
        t = t.replace(Regex("\\b(19|20)\\d{2}\\b.*$"), " ").ifBlank { raw }
        t = t.replace(releaseJunk, " ").replace(Regex("\\b[Ss]\\d{1,2}([Ee]\\d{1,3})?\\b|\\bSeason \\d+\\b", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("[._]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', ':', '|')
        return (t.ifBlank { raw.trim() }) to year
    }

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace("&", "and").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun kindOf(type: TvType?): String? = when (type) {
        TvType.Movie, TvType.AnimeMovie, TvType.Torrent -> "movie"
        TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AsianDrama, TvType.OVA -> "tv"
        else -> null
    }

    private fun isAnime(type: TvType?) = type == TvType.Anime || type == TvType.AnimeMovie || type == TvType.OVA

    /** Best TMDB search hit for a title: exact name and year win; otherwise a close name. */
    private fun searchTmdb(title: String, year: Int?, kind: String?): JSONObject? {
        val path = when (kind) { "movie" -> "/search/movie"; "tv" -> "/search/tv"; else -> "/search/multi" }
        val params = mutableMapOf("query" to title, "language" to language, "include_adult" to "false")
        if (year != null) when (kind) { "movie" -> params["year"] = "$year"; "tv" -> params["first_air_date_year"] = "$year" }
        var results = tmdb(path, params)?.optJSONArray("results")
        if ((results == null || results.length() == 0) && year != null) results = tmdb(path, params - "year" - "first_air_date_year")?.optJSONArray("results")
        val list = results?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { kind != null || it.optString("media_type") in setOf("movie", "tv") }
        if (list.isEmpty()) return null
        val wanted = normalize(title)
        fun name(o: JSONObject) = o.optString("title").ifBlank { o.optString("name") }
        fun original(o: JSONObject) = o.optString("original_title").ifBlank { o.optString("original_name") }
        fun yearOf(o: JSONObject) = (o.optString("release_date").ifBlank { o.optString("first_air_date") }).take(4).toIntOrNull()
        val scored = list.map { o ->
            var score = 0
            if (normalize(name(o)) == wanted || normalize(original(o)) == wanted) score += 4
            else if (normalize(name(o)).contains(wanted) || wanted.contains(normalize(name(o)))) score += 1
            if (year != null && yearOf(o) != null && kotlin.math.abs(yearOf(o)!! - year) <= 1) score += 3
            o to score
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second >= 3 || (best.second >= 1 && list.size == 1) }
    }

    /** IMDb id → TMDB item through /find. */
    private fun findByImdb(imdb: String): JSONObject? {
        val found = tmdb("/find/$imdb", mapOf("external_source" to "imdb_id", "language" to language)) ?: return null
        found.optJSONArray("movie_results")?.optJSONObject(0)?.let { return it.put("media_type", "movie") }
        found.optJSONArray("tv_results")?.optJSONObject(0)?.let { return it.put("media_type", "tv") }
        return null
    }

    // ---------- choosing the match by hand ("Wrong match?") ----------

    data class Candidate(val id: Int, val mediaType: String, val title: String, val year: Int?, val poster: String?, val overview: String?)

    /** Films and series on TMDB for [query] ("Title 2019" narrows by year), best first. */
    suspend fun searchCandidates(query: String): List<Candidate> = withContext(Dispatchers.IO) {
        if (!hasTmdb) return@withContext emptyList()
        val (title, year) = cleanTitle(query)
        val params = mutableMapOf("query" to title, "language" to language, "include_adult" to "false")
        val results = tmdb("/search/multi", params)?.optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("media_type") in setOf("movie", "tv") }
            .map {
                Candidate(it.optInt("id"), it.optString("media_type"), it.optString("title").ifBlank { it.optString("name") },
                    (it.optString("release_date").ifBlank { it.optString("first_air_date") }).take(4).toIntOrNull(),
                    image(it.optString("poster_path"), "w185"), it.optString("overview").ifBlank { null })
            }
            .sortedByDescending { if (year != null && it.year != null && kotlin.math.abs(it.year - year) <= 1) 1 else 0 }
    }

    private fun matchKey(apiName: String, url: String) = "match:" + cacheKey(apiName, url)

    /** The TMDB entry chosen by hand for a title: ("movie"|"tv", id), (null, 0) for "no match", or null for automatic. */
    fun chosenMatch(apiName: String, url: String): Pair<String?, Int>? {
        val v = prefs.getString(matchKey(apiName, url), null)?.takeIf { it.isNotBlank() } ?: return null
        if (v == "none") return null to 0
        val (type, id) = v.split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: return null) }
        return type to id
    }

    /** Pins [c] as the match for a title; null c = "don't use TMDB for it"; [automatic] = forget the choice. */
    fun chooseMatch(apiName: String, url: String, c: Candidate?, automatic: Boolean = false) {
        prefs.edit().putString(matchKey(apiName, url), if (automatic) "" else c?.let { "${it.mediaType}:${it.id}" } ?: "none").apply()
        changed()
    }

    // ---------- ratings on posters ----------

    /** A short score for a poster card ("TMDB 7.9" / "AniList 8.4"), or null. Cached; at most 4 lookups at once. */
    suspend fun posterRating(item: SearchResponse): String? {
        if (!posterRatings) return null
        val (title, year) = cleanTitle(item.name)
        val y = year ?: (item as? com.lagradost.cloudstream3.MovieSearchResponse)?.year ?: (item as? com.lagradost.cloudstream3.TvSeriesSearchResponse)?.year
        if (hasTmdb) {
            val chosen = chosenMatch(item.apiName, item.url)
            if (chosen?.first != null) {
                val full = cached(cacheKey("vote", chosen.first, chosen.second)) { tmdb("/${chosen.first}/${chosen.second}", mapOf("language" to language)) }
                full?.optDouble("vote_average")?.takeIf { it > 0 }?.let { return "TMDB %.1f".format(it) }
            }
            val hit = if (chosen != null) null else cached(cacheKey("search", title, y, kindOf(item.type), language)) { searchTmdb(title, y, kindOf(item.type)) }
            hit?.optDouble("vote_average")?.takeIf { it > 0 && hit.optInt("vote_count") >= 3 }?.let { return "TMDB %.1f".format(it) }
        }
        if (anilist && isAnime(item.type)) {
            anilistMedia(title)?.optInt("averageScore")?.takeIf { it > 0 }?.let { return "AniList %.1f".format(it / 10.0) }
        }
        return null
    }

    // ---------- details ----------

    /** Everything known about a title from the enabled services, or null when nothing matched. */
    suspend fun details(r: LoadResponse): Info? = withContext(Dispatchers.IO) {
        val (title, cleanYear) = cleanTitle(r.name)
        val year = r.year ?: cleanYear
        var imdb = runCatching { r.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") } ?: imdbUrlToIdNullable(r.url)
        var info = Info(title = title, imdbId = imdb)

        if (hasTmdb && (artwork || extras || posterRatings)) {
            // A match chosen by hand ("Wrong match?") wins; "no match" skips TMDB for this title.
            val chosen = chosenMatch(r.apiName, r.url)
            val hit = when {
                chosen != null && chosen.first == null -> null
                chosen != null -> JSONObject().put("id", chosen.second).put("media_type", chosen.first)
                else -> imdb?.let { id -> cached(cacheKey("find", id, language)) { findByImdb(id) } }
                    ?: cached(cacheKey("search", title, year, kindOf(r.type), language)) { searchTmdb(title, year, kindOf(r.type)) }
            }
            if (hit != null) {
                val type = hit.optString("media_type").ifBlank { kindOf(r.type) ?: "movie" }
                val id = hit.optInt("id")
                val full = cached(cacheKey("full", type, id, language)) {
                    tmdb("/$type/$id", mapOf(
                        "language" to language,
                        "append_to_response" to "credits,videos,images,recommendations,external_ids," + if (type == "movie") "release_dates" else "content_ratings",
                        "include_image_language" to "${language.take(2)},en,null",
                    ))
                } ?: hit
                if (chosen != null) imdb = null // the extension's id may be what was wrong
                imdb = imdb ?: full.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") } ?: full.optString("imdb_id").takeIf { it.startsWith("tt") }
                val logos = full.optJSONObject("images")?.optJSONArray("logos")
                val logo = logos?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .sortedByDescending { if (it.optString("iso_639_1") == language.take(2)) 2 else if (it.optString("iso_639_1") == "en") 1 else 0 }
                    .firstOrNull { !it.optString("file_path").endsWith(".svg") }?.optString("file_path") }
                val cast = full.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
                    (0 until minOf(a.length(), 15)).mapNotNull { a.optJSONObject(it) }.map { Person(it.optString("name"), it.optString("character").ifBlank { null }, image(it.optString("profile_path"), "w185")) }
                }.orEmpty()
                val trailer = full.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("site") == "YouTube" }
                        .sortedByDescending { (if (it.optString("type") == "Trailer") 2 else 0) + (if (it.optBoolean("official")) 1 else 0) }
                        .firstOrNull()?.optString("key")?.let { "https://www.youtube.com/watch?v=$it" }
                }
                val similar = full.optJSONObject("recommendations")?.optJSONArray("results")?.let { a ->
                    (0 until minOf(a.length(), 20)).mapNotNull { a.optJSONObject(it) }.map {
                        Similar(it.optString("title").ifBlank { it.optString("name") }, (it.optString("release_date").ifBlank { it.optString("first_air_date") }).take(4).toIntOrNull(),
                            image(it.optString("poster_path"), "w342"), it.optDouble("vote_average").takeIf { v -> v > 0 })
                    }
                }.orEmpty()
                val rating = if (type == "movie") full.optJSONObject("release_dates")?.optJSONArray("results")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }
                        ?.optJSONArray("release_dates")?.let { d -> (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("certification") }.firstOrNull { it.isNotBlank() } }
                } else full.optJSONObject("content_ratings")?.optJSONArray("results")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }?.optString("rating")?.ifBlank { null }
                }
                info = info.copy(
                    title = full.optString("title").ifBlank { full.optString("name") }.ifBlank { title },
                    tmdbId = id, mediaType = type,
                    tmdbVote = full.optDouble("vote_average").takeIf { it > 0 }, tmdbVotes = full.optInt("vote_count").takeIf { it > 0 },
                    imdbId = imdb,
                    backdrop = image(full.optString("backdrop_path"), "w1280"),
                    logo = image(logo, "w500"),
                    overview = full.optString("overview").ifBlank { null },
                    contentRating = rating,
                    cast = cast, trailer = trailer, similar = similar,
                )
            }
        }
        if (cinemeta && imdb == null && r.type != TvType.Live) {
            imdb = runCatching { OnlineSubtitles.resolveImdb(OnlineSubtitles.Query(title, year, null, if (kindOf(r.type) == "tv") 1 else null, null)) }.getOrNull()
            info = info.copy(imdbId = imdb)
        }
        if (cinemeta && imdb != null) {
            val type = if ((info.mediaType ?: kindOf(r.type)) == "tv") "series" else "movie"
            val meta = cached(cacheKey("cinemeta", type, imdb)) {
                runCatching { JSONObject(app.get("$cinemetaBase/meta/$type/$imdb.json", timeout = 15).text).optJSONObject("meta") }.getOrNull()
            }
            info = info.copy(imdbRating = meta?.optString("imdbRating")?.toDoubleOrNull())
        }
        if (anilist && isAnime(r.type)) {
            anilistMedia(title)?.let { m ->
                info = info.copy(anilistScore = m.optInt("averageScore").takeIf { it > 0 }?.div(10.0),
                    backdrop = info.backdrop ?: m.optString("bannerImage").ifBlank { null },
                    overview = info.overview ?: m.optString("description").replace(Regex("<[^>]+>"), "").ifBlank { null })
            }
        }
        info.takeIf { it.tmdbId != null || it.imdbRating != null || it.anilistScore != null }
    }

    /** AniList's entry for an anime title (score, banner, description, MyAnimeList id). */
    internal suspend fun anilistMedia(title: String): JSONObject? = cached(cacheKey("anilist2", title)) {
        val query = "query(\$s:String){Media(search:\$s,type:ANIME){idMal averageScore bannerImage description(asHtml:false) title{romaji english}}}"
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("s", title)).toString()
        val request = Request.Builder().url(anilistBase).post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json").build()
        app.baseClient.newCall(request).execute().use { r -> if (r.isSuccessful) JSONObject(r.body.string()).optJSONObject("data")?.optJSONObject("Media") else null }
    }
}
