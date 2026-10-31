package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * Downloads for watching offline. A download is one movie or episode: the extension is asked for
 * its sources, the best one that can be saved (a direct file or HLS; not DASH or torrents) is
 * written to the downloads folder, with subtitles beside it. HLS segments are joined into one
 * file (AES-128 decrypted). Downloads resume after a pause, a restart or a dropped connection.
 *
 * Downloaded episodes play through [OfflineApi], which keeps the extension's name and episode
 * data, so progress, Continue Watching and "watched" are shared with streaming.
 */
object Downloads {
    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }

    data class Item(
        val id: String, val profile: String,
        val apiName: String, val url: String, val title: String, val poster: String?, val type: String,
        val data: String, val label: String?, val season: Int?, val episode: Int?, val episodeName: String?,
        val file: String? = null, val state: State = State.QUEUED, val bytes: Long = 0, val total: Long = 0,
        val segmentsDone: Int = 0, val segments: Int = 0, val error: String? = null,
        val created: Long = System.currentTimeMillis(),
        val source: String? = null, val headers: Map<String, String> = emptyMap(), val hls: Boolean = false,
        val subtitles: List<String> = emptyList(), val retried: Boolean = false,
    ) {
        /** 0..1, or -1 when the size isn't known yet. */
        val progress: Double get() = when {
            state == State.DONE -> 1.0
            segments > 0 -> segmentsDone.toDouble() / segments
            total > 0 -> bytes.toDouble() / total
            else -> -1.0
        }
        val isEpisode get() = label != null
    }

    private const val PARALLEL = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val items = ConcurrentHashMap<String, Item>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val stateFile get() = File(AppDirs.data, "downloads.json")
    @Volatile private var loaded = false
    @Volatile private var lastSave = 0L

    // ---------- where files go ----------

    /** ~/Downloads/CloudStream (the XDG download folder; the Flatpak may write there), or the chosen folder. */
    fun folder(): File {
        Settings.downloadFolder.takeIf { it.isNotBlank() }?.let { return File(it) }
        val xdg = runCatching {
            File(System.getProperty("user.home"), ".config/user-dirs.dirs").readLines()
                .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }?.substringAfter('=')?.trim('"')
                ?.replace("\$HOME", System.getProperty("user.home"))
        }.getOrNull()
        return File(xdg ?: "${System.getProperty("user.home")}/Downloads", "CloudStream")
    }

    // ---------- the list ----------

    fun onChange(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** This profile's downloads, newest first. */
    fun all(): List<Item> { ensureLoaded(); return items.values.filter { it.profile == Profiles.current.id }.sortedByDescending { it.created } }

    /** Running or waiting downloads of every profile (they all keep going after a profile switch). */
    fun active(): List<Item> { ensureLoaded(); return items.values.filter { it.state == State.RUNNING || it.state == State.QUEUED } }

    fun get(apiName: String, data: String): Item? { ensureLoaded(); return items[id(apiName, data)]?.takeIf { it.profile == Profiles.current.id } }

    /** The finished file for an episode, if it is downloaded and still on disk. */
    fun localFile(apiName: String, data: String): File? = get(apiName, data)?.takeIf { it.state == State.DONE }?.file?.let(::File)?.takeIf { it.isFile }

    fun id(apiName: String, data: String): String =
        MessageDigest.getInstance("SHA-1").digest("$apiName\n$data".toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val arr = JSONArray(stateFile.readText())
                for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i)).let { items[it.id] = it }
            }
            loaded = true
        }
    }

    @Synchronized
    private fun save(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2000) return
        lastSave = now
        val arr = JSONArray(); items.values.sortedBy { it.created }.forEach { arr.put(toJson(it)) }
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(stateFile)
    }

    private fun update(id: String, persist: Boolean = true, f: (Item) -> Item): Item? {
        val next = items.computeIfPresent(id) { _, old -> f(old) } ?: return null
        save(force = persist)
        changed()
        return next
    }

    // ---------- actions ----------

    /** Restarts what was running or waiting when the app closed. */
    fun init() {
        ensureLoaded()
        items.values.filter { it.state == State.RUNNING }.forEach { items[it.id] = it.copy(state = State.QUEUED) }
        schedule()
    }

    fun start(api: MainAPI, response: LoadResponse, playable: Playable): Item {
        ensureLoaded()
        val id = id(api.name, playable.data)
        items[id]?.let { existing ->
            if (existing.state == State.FAILED || existing.state == State.PAUSED) resume(id)
            return items[id]!!
        }
        val item = Item(
            id, Profiles.current.id, api.name, response.url, response.name, response.posterUrl, response.type.name,
            playable.data, playable.label.takeIf { Engine.playables(response).size > 1 }, playable.season, playable.episode,
            playable.name,
        )
        items[id] = item
        save(); changed()
        schedule()
        return item
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { if (it.state == State.DONE) it else it.copy(state = State.PAUSED) }
        schedule()
    }

    fun resume(id: String) {
        update(id) { if (it.state == State.DONE) it else it.copy(state = State.QUEUED, error = null, retried = false) }
        schedule()
    }

    /** Stops it and deletes the file and its subtitles. */
    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        val item = items.remove(id) ?: return
        item.file?.let { File(it).delete(); File("$it.part").delete() }
        item.subtitles.forEach { File(it).delete() }
        save(); changed()
        schedule()
    }

    @Synchronized
    private fun schedule() {
        val running = items.values.count { it.state == State.RUNNING && jobs[it.id]?.isActive == true }
        val waiting = items.values.filter { it.state == State.QUEUED }.sortedBy { it.created }.take((PARALLEL - running).coerceAtLeast(0))
        for (item in waiting) {
            items[item.id] = item.copy(state = State.RUNNING)
            jobs[item.id] = scope.launch {
                try {
                    run(item.id)
                    update(item.id) { it.copy(state = State.DONE, error = null) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    System.err.println("W/downloads: ${item.title} ${item.label.orEmpty()}: $t")
                    val current = items[item.id]
                    if (current != null && !current.retried && current.source != null) {
                        // Links often expire: ask the extension again, once, and start over.
                        current.file?.let { File(it).delete(); File("$it.part").delete() }
                        update(item.id) { it.copy(state = State.QUEUED, source = null, bytes = 0, total = 0, segmentsDone = 0, segments = 0, retried = true) }
                    } else update(item.id) { it.copy(state = State.FAILED, error = t.message ?: t.toString()) }
                } finally {
                    jobs.remove(item.id)
                    schedule()
                }
            }
        }
        if (waiting.isNotEmpty()) { save(); changed() }
    }

    // ---------- the work ----------

    private suspend fun run(id: String) {
        var item = items[id] ?: return
        if (item.source == null) item = resolve(item)
        val dir = folder().apply { mkdirs() }
        if (!dir.isDirectory) throw IOException("Can't write to ${dir.path}")
        if (item.hls) hls(item) else direct(item)
    }

    /** Asks the extension for sources and picks the best one that can be saved. */
    private suspend fun resolve(item: Item): Item {
        val api = Engine.allProviders().firstOrNull { it.name == item.apiName }
            ?: throw IOException("The extension ${item.apiName} isn't installed or turned on")
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        Engine.links(api, item.data).collect { e ->
            when (e) {
                is LinkEvent.Link -> if (e.link.source != OfflineApi.SOURCE) links += e.link
                is LinkEvent.Subtitle -> subs += e.subtitle
                is LinkEvent.Done -> Unit
            }
        }
        val usable = Engine.rank(links.distinctBy { it.url }).filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 }
        val link = usable.firstOrNull() ?: throw IOException(if (links.isEmpty()) "No sources found" else "No source can be downloaded (only streams like DASH or torrents)")
        val hls = link.type == ExtractorLinkType.M3U8 || link.url.substringBefore('?').endsWith(".m3u8")
        val base = baseName(item)
        val ext = if (hls) "ts" else link.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts") } ?: "mp4"
        val headers = link.getAllHeaders().toMutableMap().apply { if (keys.none { it.equals("user-agent", true) }) put("User-Agent", USER_AGENT) }
        val subtitleFiles = saveSubtitles(subs, base, headers)
        return update(item.id) {
            it.copy(source = link.url, headers = headers, hls = hls, file = File(folder(), "$base.$ext").path, subtitles = subtitleFiles)
        } ?: throw CancellationException()
    }

    private fun baseName(item: Item): String {
        val clean = { s: String -> s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim() }
        val code = listOfNotNull(item.season?.let { "S%02d".format(it) }, item.episode?.let { "E%02d".format(it) }).joinToString("")
        val name = listOfNotNull(clean(item.title).take(80), code.ifBlank { item.label?.let(clean) }?.takeIf { item.isEpisode }).joinToString(" ")
        var base = name.ifBlank { "video" }
        var n = 2
        while (File(folder(), "$base.mp4").exists() || File(folder(), "$base.ts").exists() || File(folder(), "$base.mkv").exists()) base = "$name ($n)".also { n++ }
        return base
    }

    /** Up to three subtitle files, in the preferred languages first, beside the video (VLC finds them by name). */
    private fun saveSubtitles(subs: List<SubtitleFile>, base: String, linkHeaders: Map<String, String>): List<String> {
        val wanted = Settings.subtitleLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val picked = subs.distinctBy { it.url }.sortedBy { s -> wanted.indexOfFirst { s.lang.contains(it, true) }.let { if (it < 0) 99 else it } }.take(3)
        return picked.mapIndexedNotNull { i, s ->
            runCatching {
                val body = get(s.url, s.headers?.takeIf { it.isNotEmpty() } ?: linkHeaders).use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code}"); r.body.bytes() }
                val ext = s.url.substringBefore('?').substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") }
                    ?: if (body.take(6).toByteArray().decodeToString().startsWith("WEBVTT")) "vtt" else "srt"
                val lang = s.lang.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").take(24).ifBlank { "sub$i" }
                File(folder(), "$base.$lang.$ext").also { it.writeBytes(body) }.path
            }.getOrNull()
        }
    }

    private fun get(url: String, headers: Map<String, String>, range: String? = null): okhttp3.Response {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (headers.keys.none { it.equals("user-agent", true) }) b.header("User-Agent", USER_AGENT)
        range?.let { b.header("Range", it) }
        return app.baseClient.newCall(b.build()).execute()
    }

    private suspend fun direct(item: Item) {
        val file = File(item.file!!)
        val part = File(file.path + ".part")
        val have = part.length()
        get(item.source!!, item.headers, if (have > 0) "bytes=$have-" else null).use { r ->
            if (r.code == 416 && have > 0) { part.renameTo(file); return }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val append = r.code == 206 && have > 0
            val length = r.body.contentLength()
            val total = if (length > 0) (if (append) have + length else length) else 0
            var done = if (append) have else 0
            update(item.id) { it.copy(bytes = done, total = total) }
            RandomAccessFile(part, "rw").use { out ->
                if (!append) out.setLength(0)
                out.seek(done)
                val input = r.body.byteStream()
                val buf = ByteArray(256 * 1024)
                var lastTick = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 500) { lastTick = now; update(item.id, persist = false) { it.copy(bytes = done) }; save(force = false) }
                }
            }
            if (total in 1 until done || (total > 0 && done < total)) throw IOException("Connection closed early")
            update(item.id) { it.copy(bytes = done, total = done) }
        }
        part.renameTo(file)
    }

    // ---------- HLS ----------

    internal data class Segment(val url: String, val keyUrl: String?, val iv: ByteArray?, val sequence: Long, val range: String?)

    private fun resolveUrl(base: String, ref: String): String = base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private fun text(url: String, headers: Map<String, String>): String =
        get(url, headers).use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code} for playlist"); r.body.string() }

    /** The variant closest to the preferred quality (highest when none is set). */
    internal fun pickVariant(master: String, base: String): String? {
        val lines = master.lines().map { it.trim() }
        data class V(val url: String, val height: Int, val bandwidth: Long)
        val variants = lines.mapIndexedNotNull { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(",$l")?.groupValues?.get(1)?.toLongOrNull() ?: 0
            V(resolveUrl(base, uri), height, bw)
        }
        if (variants.isEmpty()) return null
        val preferred = Settings.preferredQuality.takeIf { it > 0 }
        val ok = preferred?.let { p -> variants.filter { it.height in 1..p } }.orEmpty()
        return (ok.ifEmpty { variants }).maxWith(compareBy<V> { it.height }.thenBy { it.bandwidth }).url
    }

    internal fun parseMedia(playlist: String, base: String): Pair<String?, List<Segment>> {
        var keyUrl: String? = null; var iv: ByteArray? = null; var seq = 0L; var map: String? = null
        var range: String? = null; var nextOffset = 0L
        val out = mutableListOf<Segment>()
        var expectSegment = false
        for (raw in playlist.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]+)").find(l)?.groupValues?.get(1)
                    when (method) {
                        "NONE" -> { keyUrl = null; iv = null }
                        "AES-128" -> {
                            keyUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { resolveUrl(base, it) }
                            iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                        }
                        else -> throw IOException("This stream uses $method encryption (DRM); it can't be downloaded")
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> map = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { resolveUrl(base, it) }
                l.startsWith("#EXT-X-BYTERANGE:") -> {
                    val (len, off) = l.substringAfter(':').split('@').let { it[0].toLong() to (it.getOrNull(1)?.toLong() ?: nextOffset) }
                    range = "bytes=$off-${off + len - 1}"; nextOffset = off + len
                }
                l.startsWith("#EXTINF") -> expectSegment = true
                l.isNotEmpty() && !l.startsWith("#") && expectSegment -> {
                    out += Segment(resolveUrl(base, l), keyUrl, iv, seq++, range)
                    range = null; expectSegment = false
                }
            }
        }
        return map to out
    }

    private suspend fun hls(item: Item) {
        val headers = item.headers
        var url = item.source!!
        var playlist = text(url, headers)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            url = pickVariant(playlist, url) ?: throw IOException("Empty HLS playlist")
            playlist = text(url, headers)
        }
        if (!playlist.contains("#EXT-X-ENDLIST") && playlist.contains("#EXTINF")) throw IOException("This is a live stream; it can't be downloaded")
        val (init, segments) = parseMedia(playlist, url)
        if (segments.isEmpty()) throw IOException("Empty HLS playlist")
        // fMP4 segments join into an .mp4; MPEG-TS into a .ts. Both play in VLC.
        var item0 = item
        if (init != null && item.file!!.endsWith(".ts")) item0 = update(item.id) { it.copy(file = it.file!!.removeSuffix(".ts") + ".mp4") } ?: item
        val file = File(item0.file!!)
        val part = File(file.path + ".part")
        var startAt = item0.segmentsDone.coerceAtMost(segments.size)
        if (startAt == 0 || !part.isFile || part.length() < item0.bytes) { startAt = 0; part.delete() }
        update(item.id) { it.copy(segments = segments.size, segmentsDone = startAt) }
        val keys = mutableMapOf<String, ByteArray>()
        RandomAccessFile(part, "rw").use { out ->
            var written = if (startAt == 0) 0L else item0.bytes
            out.setLength(written); out.seek(written)
            if (startAt == 0 && init != null) {
                val bytes = fetch(init, headers, null)
                out.write(bytes); written += bytes.size
            }
            for (i in startAt until segments.size) {
                coroutineContext.ensureActive()
                val s = segments[i]
                var bytes = fetch(s.url, headers, s.range)
                if (s.keyUrl != null) {
                    val key = keys.getOrPut(s.keyUrl) { fetch(s.keyUrl, headers, null) }
                    val iv = s.iv ?: ByteArray(16).also { b -> var v = s.sequence; for (k in 15 downTo 8) { b[k] = (v and 0xff).toByte(); v = v shr 8 } }
                    bytes = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(bytes)
                }
                out.write(bytes); written += bytes.size
                update(item.id, persist = false) { it.copy(segmentsDone = i + 1, bytes = written) }
                save(force = false)
            }
            update(item.id) { it.copy(total = written, bytes = written) }
        }
        part.renameTo(file)
    }

    /** One segment or key, with a few retries for flaky connections. */
    private suspend fun fetch(url: String, headers: Map<String, String>, range: String?): ByteArray {
        var last: Throwable? = null
        repeat(4) { attempt ->
            coroutineContext.ensureActive()
            try {
                return get(url, headers, range).use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code}"); r.body.bytes() }
            } catch (t: IOException) {
                last = t
                kotlinx.coroutines.delay(1000L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }

    // ---------- JSON ----------

    private fun toJson(i: Item) = JSONObject().apply {
        put("id", i.id); put("profile", i.profile); put("apiName", i.apiName); put("url", i.url); put("title", i.title)
        put("poster", i.poster ?: ""); put("type", i.type); put("data", i.data); put("label", i.label ?: "")
        i.season?.let { put("season", it) }; i.episode?.let { put("episode", it) }; put("episodeName", i.episodeName ?: "")
        put("file", i.file ?: ""); put("state", i.state.name); put("bytes", i.bytes); put("total", i.total)
        put("segmentsDone", i.segmentsDone); put("segments", i.segments); put("error", i.error ?: ""); put("created", i.created)
        put("source", i.source ?: ""); put("headers", JSONObject(i.headers)); put("hls", i.hls); put("subtitles", JSONArray(i.subtitles))
        put("retried", i.retried)
    }

    private fun fromJson(o: JSONObject): Item {
        fun s(k: String) = o.optString(k).ifBlank { null }
        val h = o.optJSONObject("headers")
        val subs = o.optJSONArray("subtitles")
        return Item(
            o.getString("id"), o.optString("profile", Profiles.DEFAULT), o.getString("apiName"), o.getString("url"), o.getString("title"),
            s("poster"), o.optString("type", TvType.Movie.name), o.getString("data"), s("label"),
            if (o.has("season")) o.getInt("season") else null, if (o.has("episode")) o.getInt("episode") else null, s("episodeName"),
            s("file"), runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.PAUSED), o.optLong("bytes"), o.optLong("total"),
            o.optInt("segmentsDone"), o.optInt("segments"), s("error"), o.optLong("created"),
            s("source"), h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty(), o.optBoolean("hls"),
            subs?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), o.optBoolean("retried"),
        )
    }
}

/**
 * Plays downloaded files. It takes the extension's name, so the player records progress under
 * the same title and episode as when streaming.
 */
class OfflineApi(original: String) : MainAPI() {
    override var name = original
    override var mainUrl = "offline://"
    override val hasMainPage = false

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = Downloads.get(name, data) ?: return false
        val file = item.file?.let(::File)?.takeIf { it.isFile } ?: return false
        // The file itself comes from Engine.links (a downloaded copy always plays first); add its subtitles.
        item.subtitles.map(::File).filter { it.isFile }.forEach { f ->
            subtitleCallback(newSubtitleFile(f.name.removePrefix(file.nameWithoutExtension).trim('.').substringBeforeLast('.').ifBlank { "Subtitles" }, f.toURI().toString()))
        }
        return true
    }

    /** A title page made from what's downloaded of it (for playing and next episode offline). */
    suspend fun response(item: Downloads.Item): LoadResponse {
        val siblings = Downloads.all().filter { it.apiName == item.apiName && it.url == item.url && it.state == Downloads.State.DONE }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.created }))
        val type = runCatching { TvType.valueOf(item.type) }.getOrDefault(TvType.Movie)
        return if (!item.isEpisode) newMovieLoadResponse(item.title, item.url, type, item.data) { posterUrl = item.poster }
        else newTvSeriesLoadResponse(item.title, item.url, type, siblings.map { d ->
            newEpisode(d.data) { name = d.episodeName; season = d.season; episode = d.episode }
        }) { posterUrl = item.poster }
    }

    companion object {
        const val SOURCE = "Downloaded"

        suspend fun local(file: File): ExtractorLink = newExtractorLink(SOURCE, SOURCE, file.toURI().toString(), ExtractorLinkType.VIDEO) {
            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
        }
    }
}
