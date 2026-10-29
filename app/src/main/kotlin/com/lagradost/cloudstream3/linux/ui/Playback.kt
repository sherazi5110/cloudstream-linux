package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Playable
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import javafx.animation.Interpolator
import javafx.animation.PauseTransition
import javafx.animation.TranslateTransition
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ProgressIndicator
import javafx.scene.image.ImageView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.coroutines.launch
import java.net.URI

/** Starting playback: find sources on the loading screen, then hand over to the player. */
object Playback {
    var active: ActivePlayer? = null

    fun open(api: MainAPI, response: LoadResponse, playable: Playable, chooseManually: Boolean = false) {
        if (active != null) return
        Nav.window.showOverlay(LoadingScreen(api, response, playable, chooseManually))
    }

    fun title(response: LoadResponse, playable: Playable, total: Int) =
        if (total > 1) "${response.name} — ${playable.label}" else response.name

    fun start(api: MainAPI, response: LoadResponse, playable: Playable, sources: SourceCollector, link: ExtractorLink) {
        Nav.window.closeOverlay()
        if (Vlc.factory == null) {
            sources.cancel()
            Toasts.show("Playing needs VLC's libraries: sudo apt install libvlc5 vlc-plugin-base", seconds = 12.0)
            return
        }
        active = BuiltinPlayer(api, response, playable, sources, link).also { it.start() }
    }
}

/**
 * Full-window loading screen while the extension looks for sources. With auto-play on,
 * the best source starts when the extension finishes, a few seconds after the first
 * source, or immediately once a source reaches the preferred quality.
 */
class LoadingScreen(
    private val api: MainAPI,
    private val response: LoadResponse,
    private val playable: Playable,
    chooseManually: Boolean,
) : StackPane(), Disposable {
    private val sources = SourceCollector(api, playable)
    private var armed = Settings.autoPlay && !chooseManually
    private var handedOver = false
    private val status = label("Looking for sources…", "loading-status")
    private val count = label("0 SOURCES", "loading-count")
    private val playNow = button("Play now", Icons.PLAY, "btn-primary") { sources.ranked().firstOrNull()?.let { play(it) } }
    private val bar = Region().apply { styleClass += "loading-bar-fill" }
    private val sweep = TranslateTransition(Duration.millis(1400.0), bar).apply {
        fromX = -260.0; toX = 520.0; cycleCount = TranslateTransition.INDEFINITE; interpolator = Interpolator.EASE_BOTH
    }
    private val grace = PauseTransition(Duration.seconds(4.0)).apply { setOnFinished { autoPlayNow() } }
    private val actions = hbox(12.0)

    init {
        styleClass += "loading-screen"
        val art = ImageView()
        val w = Nav.window.width.coerceAtLeast(1280.0)
        val h = Nav.window.height.coerceAtLeast(720.0)
        Images.load(art, response.backgroundPosterUrl ?: response.posterUrl, w, h, response.posterHeaders)
        art.fitWidthProperty().bind(widthProperty())
        art.fitHeightProperty().bind(heightProperty())
        art.isPreserveRatio = false
        val shade = Region().apply { styleClass += "loading-shade" }

        val track = StackPane(bar).apply {
            styleClass += "loading-bar"
            setMaxSize(520.0, 4.0); setMinSize(520.0, 4.0)
            clip = javafx.scene.shape.Rectangle(520.0, 4.0)
            alignment = Pos.CENTER_LEFT
        }
        bar.setMinSize(220.0, 4.0); bar.setMaxSize(220.0, 4.0)
        sweep.play()

        val total = Engine.playables(response).size
        val heading = vbox(6.0,
            label(response.name, "loading-title").apply { isWrapText = true; maxWidth = 900.0; alignment = Pos.CENTER },
            label(if (total > 1) playable.label else api.name, "loading-subtitle"),
        ).apply { alignment = Pos.CENTER }
        val statusRow = hbox(14.0, status, spacer(), count).apply { maxWidth = 520.0 }
        playNow.isDisable = true
        actions.children.setAll(
            playNow,
            button("Choose source", Icons.MOVIE, "btn-ghost") { showList() },
            button("Cancel", Icons.CLOSE, "btn-ghost") { Nav.window.closeOverlay() },
        )
        actions.alignment = Pos.CENTER
        val center = VBox(22.0, heading, Region().apply { minHeight = 12.0 }, statusRow, track, actions).apply {
            alignment = Pos.CENTER
            maxWidth = 920.0
        }
        children.addAll(art, shade, center)
        sources.onUpdate { onUpdate() }
        sources.start()
        if (chooseManually) javafx.application.Platform.runLater { showList() }
    }

    private fun onUpdate() {
        val n = sources.links.size
        count.text = "$n SOURCE${if (n == 1) "" else "S"}"
        playNow.isDisable = n == 0
        sources.ranked().firstOrNull()?.let { best ->
            playNow.text = "Play now · " + Qualities.getStringByInt(best.quality).ifEmpty { "Auto" }
        }
        status.text = when {
            !sources.finished && n == 0 -> "Looking for sources…"
            !sources.finished -> "Found ${sources.lastSource ?: "a source"} · still searching"
            n == 0 -> errorText()
            else -> "Ready"
        }
        if (sources.finished) {
            sweep.stop()
            bar.translateX = 0.0
            bar.setMinSize(520.0, 4.0)
        }
        if (sources.finished && n == 0) {
            armed = false
            actions.children.setAll(
                button("Retry", Icons.REFRESH, "btn-primary") { sweep.play(); bar.setMinSize(220.0, 4.0); sources.start() },
                button("Close", Icons.CLOSE, "btn-ghost") { Nav.window.closeOverlay() },
            )
            return
        }
        if (!armed) return
        val newest = sources.links.lastOrNull() ?: return
        val preferred = Settings.preferredQuality
        when {
            sources.finished -> autoPlayNow()
            preferred > 0 && newest.quality >= preferred -> autoPlayNow()
            n == 1 && grace.status != javafx.animation.Animation.Status.RUNNING -> grace.playFromStart()
        }
    }

    private fun errorText(): String {
        val t = sources.error ?: return "No sources found. The site may be down or blocked — try Settings → Network."
        return if (t is NotImplementedError || t.toString().contains("WebView")) {
            "This extension needs an Android WebView (e.g. Cloudflare), which Linux doesn't support yet."
        } else "No sources: ${t.message ?: t}"
    }

    private fun autoPlayNow() {
        if (!armed) return
        sources.ranked().firstOrNull()?.let { play(it) }
    }

    private fun play(link: ExtractorLink) {
        if (handedOver) return
        handedOver = true
        armed = false
        grace.stop()
        sweep.stop()
        Playback.start(api, response, playable, sources, link)
    }

    private fun showList() {
        armed = false
        grace.stop()
        children += SourcesSheet(api, response, playable, sources, onPick = ::play, onClose = { children.removeIf { it is SourcesSheet } })
    }

    override fun dispose() {
        grace.stop()
        sweep.stop()
        if (!handedOver) sources.cancel()
    }
}

/** Manual source picker over the loading screen, sharing its source collector. */
class SourcesSheet(
    private val api: MainAPI,
    response: LoadResponse,
    playable: Playable,
    private val sources: SourceCollector,
    private val onPick: (ExtractorLink) -> Unit,
    private val onClose: () -> Unit,
) : StackPane() {
    private val list = VBox(8.0)
    private val status = hbox(10.0)

    init {
        val scrim = Region().apply { styleClass += "scrim"; onClick(onClose) }
        val total = Engine.playables(response).size
        val header = hbox(14.0,
            vbox(4.0, label("Sources", "h2"), label(Playback.title(response, playable, total), "muted").apply { maxWidth = 600.0 }),
            spacer(),
            button(null, Icons.CLOSE, "btn-ghost") { onClose() }.tooltip("Close"),
        )
        val scroll = pageScroll(list).apply {
            maxHeightProperty().bind(Nav.window.heightProperty().multiply(0.62))
            prefViewportHeightProperty().bind(list.heightProperty())
        }
        val sheet = VBox(16.0, header, status, scroll).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 24.0, 26.0)
            maxWidth = 780.0
            maxHeight = Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
        sources.onUpdate { render() }
        render()
    }

    private fun render() {
        val links = sources.ranked()
        status.children.setAll(listOfNotNull(
            if (!sources.finished) ProgressIndicator().apply { setPrefSize(18.0, 18.0) } else null,
            label(
                when {
                    !sources.finished && links.isEmpty() -> "Looking for sources…"
                    !sources.finished -> "${links.size} source(s) so far, still searching…"
                    links.isEmpty() -> "No sources found"
                    else -> "${links.size} source(s)"
                } + if (sources.subtitles.isNotEmpty()) "  ·  ${sources.subtitles.size} subtitle(s)" else "",
                "muted",
            ),
        ))
        if (links.isEmpty()) {
            list.children.setAll(VBox(8.0).apply {
                repeat(3) { children += Region().apply { styleClass += "skeleton"; minHeight = 58.0 } }
                opacity = 0.6
            })
            return
        }
        list.children.setAll(links.mapIndexed { i, link -> row(link, best = i == 0) })
    }

    private fun row(link: ExtractorLink, best: Boolean): Node {
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
            addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED) { it.consume() }
        }
        return hbox(14.0, pill, text, spacer(), copy, icon(Icons.PLAY, 20.0).apply { styleClass += "accent-icon" }).apply {
            styleClass += "source-row"
            if (best) styleClass += "best"
            onClick { onPick(link) }
        }
    }
}
