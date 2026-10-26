package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.LinkEvent
import com.lagradost.cloudstream3.linux.Playable
import com.lagradost.cloudstream3.linux.Player
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.WatchEntry
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import javafx.animation.PauseTransition
import javafx.geometry.Pos
import javafx.scene.control.ProgressIndicator
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.math.abs

/** Starting playback: find sources, hand the chosen one to the player, track progress. */
object Playback {
    fun open(api: MainAPI, response: LoadResponse, playable: Playable, chooseManually: Boolean = false) {
        Nav.window.showOverlay(SourcesSheet(api, response, playable, autoPlay = Settings.autoPlay && !chooseManually))
    }

    fun title(response: LoadResponse, playable: Playable, total: Int) =
        if (total > 1) "${response.name} — ${playable.label}" else response.name

    fun launch(api: MainAPI, response: LoadResponse, playable: Playable, link: ExtractorLink, subtitles: List<SubtitleFile>) {
        val all = Engine.playables(response)
        val resume = Library.progress(api.name, playable.data)?.takeIf { it.duration > 0 && it.position / it.duration < 0.92 }?.position
        val title = title(response, playable, all.size)
        val session = try {
            Player.play(link, subtitles, title, start = resume)
        } catch (t: Throwable) {
            Toasts.show(t.message ?: t.toString(), seconds = 8.0)
            return
        }
        Toasts.show("Opening in ${Settings.player}" + (resume?.let { " · resuming at ${clock(it)}" } ?: ""))

        fun entry(position: Double, duration: Double) = WatchEntry(
            api.name, response.url, response.name, response.posterUrl, playable.data,
            if (all.size > 1) playable.label else null, position, duration, System.currentTimeMillis(),
        )
        ui.launch {
            var saved = -100.0
            val (position, duration) = session.track { p, d ->
                if (abs(p - saved) >= 10) {
                    saved = p
                    Library.record(entry(p, d))
                }
            }
            if (duration > 0) withContext(Dispatchers.IO) { Library.record(entry(position, duration)) }
            val exit = session.process.exitValue()
            when {
                duration <= 0 && exit != 0 -> Toasts.show(
                    "${Settings.player} couldn't play this source (exit $exit)", "Try another" to { open(api, response, playable, chooseManually = true) }, 10.0,
                )
                duration > 0 && position / duration > 0.92 -> {
                    val next = all.getOrNull(all.indexOfFirst { it.data == playable.data } + 1)
                    if (next != null && next.dub == playable.dub) {
                        Toasts.show("Up next: ${next.label}", "Play" to { open(api, response, next) }, 12.0)
                    }
                }
            }
        }
    }
}

/**
 * Lists sources as the extension finds them. With auto-play on, the best source starts
 * once the extension finishes, or a few seconds after the first source arrives, so slow
 * or dead extractors never hold up playback.
 */
class SourcesSheet(
    private val api: MainAPI,
    private val response: LoadResponse,
    private val playable: Playable,
    autoPlay: Boolean,
) : StackPane(), Disposable {
    private val links = mutableListOf<ExtractorLink>()
    private val subtitles = mutableListOf<SubtitleFile>()
    private val list = VBox(8.0)
    private val status = hbox(10.0)
    private val autoBar = hbox(10.0)
    private var armed = autoPlay
    private var finished = false
    private var job: Job? = null
    private val grace = PauseTransition(Duration.seconds(4.0)).apply { setOnFinished { autoPlayNow() } }

    init {
        val scrim = Region().apply { styleClass += "scrim"; onClick { Nav.window.closeOverlay() } }
        val total = Engine.playables(response).size
        val header = hbox(14.0,
            vbox(4.0,
                label("Sources", "h2"),
                label(Playback.title(response, playable, total), "muted").apply { maxWidth = 600.0 },
            ),
            spacer(),
            button(null, Icons.CLOSE, "btn-ghost") { Nav.window.closeOverlay() }.tooltip("Close (Esc)"),
        )
        val scroll = pageScroll(list).apply {
            // Grow with the list up to most of the window, then scroll.
            maxHeightProperty().bind(Nav.window.heightProperty().multiply(0.62))
            prefViewportHeightProperty().bind(list.heightProperty())
        }
        val sheet = VBox(16.0, header, status, autoBar, scroll).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 24.0, 26.0)
            maxWidth = 780.0
            maxHeight = javafx.scene.layout.Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
        render()
        start()
    }

    private fun start() {
        finished = false
        links.clear()
        subtitles.clear()
        render()
        job = ui.launch {
            Engine.links(api, playable.data).collect { event ->
                when (event) {
                    is LinkEvent.Link -> if (links.none { it.url == event.link.url }) {
                        links += event.link
                        val preferred = Settings.preferredQuality
                        when {
                            !armed -> Unit
                            // Already as good as the user wants: no reason to wait for more.
                            preferred > 0 && event.link.quality >= preferred -> { autoPlayNow(); return@collect }
                            links.size == 1 -> grace.playFromStart()
                        }
                    }
                    is LinkEvent.Subtitle -> if (subtitles.none { it.url == event.subtitle.url }) subtitles += event.subtitle
                    is LinkEvent.Done -> {
                        finished = true
                        if (links.isEmpty() && event.error != null) error(event.error)
                        else autoPlayNow()
                    }
                }
                render()
            }
        }
    }

    private fun error(t: Throwable) {
        armed = false
        val reason = when {
            t is NotImplementedError || t.toString().contains("WebView") ->
                "This extension needs an Android WebView (e.g. for Cloudflare), which isn't supported on Linux yet."
            else -> t.message ?: t.toString()
        }
        Toasts.show("${api.name}: $reason", seconds = 8.0)
    }

    private fun autoPlayNow() {
        if (!armed || links.isEmpty()) return
        play(Engine.rank(links).first())
    }

    private fun play(link: ExtractorLink) {
        armed = false
        grace.stop()
        job?.cancel()
        Nav.window.closeOverlay()
        Playback.launch(api, response, playable, link, subtitles.toList())
    }

    private fun render() {
        val ranked = Engine.rank(links)
        status.children.setAll(listOfNotNull(
            if (!finished) ProgressIndicator().apply { setPrefSize(18.0, 18.0) } else null,
            label(
                when {
                    !finished && links.isEmpty() -> "Looking for sources…"
                    !finished -> "${links.size} source(s) so far, still searching…"
                    links.isEmpty() -> "No sources found"
                    else -> "${links.size} source(s)"
                } + if (subtitles.isNotEmpty()) "  ·  ${subtitles.size} subtitle(s)" else "",
                "muted",
            ),
            spacer(),
            if (finished && links.isEmpty()) button("Retry", Icons.REFRESH, "btn-ghost", "btn-small") { start() } else null,
        ))
        autoBar.isVisible = armed
        autoBar.isManaged = armed
        if (armed) autoBar.children.setAll(
            icon(Icons.PLAY, 14.0).apply { style += "-fx-background-color: #e8194f;" },
            label(if (links.isEmpty()) "The best source will start automatically" else "Starting the best source…", "small"),
            spacer(),
            label("Choose manually", "link-button").onClick { armed = false; grace.stop(); render() },
        )
        if (links.isEmpty()) {
            list.children.setAll(
                if (finished) emptyState(Icons.MOVIE, "No sources", "${api.name} found nothing playable. The site may be down or blocked — try another DNS in Settings, or another extension.")
                else VBox(8.0).apply {
                    repeat(3) { children += Region().apply { styleClass += "skeleton"; minHeight = 58.0 } }
                    opacity = 0.6
                }
            )
            return
        }
        list.children.setAll(ranked.mapIndexed { i, link -> row(link, best = i == 0) })
    }

    private fun row(link: ExtractorLink, best: Boolean): javafx.scene.Node {
        val quality = Qualities.getStringByInt(link.quality).ifEmpty { "AUTO" }
        val host = runCatching { URI(link.url).host }.getOrNull()?.removePrefix("www.") ?: ""
        val pill = label(quality, "quality-pill").apply { if (link.quality in 1..479) styleClass += "low" }
        val text = vbox(3.0,
            hbox(8.0, label(link.name, "bold"), if (best) label("BEST", "badge", "accent") else Region()),
            label(listOf(link.type.name, host, link.source).filter { it.isNotBlank() }.distinct().joinToString("  ·  "), "faint", "small"),
        )
        val copy = button(null, Icons.COPY, "btn-ghost", "btn-small") {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(link.url) })
            Toasts.show("Stream URL copied")
        }.apply {
            tooltip("Copy stream URL")
            // Don't let the click reach the row, which would start playback.
            addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED) { it.consume() }
        }
        return hbox(14.0, pill, text, spacer(), copy, icon(Icons.PLAY, 20.0).apply { style += "-fx-background-color: #e8194f;" }).apply {
            styleClass += "source-row"
            if (best) styleClass += "best"
            onClick { play(link) }
        }
    }

    override fun dispose() {
        grace.stop()
        job?.cancel()
    }
}
