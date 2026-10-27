package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.linux.AppDirs
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.LinkEvent
import com.lagradost.cloudstream3.linux.MpvConfig
import com.lagradost.cloudstream3.linux.MpvIpc
import com.lagradost.cloudstream3.linux.Playable
import com.lagradost.cloudstream3.linux.Player
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.WatchEntry
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.Qualities
import javafx.stage.FileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.math.abs

/**
 * Collects an extension's sources and subtitles. It starts on the loading screen and
 * keeps running during playback, so the player's Sources menu fills up over time.
 */
class SourceCollector(private val api: MainAPI, private val playable: Playable) {
    val links = mutableListOf<ExtractorLink>()
    val subtitles = mutableListOf<SubtitleFile>()
    var finished = false
        private set
    var error: Throwable? = null
        private set
    var lastSource: String? = null
        private set
    private var job: Job? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun onUpdate(listener: () -> Unit) {
        listeners += listener
    }

    fun start() {
        job?.cancel()
        links.clear(); subtitles.clear(); finished = false; error = null
        job = ui.launch {
            Engine.links(api, playable.data).collect { event ->
                when (event) {
                    is LinkEvent.Link -> if (links.none { it.url == event.link.url }) {
                        links += event.link
                        lastSource = event.link.source.ifBlank { event.link.name }
                    }
                    is LinkEvent.Subtitle -> if (subtitles.none { it.url == event.subtitle.url }) subtitles += event.subtitle
                    is LinkEvent.Done -> {
                        finished = true
                        error = event.error
                    }
                }
                listeners.toList().forEach { it() }
            }
        }
    }

    fun ranked(): List<ExtractorLink> = Engine.rank(links)

    fun cancel() {
        job?.cancel()
        listeners.clear()
    }
}

/**
 * One playback in mpv, in its own window (Settings → Player → mpv). uosc draws the
 * controls; the app drives sources, subtitles and progress over mpv's JSON IPC.
 */
class PlayerSession(
    private val api: MainAPI,
    private val response: LoadResponse,
    private val playable: Playable,
    private val sources: SourceCollector,
    private var current: ExtractorLink,
) : ActivePlayer {
    private val debug = System.getenv("CS_DEBUG_PLAYER") != null
    private val all = Engine.playables(response)
    private val next = all.getOrNull(all.indexOfFirst { it.data == playable.data } + 1)?.takeIf { it.dub == playable.dub }
    private val title = Playback.title(response, playable, all.size)
    private lateinit var process: Process
    private var ipc: MpvIpc? = null
    private var position = 0.0
    private var duration = 0.0
    private var saved = -100.0
    private var leaving: (() -> Unit)? = null
    private val failed = mutableSetOf<String>()
    private var menuLinks: List<ExtractorLink> = emptyList()
    /** loadfile ends the previous file with reason "stop"; ignore that one. */
    private var switching = false
    private var retryWhenAvailable = false

    fun start() {
        val resume = Library.progress(api.name, playable.data)?.takeIf { it.duration > 0 && it.position / it.duration < 0.92 }?.position
        val socket = Files.createTempFile("cs-mpv-", ".sock").also { Files.delete(it) }
        val config = MpvConfig.install()
        val cmd = buildList {
            add("mpv")
            add("--config-dir=${config.absolutePath}")
            add("--input-ipc-server=$socket")
            // Non-ASCII titles are sent over IPC (always UTF-8), not argv (locale-dependent).
            add("--force-media-title=" + title.filter { it.code < 128 })
            add("--input-vo-keyboard=yes")
            // -append adds one entry, so the commas inside uosc's colour list survive.
            add("--script-opts-append=" + uoscOptions())
            if (resume != null && resume > 5) add("--start=${resume.toLong()}")
            if (Settings.batterySaver) addAll(listOf("--profile=fast", "--hwdec=auto", "--video-sync=audio", "--interpolation=no", "--deband=no"))
            Settings.subtitleLanguage.takeIf { it.isNotBlank() }?.let { add("--slang=$it") }
            addAll(linkOptions(current))
            addAll(Settings.mpvArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
            add(urls(current).first())
        }
        ui.launch {
            try {
                process = withContext(Dispatchers.IO) {
                    ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .redirectOutput(File(AppDirs.data, "logs/player.log").apply { parentFile.mkdirs() })
                        .start()
                }
                ipc = withContext(Dispatchers.IO) { MpvIpc.connect(socket, process) }
                ipc?.let { wire(it) }
                withContext(Dispatchers.IO) { process.waitFor() }
            } catch (t: Throwable) {
                Toasts.show("Couldn't start mpv: ${t.message ?: t}. Install it with: sudo apt install mpv", seconds = 10.0)
            } finally {
                runCatching { Files.deleteIfExists(socket) }
                finish()
            }
        }
    }

    private fun uoscOptions(): String {
        val accent = Theme.hex(Theme.accent)
        return listOf(
            "uosc-color=foreground=$accent,foreground_text=ffffff,background=0b0b10,background_text=ffffff,curtain=000000,success=4ade80,error=ff616e",
        ).joinToString(",")
    }

    private fun urls(link: ExtractorLink) = (link as? ExtractorLinkPlayList)?.playlist?.map { it.url } ?: listOf(link.url)

    private fun headers(link: ExtractorLink): Triple<String, String?, List<String>> {
        val h = link.getAllHeaders()
        val referer = h.entries.firstOrNull { it.key.equals("referer", true) }?.value
        val ua = h.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: USER_AGENT
        val other = h.filterKeys { !it.equals("referer", true) && !it.equals("user-agent", true) }.map { "${it.key}: ${it.value}" }
        return Triple(ua, referer, other)
    }

    private fun linkOptions(link: ExtractorLink): List<String> {
        val (ua, referer, other) = headers(link)
        return buildList {
            add("--user-agent=$ua")
            referer?.let { add("--referrer=$it") }
            other.forEach { add("--http-header-fields-append=$it") }
            link.audioTracks.forEach { add("--audio-file-append=${it.url}") }
        }
    }

    private fun wire(ipc: MpvIpc) {
        ipc.set("force-media-title", title)
        ipc.observe(1, "time-pos")
        ipc.observe(2, "duration")
        updateUserData()
        sources.onUpdate {
            updateUserData()
            refreshSourcesMenu()
            if (retryWhenAvailable) sources.ranked().firstOrNull { it.url !in failed }?.let {
                retryWhenAvailable = false
                switchTo(it, keepPosition = true)
            }
        }
        ui.launch {
            ipc.events.collect { e ->
                if (debug && e.optString("event") != "property-change") System.err.println("D/player: $e")
                when (e.optString("event")) {
                    "property-change" -> onProperty(e.optString("name"), e.opt("data"))
                    "client-message" -> onMessage(e.optJSONArray("args"))
                    "file-loaded" -> addSubtitles()
                    "end-file" -> onEndFile(e.optString("reason"))
                    "ipc-closed" -> Unit
                }
            }
        }
    }

    private fun updateUserData() {
        val ipc = ipc ?: return
        ipc.set("user-data/cs/sources", sources.links.size)
        ipc.set("user-data/cs/has-next", next != null)
    }

    private fun onProperty(name: String, value: Any?) {
        when (name) {
            "time-pos" -> (value as? Number)?.toDouble()?.let {
                position = it
                if (duration > 0 && abs(it - saved) >= 10) {
                    saved = it
                    record()
                }
            }
            "duration" -> (value as? Number)?.toDouble()?.let { duration = it }
        }
    }

    private fun onMessage(args: JSONArray?) {
        val ipc = ipc ?: return
        when (args?.optString(0)) {
            "cs-back" -> quit()
            "cs-sources" -> openSourcesMenu()
            "cs-play-source" -> menuLinks.getOrNull(args.optString(1).toIntOrNull() ?: -1)?.let { switchTo(it, keepPosition = true) }
            "cs-load-sub" -> loadSubtitleFile()
            "cs-settings" -> ipc.scriptMessageTo("uosc", "open-menu", settingsMenu().toString())
            "cs-next" -> next?.let { n -> quit { Playback.open(api, response, n) } }
        }
    }

    private fun sourceItems(): JSONArray {
        menuLinks = sources.ranked()
        val items = JSONArray()
        menuLinks.forEachIndexed { i, link ->
            val quality = Qualities.getStringByInt(link.quality).ifEmpty { "Auto" }
            val host = runCatching { URI(link.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
            items.put(JSONObject()
                .put("title", link.name)
                .put("hint", listOf(quality, link.type.name, host).filter { it.isNotBlank() }.joinToString(" · "))
                .put("active", link.url == current.url)
                .put("muted", link.url in failed)
                .put("value", JSONArray(listOf("script-message", "cs-play-source", i.toString()))))
        }
        if (!sources.finished) items.put(JSONObject().put("title", "Still searching for more sources…").put("muted", true).put("selectable", false))
        return items
    }

    private fun sourcesMenu() = JSONObject()
        .put("type", "cs-sources")
        .put("title", "Sources (${sources.links.size})")
        .put("search_style", "on_demand")
        .put("items", sourceItems())

    private fun openSourcesMenu() {
        ipc?.scriptMessageTo("uosc", "open-menu", sourcesMenu().toString())
    }

    /** Keeps an open Sources menu live as new sources arrive. */
    private fun refreshSourcesMenu() {
        ipc?.scriptMessageTo("uosc", "update-menu", sourcesMenu().toString())
    }

    private fun switchTo(link: ExtractorLink, keepPosition: Boolean) {
        val ipc = ipc ?: return
        current = link
        val (ua, referer, other) = headers(link)
        ipc.set("user-agent", ua)
        ipc.set("referrer", referer ?: "")
        ipc.set("http-header-fields", JSONArray(other))
        ipc.set("audio-files", JSONArray(link.audioTracks.map { it.url }))
        ipc.set("start", if (keepPosition && position > 1) "+${position.toLong()}" else "none")
        switching = true
        ipc.command("loadfile", urls(link).first(), "replace")
        ipc.command("show-text", "Source: ${link.name}", 2500)
    }

    private fun addSubtitles() {
        val ipc = ipc ?: return
        ipc.set("start", "none")
        sources.subtitles.forEach { sub -> ipc.command("sub-add", sub.url, "auto", sub.lang, sub.lang) }
    }

    /** A source that fails to open is skipped automatically, keeping the position. */
    private fun onEndFile(reason: String) {
        if (switching) {
            switching = false
            return
        }
        if (reason == "eof") {
            position = duration
            quit()
            return
        }
        if (reason != "error") return
        failed += current.url
        val alternative = sources.ranked().firstOrNull { it.url !in failed }
        if (alternative == null) {
            if (!sources.finished) {
                // More sources may still arrive; try again when one does.
                ipc?.command("show-text", "Source failed — waiting for another source…", 4000)
                retryWhenAvailable = true
                return
            }
            ipc?.command("show-text", "This source failed and no other source is available", 4000)
            ui.launch { kotlinx.coroutines.delay(3500); quit() }
            return
        }
        ipc?.command("show-text", "Source failed — trying ${alternative.name}", 3000)
        switchTo(alternative, keepPosition = true)
    }

    private fun loadSubtitleFile() {
        val file = FileChooser().apply {
            title = "Load subtitles"
            extensionFilters += FileChooser.ExtensionFilter("Subtitles", "*.srt", "*.vtt", "*.ass", "*.ssa", "*.sub")
        }.showOpenDialog(Nav.window.stage) ?: return
        ipc?.command("sub-add", file.absolutePath, "select")
    }

    private fun item(title: String, command: List<Any>, hint: String? = null, keepOpen: Boolean = false, active: Boolean = false) =
        JSONObject().put("title", title).put("value", JSONArray(command)).apply {
            hint?.let { put("hint", it) }
            if (keepOpen) put("keep_open", true)
            if (active) put("active", true)
        }

    private fun submenu(title: String, vararg items: JSONObject) = JSONObject().put("title", title).put("items", JSONArray(items.toList()))

    /** Menu items are mpv commands, so uosc applies them without a round trip. */
    private fun settingsMenu() = JSONObject()
        .put("type", "cs-settings")
        .put("title", "Playback settings")
        .put("items", JSONArray(listOf(
            submenu("Speed", *listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).map { item("${it}×", listOf("set", "speed", it.toString())) }.toTypedArray()),
            submenu("Subtitles",
                item("Delay −0.1 s", listOf("add", "sub-delay", "-0.1"), "z", keepOpen = true),
                item("Delay +0.1 s", listOf("add", "sub-delay", "0.1"), "x", keepOpen = true),
                item("Reset delay", listOf("set", "sub-delay", "0")),
                item("Smaller", listOf("add", "sub-scale", "-0.1"), keepOpen = true),
                item("Larger", listOf("add", "sub-scale", "0.1"), keepOpen = true),
                item("Move up", listOf("add", "sub-pos", "-2"), keepOpen = true),
                item("Move down", listOf("add", "sub-pos", "2"), keepOpen = true),
                item("Load subtitle file…", listOf("script-message", "cs-load-sub")),
            ),
            submenu("Audio",
                item("Delay −0.1 s", listOf("add", "audio-delay", "-0.1"), keepOpen = true),
                item("Delay +0.1 s", listOf("add", "audio-delay", "0.1"), keepOpen = true),
                item("Reset delay", listOf("set", "audio-delay", "0")),
                item("Boost quiet dialogue", listOf("af", "toggle", "@cs-norm:lavfi=[dynaudnorm=f=150:g=15]")),
            ),
            submenu("Picture",
                item("Aspect: auto", listOf("set", "video-aspect-override", "-1")),
                item("Aspect: 16:9", listOf("set", "video-aspect-override", "16:9")),
                item("Aspect: 21:9", listOf("set", "video-aspect-override", "2.33")),
                item("Aspect: 4:3", listOf("set", "video-aspect-override", "4:3")),
                item("Zoom in", listOf("add", "video-zoom", "0.05"), keepOpen = true),
                item("Zoom out", listOf("add", "video-zoom", "-0.05"), keepOpen = true),
                item("Reset zoom", listOf("set", "video-zoom", "0")),
                item("Deinterlace", listOf("cycle", "deinterlace")),
            ),
            item("Stats (decoder, dropped frames)", listOf("script-binding", "stats/display-stats-toggle")),
        )))

    private fun record() {
        // Nothing worth resuming until something has actually played.
        if (duration <= 0 || position < 5) return
        val entry = WatchEntry(api.name, response.url, response.name, response.posterUrl, playable.data,
            if (all.size > 1) playable.label else null, position, duration, System.currentTimeMillis())
        ui.launch(Dispatchers.IO) { Library.record(entry) }
    }

    /** Stops playback; [then] runs once mpv has exited. */
    override fun quit(then: (() -> Unit)?) {
        leaving = then
        ipc?.command("quit") ?: runCatching { process.destroy() }
    }

    private fun finish() {
        record()
        ipc?.close()
        sources.cancel()
        Playback.active = null
        val then = leaving
        when {
            then != null -> then()
            duration > 0 && position / duration > 0.92 && next != null ->
                if (Settings.autoPlayNext) Playback.open(api, response, next)
                else Toasts.show("Up next: ${next.label}", "Play" to { Playback.open(api, response, next) }, 12.0)
            duration <= 0 && runCatching { process.exitValue() }.getOrDefault(0) != 0 ->
                Toasts.show("The player couldn't open this stream", "Try another source" to { Playback.open(api, response, playable, chooseManually = true) }, 10.0)
        }
    }
}
