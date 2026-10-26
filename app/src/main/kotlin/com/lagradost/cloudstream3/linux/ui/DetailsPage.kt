package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.linux.Bookmark
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.Playable
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ProgressIndicator
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Title page: backdrop, metadata, play/resume, seasons and episodes, recommendations. */
class DetailsPage(
    private val api: MainAPI,
    private val url: String,
    private val name: String,
    private val poster: String?,
    private val posterHeaders: Map<String, String>?,
) : StackPane(), Disposable, Refreshable {
    private val backdropLayer = StackPane()
    private val content = VBox(30.0).apply { pad(18.0, 40.0, 48.0, 40.0) }
    private var response: LoadResponse? = null
    private var playables: List<Playable> = emptyList()
    private var groupIndex = 0
    private val episodesBox = VBox(18.0)
    private var job: Job? = null
    private val playSlot = StackPane()
    private val unsubscribe: () -> Unit

    init {
        val shade = Region().apply {
            style = "-fx-background-color: linear-gradient(to bottom, rgba(9,9,12,0.35) 0%, rgba(9,9,12,0.85) 420px, #09090c 640px);"
            isMouseTransparent = true
        }
        backdropLayer.children += backdrop(poster, posterHeaders, 0.35)
        children.addAll(backdropLayer, shade, pageScroll(content))
        renderLoading()
        load()
        // Playback progress updates the Resume button and episode bars in place.
        unsubscribe = Library.onChange {
            ui.launch {
                if (response == null) return@launch
                playSlot.children.setAll(playButton())
                if (playables.size > 1) renderEpisodes()
            }
        }
    }

    override fun onShow() {
        if (response != null) render()
    }

    private fun load() {
        job = ui.launch {
            try {
                val r = Engine.load(api, url) ?: error("${api.name} returned nothing for this title")
                response = r
                playables = Engine.playables(r)
                val last = Library.lastWatched(api.name, url)
                groupIndex = groups().indexOfFirst { g -> g.second.any { it.data == last?.episodeData } }.coerceAtLeast(0)
                (r.backgroundPosterUrl ?: r.posterUrl)?.let { backdropLayer.children.setAll(backdrop(it, r.posterHeaders, 0.35)) }
                render()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                content.children.setAll(topBar(), emptyState(Icons.INFO, "Couldn't open this title", t.message ?: t.toString(),
                    button("Retry", Icons.REFRESH, "btn-accent") { renderLoading(); load() }))
            }
        }
    }

    private fun topBar(): Node = hbox(12.0, button(null, Icons.BACK, "btn-ghost") { Nav.window.back() }.tooltip("Back (Esc)"), spacer(), label(api.name, "tag"))

    private fun posterNode(url: String?, headers: Map<String, String>?): Node {
        val view = ImageView()
        Images.load(view, url, 240.0, 360.0, headers)
        return StackPane(view).apply {
            styleClass += "poster-frame"
            setMinSize(240.0, 360.0); setMaxSize(240.0, 360.0)
            roundedClip(this, 14.0)
            style = "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.75), 30, 0.2, 0, 10);"
        }
    }

    private fun renderLoading() {
        content.children.setAll(
            topBar(),
            hbox(36.0, posterNode(poster, posterHeaders), vbox(16.0,
                label(name, "h1").apply { isWrapText = true; maxWidth = 760.0 },
                hbox(10.0, ProgressIndicator().apply { setPrefSize(20.0, 20.0) }, label("Loading details…", "muted")),
            )).apply { alignment = Pos.BOTTOM_LEFT },
        )
    }

    /** Episodes grouped by (dub, season), the way the Android app's season selector does. */
    private fun groups(): List<Pair<String, List<Playable>>> {
        val dubs = playables.mapNotNull { it.dub }.distinct()
        val seasonNames = (response as? EpisodeResponse)?.seasonNames?.associate { it.season to (it.name ?: "Season ${it.displaySeason ?: it.season}") }
        return playables.groupBy { it.dub to it.season }.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second ?: 0 }))
            .map { (key, eps) ->
                val (dub, season) = key
                val seasonLabel = season?.let { seasonNames?.get(it) ?: "Season $it" } ?: "Episodes"
                (if (dubs.size > 1 && dub != null) "$seasonLabel · ${dub}" else seasonLabel) to eps.sortedBy { it.episode ?: 0 }
            }
    }

    private fun render() {
        val r = response ?: return
        val meta = listOfNotNull(
            r.type.name,
            r.year?.toString(),
            r.duration?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
            r.contentRating,
            if (playables.size > 1) "${playables.size} episodes" else null,
        )
        val tags = hbox(8.0).apply {
            r.score?.toDouble(10)?.takeIf { it > 0 }?.let { children += label("★ %.1f".format(it), "tag", "accent") }
            meta.forEach { children += label(it, "tag") }
        }
        val genres = FlowPane(8.0, 8.0).apply { r.tags.orEmpty().take(10).forEach { children += label(it, "chip") } }
        val plot = label(r.plot ?: "No description available.", "muted").apply {
            isWrapText = true; maxWidth = 820.0; style = "-fx-font-size: 14px; -fx-line-spacing: 3;"
        }

        val bookmarked = Library.isBookmarked(api.name, url)
        lateinit var bookmarkButton: Button
        bookmarkButton = button(if (bookmarked) "In Library" else "Add to Library", if (bookmarked) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE, "btn-ghost") {
            val now = Library.toggleBookmark(Bookmark(api.name, url, r.name, r.posterUrl ?: poster, r.type.name, System.currentTimeMillis()))
            bookmarkButton.text = if (now) "In Library" else "Add to Library"
            bookmarkButton.graphic = icon(if (now) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE, 18.0)
            Toasts.show(if (now) "Added to your library" else "Removed from your library")
        }
        playSlot.children.setAll(playButton())
        val actions = hbox(10.0, playSlot, button("Sources", Icons.MOVIE, "btn-ghost") {
            target()?.let { Playback.open(api, r, it, chooseManually = true) }
        }.apply { isDisable = playables.isEmpty() }, bookmarkButton)

        val info = vbox(16.0,
            label(r.name, "h1").apply { isWrapText = true; maxWidth = 820.0 },
            tags, plot, genres, actions.pad(6.0, 0.0, 0.0, 0.0),
        ).apply { alignment = Pos.BOTTOM_LEFT }
        val header = hbox(36.0, posterNode(r.posterUrl ?: poster, r.posterHeaders ?: posterHeaders), info).apply { alignment = Pos.BOTTOM_LEFT }

        val nodes = mutableListOf(topBar(), header)
        r.actors?.takeIf { it.isNotEmpty() }?.let { actors ->
            nodes += vbox(10.0, label("Cast", "h3"), FlowPane(8.0, 8.0).apply {
                actors.take(14).forEach { a -> children += label(listOfNotNull(a.actor.name, a.roleString).joinToString(" · "), "tag") }
            })
        }
        if (playables.size > 1) nodes += episodesBox.also { renderEpisodes() }
        r.recommendations?.takeIf { it.isNotEmpty() }?.let { recs ->
            nodes += Shelf("More like this").apply { recs.forEach { item -> add(PosterCard.of(item) { Nav.open(api, item) }) } }
        }
        if (playables.isEmpty()) nodes += emptyState(Icons.INFO, "Nothing to play", "${api.name} didn't list any playable items for this title.")
        content.children.setAll(nodes)
    }

    /** The episode Play should start: resume the last one, else the next unwatched, else the first. */
    private fun target(): Playable? {
        val last = Library.lastWatched(api.name, url)
        val index = playables.indexOfFirst { it.data == last?.episodeData }
        return when {
            index < 0 -> playables.firstOrNull()
            last!!.finished -> playables.getOrNull(index + 1) ?: playables[index]
            else -> playables[index]
        }
    }

    private fun playButton(): Node {
        val target = target()
        val last = Library.lastWatched(api.name, url)
        val text = when {
            target == null -> "Play"
            last != null && target.data == last.episodeData && !last.finished ->
                "Resume" + (if (playables.size > 1) " ${shortCode(target)}" else "") + " · ${clock(last.position)}"
            playables.size > 1 -> "Play ${shortCode(target)}"
            else -> "Play"
        }
        return button(text, Icons.PLAY, "btn-primary") { target?.let { Playback.open(api, response!!, it) } }.apply {
            isDisable = target == null
            minWidth = 220.0
            style = "-fx-font-size: 14px; -fx-padding: 12 26 12 22;"
        }
    }

    private fun renderEpisodes() {
        val groups = groups()
        val chips = ChipGroup { i -> groupIndex = i; renderEpisodes() }
        chips.setItems(groups.map { "${it.first}  ${it.second.size}" }, groupIndex)
        val grid = FlowPane(16.0, 18.0)
        groups.getOrNull(groupIndex)?.second?.forEach { ep -> grid.children += episodeCard(ep) }
        episodesBox.children.setAll(hbox(14.0, label("Episodes", "h3"), chips), grid)
    }

    private fun episodeCard(ep: Playable): Node {
        val w = 272.0
        val h = 153.0
        val r = response!!
        val image = ImageView()
        Images.load(image, ep.posterUrl ?: r.backgroundPosterUrl ?: r.posterUrl, w, h, r.posterHeaders)
        val progress = Library.progress(api.name, ep.data)?.let { if (it.duration > 0) it.position / it.duration else null }
        val play = StackPane(icon(Icons.PLAY, 20.0).apply { style += "-fx-background-color: white;" }).apply {
            style = "-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 999;"
            setMaxSize(46.0, 46.0)
            opacity = 0.0
        }
        val thumb = StackPane(image, play).apply {
            styleClass += "poster-frame"
            setMinSize(w, h); setMaxSize(w, h)
            if (progress != null) children += progressBar(progress, w).also { StackPane.setAlignment(it, Pos.BOTTOM_LEFT) }
            if (progress != null && progress > 0.92) children += label("Watched", "badge", "good").also {
                StackPane.setAlignment(it, Pos.TOP_RIGHT); StackPane.setMargin(it, javafx.geometry.Insets(8.0))
            }
            roundedClip(this, 10.0)
        }
        val number = listOfNotNull(ep.season?.let { "S$it" }, ep.episode?.let { "EPISODE $it" }).joinToString(" · ").ifEmpty { "EPISODE" }
        val text = vbox(4.0,
            label(number, "episode-number"),
            label(ep.name ?: ep.label, "episode-title").apply { maxWidth = w - 20 },
            label(ep.description ?: "", "muted", "small").apply { isWrapText = true; maxWidth = w - 20; maxHeight = 34.0; isVisible = !ep.description.isNullOrBlank(); isManaged = isVisible },
        ).pad(10.0, 12.0, 12.0, 12.0)
        return VBox(thumb, text).apply {
            styleClass += "episode-card"
            if (progress != null && progress > 0.92) styleClass += "watched"
            prefWidth = w; maxWidth = w
            roundedClip(this, 12.0)
            setOnMouseEntered { animate(play.opacityProperty(), 1.0, 120.0) }
            setOnMouseExited { animate(play.opacityProperty(), 0.0, 120.0) }
            onClick { Playback.open(api, r, ep) }
        }
    }

    override fun dispose() {
        job?.cancel()
        unsubscribe()
    }
}

fun shortCode(p: Playable): String =
    listOfNotNull(p.season?.let { "S$it" }, p.episode?.let { "E$it" }).joinToString("").ifEmpty { p.label }

fun clock(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
