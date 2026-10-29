package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Live TV: channel lists from IPTV sources, kept apart from Home and Search.
 *  - M3U playlists (by address or as a file), with an optional XMLTV guide;
 *  - Xtream Codes logins (player_api.php), the format most IPTV services use;
 *  - Stremio (and Nuvio) add-ons: their "tv"/"channel" catalogs. Add-ons installed on the
 *    Extensions page that offer TV channels show up here automatically.
 * Channels play in the built-in player through [LiveApi].
 */
object LiveTv {
    enum class Kind(val label: String) { M3U("M3U playlist"), XTREAM("Xtream Codes"), STREMIO("Stremio add-on") }

    data class Source(
        val id: String, val kind: Kind, val name: String, val url: String,
        val epgUrl: String? = null, val user: String? = null, val pass: String? = null, val enabled: Boolean = true,
    )

    data class Channel(
        val key: String, val source: String, val name: String, val logo: String?, val group: String?,
        val url: String, val headers: Map<String, String> = emptyMap(), val epgId: String? = null,
        val country: String? = null, val number: Int = 0,
    )

    data class Programme(val start: Long, val stop: Long, val title: String, val description: String?)

    private val prefs get() = Platform.context.getSharedPreferences("livetv_sources", 0)
    /** Favourites and recently watched belong to the profile. */
    private val mine get() = Platform.context.getSharedPreferences(Profiles.file("livetv"), 0)
    private val cacheDir get() = File(AppDirs.data, "cache/livetv").apply { mkdirs() }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() = listeners.forEach { it() }

    // ---------- sources ----------

    /** Sources added on the Live TV sources page, plus Stremio add-ons that offer TV catalogs. */
    fun sources(): List<Source> = saved() + StremioAddons.all().filter { a -> a.manifest.catalogs.any { isTvType(it.type) } }.map { a ->
        Source("stremio:" + a.manifest.id, Kind.STREMIO, a.manifest.name, a.url, enabled = a.enabled)
    }

    fun saved(): List<Source> {
        val arr = runCatching { JSONArray(prefs.getString("sources", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            runCatching {
                Source(o.getString("id"), Kind.valueOf(o.getString("kind")), o.getString("name"), o.getString("url"),
                    o.optString("epg").ifBlank { null }, o.optString("user").ifBlank { null }, o.optString("pass").ifBlank { null },
                    o.optBoolean("enabled", true))
            }.getOrNull()
        }
    }

    private fun save(list: List<Source>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("kind", s.kind.name).put("name", s.name).put("url", s.url)
                .put("epg", s.epgUrl ?: "").put("user", s.user ?: "").put("pass", s.pass ?: "").put("enabled", s.enabled))
        }
        prefs.edit().putString("sources", arr.toString()).apply()
        synchronized(channels) { channels.clear() }
        changed()
    }

    /** Adds an M3U or Xtream source after checking it answers; returns it with its channel count. */
    suspend fun add(kind: Kind, url: String, name: String? = null, epgUrl: String? = null, user: String? = null, pass: String? = null): Pair<Source, Int> {
        if (kind == Kind.STREMIO) {
            // A TV add-on is an ordinary Stremio add-on; its TV catalogs are routed here.
            val addon = StremioAddons.install(url)
            if (addon.manifest.catalogs.none { isTvType(it.type) }) throw IllegalArgumentException("${addon.manifest.name} has no TV channel catalogs (it was added to Extensions → Stremio add-ons)")
            val source = sources().first { it.id == "stremio:" + addon.manifest.id }
            return source to load(source, force = true).size
        }
        val clean = rawGithub(url.trim().trimEnd('/'))
        if (!clean.startsWith("http://") && !clean.startsWith("https://") && !clean.startsWith("file:")) throw IllegalArgumentException("Enter an address starting with http:// or https://")
        val id = MessageDigest.getInstance("SHA-1").digest("$kind|$clean|$user".toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val label = name?.takeIf { it.isNotBlank() }
            ?: if (clean.startsWith("file:")) File(java.net.URI(clean)).nameWithoutExtension else java.net.URI(clean).host?.removePrefix("www.") ?: "Playlist"
        val source = Source(id, kind, label, clean, epgUrl?.trim()?.ifBlank { null }, user?.trim(), pass)
        val list = fetch(source)
        if (list.isEmpty()) throw IllegalArgumentException("No channels found at that address")
        save(saved().filterNot { it.id == id } + source)
        store(source, list)
        return source to list.size
    }

    fun remove(id: String) {
        if (id.startsWith("stremio:")) StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == id }?.let { StremioAddons.remove(it.url) }
        else save(saved().filterNot { it.id == id })
        File(cacheDir, "$id.json").delete()
        changed()
    }

    fun setEnabled(id: String, on: Boolean) {
        if (id.startsWith("stremio:")) StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == id }?.let { StremioAddons.setEnabled(it.url, on) }
        else save(saved().map { if (it.id == id) it.copy(enabled = on) else it })
        changed()
    }

    var refreshDaily: Boolean
        get() = prefs.getString("refresh_daily", null) != "false"
        set(v) { prefs.edit().putString("refresh_daily", v.toString()).apply() }

    /** Kids profiles see Live TV only when allowed, and then only channels in child-friendly groups. */
    var allowKids: Boolean
        get() = prefs.getString("allow_kids", null) == "true"
        set(v) { prefs.edit().putString("allow_kids", v.toString()).apply(); changed() }

    private val kidsGroups = Regex("kid|child|cartoon|anim|family|junior|enfant|infantil|niñ|kinder", RegexOption.IGNORE_CASE)

    val availableToProfile: Boolean get() = !Profiles.isKids || allowKids

    fun isTvType(type: String) = type == "tv" || type == "channel"

    // ---------- channels ----------

    private val channels = mutableMapOf<String, List<Channel>>()

    /** All channels of the enabled sources (cached on disk; refreshed once a day when allowed). */
    suspend fun channels(sourceId: String? = null, force: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        val chosen = sources().filter { it.enabled && (sourceId == null || it.id == sourceId) }
        val all = coroutineScope { chosen.map { s -> async { runCatching { load(s, force) }.onFailure { System.err.println("W/livetv: ${s.name}: $it") }.getOrDefault(emptyList()) } }.awaitAll() }.flatten()
        if (Profiles.isKids) all.filter { kidsGroups.containsMatchIn(it.group.orEmpty()) || kidsGroups.containsMatchIn(it.name) } else all
    }

    private suspend fun load(source: Source, force: Boolean): List<Channel> {
        if (!force) synchronized(channels) { channels[source.id] }?.let { return it }
        val file = File(cacheDir, "${source.id}.json")
        val fresh = file.isFile && (!refreshDaily || System.currentTimeMillis() - file.lastModified() < 24 * 3600_000L)
        if (!force && fresh) readCache(file)?.let { list -> synchronized(channels) { channels[source.id] = list }; return list }
        val list = runCatching { fetch(source) }.getOrElse { e ->
            // Offline or the server is down: keep showing the last list.
            readCache(file)?.also { System.err.println("W/livetv: ${source.name}: $e (using cached list)") } ?: throw e
        }
        store(source, list)
        return list
    }

    private fun store(source: Source, list: List<Channel>) {
        synchronized(channels) { channels[source.id] = list }
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("k", c.key).put("s", c.source).put("n", c.name).put("l", c.logo ?: "").put("g", c.group ?: "")
                .put("u", c.url).put("h", JSONObject(c.headers)).put("e", c.epgId ?: "").put("c", c.country ?: "").put("o", c.number))
        }
        runCatching { File(cacheDir, "${source.id}.json").writeText(arr.toString()) }
    }

    private fun readCache(file: File): List<Channel>? = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val h = o.optJSONObject("h")
            Channel(o.getString("k"), o.getString("s"), o.getString("n"), o.optString("l").ifBlank { null }, o.optString("g").ifBlank { null },
                o.getString("u"), h?.keySet()?.associateWith { h.getString(it) }.orEmpty(), o.optString("e").ifBlank { null },
                o.optString("c").ifBlank { null }, o.optInt("o"))
        }
    }.getOrNull()

    private suspend fun fetch(source: Source): List<Channel> = when (source.kind) {
        Kind.M3U -> text(source.url).let { body ->
            val head = body.trimStart().take(200).lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html") || (head.startsWith("<") && "#extinf" !in body.take(20000).lowercase()))
                throw IllegalArgumentException("That address is a web page, not a playlist. Use the playlist file's own address (it usually ends in .m3u or .m3u8; on GitHub, the \"Raw\" button).")
            parseM3u(source.id, body)
        }
        Kind.XTREAM -> xtream(source)
        Kind.STREMIO -> stremio(source)
    }

    /** GitHub page links to a file ("…/blob/…") become the file itself (raw.githubusercontent.com). */
    fun rawGithub(url: String): String {
        val m = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url) ?: return url
        return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
    }

    private fun text(url: String): String {
        if (url.startsWith("file:")) {
            val bytes = File(java.net.URI(url)).readBytes()
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
        }
        val r = app.baseClient.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", com.lagradost.cloudstream3.USER_AGENT).build()).execute()
        r.use {
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            val bytes = it.body.bytes()
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
        }
    }

    /** #EXTM3U playlists: attributes, #EXTVLCOPT headers and the "url|Header=value" form. */
    fun parseM3u(sourceId: String, text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var info: String? = null
        var extGroup: String? = null
        val options = mutableMapOf<String, String>()
        val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> { info = line; options.clear(); extGroup = null }
                line.startsWith("#EXTGRP:", true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-referrer=", true) -> options["Referer"] = opt.substringAfter('=')
                        opt.startsWith("http-user-agent=", true) -> options["User-Agent"] = opt.substringAfter('=')
                        opt.startsWith("http-origin=", true) -> options["Origin"] = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val inf = info ?: continue
                    val attrs = attr.findAll(inf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = inf.substringAfterLast(",", "").trim().ifBlank { attrs["tvg-name"] ?: "Channel ${out.size + 1}" }
                    val headers = options.toMutableMap()
                    var url = line
                    if ('|' in line) {
                        url = line.substringBefore('|')
                        line.substringAfter('|').split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim(); val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                            if (k.isNotEmpty() && v.isNotEmpty()) headers[if (k.equals("referrer", true)) "Referer" else k] = v
                        }
                    }
                    if (url.startsWith("http") || url.startsWith("rtmp") || url.startsWith("rtsp") || url.startsWith("udp")) {
                        out += Channel("$sourceId:${out.size}:${url.hashCode()}", sourceId, name, attrs["tvg-logo"]?.ifBlank { null },
                            (attrs["group-title"] ?: extGroup)?.substringBefore(';')?.ifBlank { null }, url, headers,
                            attrs["tvg-id"]?.ifBlank { null }, attrs["tvg-country"]?.ifBlank { null }?.uppercase(),
                            attrs["tvg-chno"]?.toIntOrNull() ?: (out.size + 1))
                    }
                    info = null
                }
            }
        }
        return out
    }

    private fun xtream(s: Source): List<Channel> {
        val auth = "username=${enc(s.user.orEmpty())}&password=${enc(s.pass.orEmpty())}"
        val login = JSONObject(text("${s.url}/player_api.php?$auth"))
        val status = login.optJSONObject("user_info")?.optString("auth")
        if (status == "0") throw IllegalArgumentException("The provider refused the username or password")
        val groups = runCatching { JSONArray(text("${s.url}/player_api.php?$auth&action=get_live_categories")) }.getOrDefault(JSONArray())
        val names = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.associate { it.optString("category_id") to it.optString("category_name") }
        val streams = JSONArray(text("${s.url}/player_api.php?$auth&action=get_live_streams"))
        return (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.map { o ->
            val id = o.optString("stream_id")
            Channel("${s.id}:$id", s.id, o.optString("name"), o.optString("stream_icon").ifBlank { null },
                names[o.optString("category_id")], "${s.url}/live/${s.user}/${s.pass}/$id.m3u8",
                epgId = o.optString("epg_channel_id").ifBlank { null }, number = o.optInt("num"))
        }
    }

    private suspend fun stremio(s: Source): List<Channel> {
        val addon = StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == s.id } ?: return emptyList()
        val out = mutableListOf<Channel>()
        for (c in addon.manifest.catalogs.filter { isTvType(it.type) && !it.needsExtra }) {
            var skip = 0
            // Pages until a page comes back empty (bounded: a TV catalog rarely needs more).
            for (page in 0 until 20) {
                val extra = if (skip > 0) "/skip=$skip" else ""
                val metas = StremioAddons.json("${addon.base}/catalog/${c.type}/${StremioAddons.encode(c.id)}$extra.json")?.optJSONArray("metas")
                if (metas == null || metas.length() == 0) break
                for (i in 0 until metas.length()) {
                    val m = metas.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    if (id.isBlank() || out.any { it.key == "${s.id}:$id" }) continue
                    val genre = m.optJSONArray("genres")?.optString(0)?.ifBlank { null } ?: m.optJSONArray("genre")?.optString(0)?.ifBlank { null }
                    out += Channel("${s.id}:$id", s.id, m.optString("name", id), m.optString("logo").ifBlank { m.optString("poster") }.ifBlank { null },
                        genre ?: c.name, "${addon.base}/meta/${c.type}/${StremioAddons.encode(id)}", number = out.size + 1)
                }
                skip += metas.length()
                if (metas.length() < 20) break
            }
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- guide (XMLTV) ----------

    private val guide = mutableMapOf<String, List<Programme>>()
    private var guideLoaded = mutableSetOf<String>()

    /** Loads the XMLTV guides of the enabled sources (Xtream ones come with the login). */
    suspend fun loadGuides(force: Boolean = false) = withContext(Dispatchers.IO) {
        for (s in sources().filter { it.enabled }) {
            val url = when (s.kind) {
                Kind.M3U -> s.epgUrl
                Kind.XTREAM -> s.epgUrl ?: "${s.url}/xmltv.php?username=${enc(s.user.orEmpty())}&password=${enc(s.pass.orEmpty())}"
                Kind.STREMIO -> null
            } ?: continue
            if (!force && url in guideLoaded) continue
            val file = File(cacheDir, "guide-" + MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).take(6).joinToString("") { "%02x".format(it) } + ".xml")
            runCatching {
                val stale = !file.isFile || System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L
                if (force || stale) {
                    app.baseClient.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", com.lagradost.cloudstream3.USER_AGENT).build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    }
                }
                val parsed = parseXmltv(file.inputStream())
                synchronized(guide) { guide.putAll(parsed) }
                guideLoaded += url
            }.onFailure { System.err.println("W/livetv: guide $url: $it") }
        }
        changed()
    }

    /** Programmes from 3 hours ago to 2 days ahead, per channel id. Gzipped files are fine. */
    fun parseXmltv(input: InputStream): Map<String, List<Programme>> {
        val stream = java.io.BufferedInputStream(input).let { b ->
            b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b
            b.reset()
            if (gz) GZIPInputStream(b) else b
        }
        val from = System.currentTimeMillis() - 3 * 3600_000L
        val to = System.currentTimeMillis() + 48 * 3600_000L
        val out = mutableMapOf<String, MutableList<Programme>>()
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val r = factory.createXMLStreamReader(stream)
        var channel: String? = null; var start = 0L; var stop = 0L
        var title: String? = null; var desc: String? = null; var field: String? = null
        val text = StringBuilder()
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "programme" -> {
                        channel = r.getAttributeValue(null, "channel")
                        start = xmltvTime(r.getAttributeValue(null, "start")); stop = xmltvTime(r.getAttributeValue(null, "stop"))
                        title = null; desc = null
                    }
                    "title", "desc" -> { field = r.localName; text.clear() }
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (field != null) text.append(r.text)
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "title" -> { if (title == null) title = text.toString().trim(); field = null }
                    "desc" -> { if (desc == null) desc = text.toString().trim(); field = null }
                    "programme" -> {
                        val ch = channel
                        if (ch != null && title != null && stop > from && start < to) out.getOrPut(ch) { mutableListOf() } += Programme(start, stop, title!!, desc)
                        channel = null
                    }
                }
            }
        }
        r.close()
        return out.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    private val xmltvFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** "20261029190000 +0100" → epoch millis. */
    fun xmltvTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(' ')
        val local = LocalDateTime.parse(parts[0].take(14).padEnd(14, '0'), xmltvFormat)
        val offset = parts.getOrNull(1)?.let { runCatching { ZoneOffset.of(it.substring(0, 3) + ":" + it.substring(3, 5)) }.getOrNull() } ?: ZoneOffset.UTC
        return local.toInstant(offset).toEpochMilli()
    }

    /** What's on now and next on [channel], if a guide covers it. */
    fun nowNext(channel: Channel, at: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = synchronized(guide) { guide[channel.epgId ?: channel.name] ?: guide[channel.name] } ?: return null to null
        val i = list.indexOfFirst { at in it.start until it.stop }
        return if (i < 0) null to list.firstOrNull { it.start > at } else list[i] to list.getOrNull(i + 1)
    }

    // ---------- favourites and recently watched (per profile) ----------

    private fun keys(name: String): List<String> = runCatching { JSONArray(mine.getString(name, "[]")) }.getOrDefault(JSONArray())
        .let { a -> (0 until a.length()).map { a.getString(it) } }

    fun favourites(): Set<String> = keys("favourites").toSet()
    fun recent(): List<String> = keys("recent")

    fun toggleFavourite(c: Channel): Boolean {
        val now = favourites().toMutableSet()
        val added = c.key !in now
        if (added) now += c.key else now -= c.key
        mine.edit().putString("favourites", JSONArray(now.toList()).toString()).apply()
        changed()
        return added
    }

    fun watched(c: Channel) {
        val list = (listOf(c.key) + recent().filterNot { it == c.key }).take(30)
        mine.edit().putString("recent", JSONArray(list).toString()).apply()
        changed()
    }
}

/**
 * Plays Live TV channels in the built-in player: a channel's address (and headers) is its
 * data. Stremio TV channels go through their add-on instead ([StremioApi]).
 */
object LiveApi : MainAPI() {
    override var name = "Live TV"
    override var mainUrl = "livetv://"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Live)

    /** A channel as the "url" this API loads: everything needed to play it. */
    fun data(c: LiveTv.Channel) = JSONObject().put("url", c.url).put("headers", JSONObject(c.headers)).put("name", c.name).put("logo", c.logo ?: "").toString()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    override suspend fun load(url: String): LoadResponse? {
        val o = JSONObject(url)
        return newLiveStreamLoadResponse(o.optString("name", "Live TV"), url, url) {
            posterUrl = o.optString("logo").ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val o = JSONObject(data)
        val url = o.getString("url")
        val h = o.optJSONObject("headers")
        val headers = h?.keySet()?.associateWith { h.getString(it) }.orEmpty()
        val type = if (url.substringBefore('?').endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink(o.optString("name", "Live TV"), "Live", url, type) {
            this.headers = headers
            headers["Referer"]?.let { referer = it }
        })
        return true
    }
}

/**
 * Channel logos from the open iptv-org database (github.com/iptv-org/database, public domain),
 * for channels whose playlist has no logo or a broken one. Matched by tvg-id, then by name.
 * The lookup tables are downloaded once a week and kept small on disk.
 */
object ChannelLogos {
    /** iptv-org's API, with GitHub's copy of the same files as a second address. */
    private val bases get() = System.getProperty("cs.logos.base")?.let { listOf(it) }
        ?: listOf("https://iptv-org.github.io/api", "https://raw.githubusercontent.com/iptv-org/api/gh-pages")
    private val file get() = File(AppDirs.data, "cache/livetv/iptv-org-logos.json")
    @Volatile private var byId: Map<String, String>? = null
    @Volatile private var byName: Map<String, String>? = null
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Logo addresses to try for [c], best first. */
    suspend fun candidates(c: LiveTv.Channel): List<String> {
        val own = c.logo?.takeIf { it.startsWith("http") }
        val fromDb = runCatching { lookup(c) }.getOrNull()
        return listOfNotNull(own, fromDb).distinct()
    }

    suspend fun lookup(c: LiveTv.Channel): String? {
        ensureLoaded()
        val id = c.epgId?.substringBefore('@')?.lowercase()
        return id?.let { byId?.get(it) } ?: byName?.get(normalize(c.name))
    }

    /** "1+1 International HD (720p) [Geo-blocked]" → "1+1international". */
    fun normalize(name: String) = name.lowercase()
        .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
        .replace(Regex("\\b(hd|fhd|uhd|sd|4k|hevc|backup|tv)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}+]"), "")

    private suspend fun ensureLoaded() {
        if (byId != null) return
        lock.withLock {
            if (byId != null) return
            withContext(Dispatchers.IO) {
                val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L
                val json = (if (fresh) runCatching { JSONObject(file.readText()) }.getOrNull() else null) ?: runCatching { download() }
                    .onFailure { System.err.println("W/logos: $it") }.getOrNull()
                    ?: runCatching { JSONObject(file.readText()) }.getOrNull() // stale is better than nothing
                val ids = json?.optJSONObject("ids"); val names = json?.optJSONObject("names")
                byId = ids?.keySet()?.associateWith { ids.getString(it) }.orEmpty()
                byName = names?.keySet()?.associateWith { names.getString(it) }.orEmpty()
            }
        }
    }

    private fun get(url: String): String {
        app.baseClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            return r.body.string()
        }
    }

    private fun download(): JSONObject {
        var last: Throwable? = null
        for (base in bases) {
            try { return download(base) } catch (t: Throwable) { last = t }
        }
        throw last ?: java.io.IOException("no logo database address")
    }

    private fun download(base: String): JSONObject {
        // logos.json: [{channel, feed, format, width, url}]; channels.json: [{id, name, alt_names, logo?}]
        val logos = mutableMapOf<String, Pair<Int, String>>()
        runCatching { JSONArray(get("$base/logos.json")) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val format = o.optString("format").uppercase()
                if (format == "SVG" || o.optString("url").endsWith(".svg")) continue // JavaFX can't draw SVG
                val score = o.optInt("width") + (if (o.isNull("feed") || o.optString("feed").isBlank()) 10_000 else 0) +
                    (if (o.optBoolean("in_use", true)) 20_000 else 0)
                val id = o.optString("channel").lowercase()
                if ((logos[id]?.first ?: -1) < score) logos[id] = score to o.optString("url")
            }
        }
        val names = mutableMapOf<String, String>()
        val channels = JSONArray(get("$base/channels.json"))
        for (i in 0 until channels.length()) {
            val o = channels.optJSONObject(i) ?: continue
            val id = o.optString("id").lowercase()
            val logo = logos[id]?.second ?: o.optString("logo").takeIf { it.startsWith("http") && !it.endsWith(".svg") } ?: continue
            if (id !in logos) logos[id] = 0 to logo
            val all = listOf(o.optString("name")) + (o.optJSONArray("alt_names")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
            all.map(::normalize).filter { it.length >= 2 }.forEach { names.putIfAbsent(it, logo) }
        }
        val out = JSONObject().put("ids", JSONObject(logos.mapValues { it.value.second })).put("names", JSONObject(names as Map<*, *>))
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        return out
    }
}

