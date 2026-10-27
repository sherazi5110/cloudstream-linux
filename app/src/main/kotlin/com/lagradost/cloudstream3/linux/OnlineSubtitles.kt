package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.imdbUrlToIdNullable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Subtitles from the internet, through the open Stremio addon protocol
 * (`GET <addon>/subtitles/<movie|series>/<imdb id>[:season:episode].json`), the same
 * way Stremio and the other CloudStream desktop app fetch them:
 *  - files: the free OpenSubtitles v3 addon (no account or API key needed);
 *  - IMDb ids: from the extension when it has one, otherwise a Cinemeta title search.
 */
object OnlineSubtitles {
    data class Result(val id: String, val url: String, val language: String) {
        val languageName: String get() = languageName(language)
    }

    data class Query(val title: String, val year: Int?, val imdbId: String?, val season: Int?, val episode: Int?) {
        val isSeries get() = season != null || episode != null
    }

    private val subtitlesAddon get() = System.getProperty("cs.subtitles.addon") ?: "https://opensubtitles-v3.strem.io"
    private val metaAddon get() = System.getProperty("cs.subtitles.meta") ?: "https://v3-cinemeta.strem.io"

    fun query(response: LoadResponse, season: Int?, episode: Int?): Query {
        val imdb = runCatching { response.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
            ?: imdbUrlToIdNullable(response.url)
        return Query(response.name, response.year, imdb, season, episode)
    }

    suspend fun search(query: Query): List<Result> = withContext(Dispatchers.IO) {
        val imdb = query.imdbId ?: resolveImdb(query) ?: throw NoSuchElementException("Couldn't identify “${query.title}” on IMDb")
        val type = if (query.isSeries) "series" else "movie"
        val videoId = if (query.isSeries) "$imdb:${query.season ?: 1}:${query.episode ?: 1}" else imdb
        val url = "$subtitlesAddon/subtitles/$type/${encode(videoId)}.json"
        val list = JSONObject(app.get(url, timeout = 15).text).optJSONArray("subtitles") ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val link = o.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Result(o.optString("id", link), link, o.optString("lang", "und"))
        }.distinctBy { it.url }
    }

    /** Cinemeta catalog search; picks the closest title (and year, when known). */
    suspend fun resolveImdb(query: Query): String? = withContext(Dispatchers.IO) {
        val type = if (query.isSeries) "series" else "movie"
        val url = "$metaAddon/catalog/$type/top/search=${encode(query.title)}.json"
        val metas = runCatching { JSONObject(app.get(url, timeout = 15).text).optJSONArray("metas") }.getOrNull()
            ?: return@withContext null
        val wanted = normalize(query.title)
        val candidates = (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }
            .filter { it.optString("id").startsWith("tt") || it.optString("imdb_id").startsWith("tt") }
        fun id(o: JSONObject) = o.optString("imdb_id").takeIf { it.startsWith("tt") } ?: o.optString("id")
        fun year(o: JSONObject) = o.optString("releaseInfo").take(4).toIntOrNull() ?: o.optString("year").take(4).toIntOrNull()
        val exact = candidates.filter { normalize(it.optString("name")) == wanted }
        val best = exact.firstOrNull { query.year == null || year(it) == query.year }
            ?: exact.firstOrNull()
            ?: candidates.firstOrNull { query.year != null && year(it) == query.year && normalize(it.optString("name")).contains(wanted) }
        best?.let(::id)
    }

    /** Downloads a subtitle into the cache and returns the file (the player wants a local file). */
    suspend fun download(result: Result): File = withContext(Dispatchers.IO) {
        val dir = File(AppDirs.data, "cache/subtitles").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(result.url.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$name.${result.language}.srt")
        if (!file.isFile || file.length() == 0L) {
            val response = app.get(result.url, timeout = 30)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            file.writeBytes(response.body.bytes())
        }
        file
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** OpenSubtitles uses ISO 639-2 codes ("eng", "pob" for Brazilian Portuguese). */
    fun languageName(code: String): String = when (code.lowercase()) {
        "pob", "pt-br" -> "Portuguese (Brazil)"
        "scc" -> "Serbian"
        "chi", "zho" -> "Chinese"
        "ze" -> "Chinese (bilingual)"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code }
            ?: Locale.getISOLanguages().firstOrNull { Locale(it).isO3Language == code.lowercase() }
                ?.let { Locale(it).getDisplayLanguage(Locale.ENGLISH) }
            ?: code
    }

    /** Whether an addon language code matches a user setting such as "en", "eng" or "English". */
    fun matches(code: String, wanted: String): Boolean {
        if (wanted.isBlank()) return true
        val w = wanted.trim().lowercase()
        val iso3 = runCatching { Locale(w).isO3Language }.getOrNull()
        return code.lowercase() == w || code.lowercase() == iso3 || languageName(code).lowercase() == w
    }
}
