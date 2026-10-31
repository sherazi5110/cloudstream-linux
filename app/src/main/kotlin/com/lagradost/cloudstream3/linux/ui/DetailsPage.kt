package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.linux.Bookmark
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.Metadata
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.Playable
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ProgressIndicator
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
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
    /** Extra details from TMDB / Cinemeta / AniList (Settings → Metadata & integrations). */
    private var info: Metadata.Info? = null
    private var playables: List<Playable> = emptyList()
    private var groupIndex = 0
    private val episodesBox = VBox(18.0)
    private var job: Job? = null
    private val playSlot = StackPane()
    private val unsubscribe: () -> Unit

    init {
        val shade = Region().apply {
            style = "-fx-background-color: linear-gradient(to bottom, -cs-bg-60 0%, -cs-bg-85 420px, -cs-bg 640px);"
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
                if (blockedForKids(r)) {
                    content.children.setAll(topBar(), emptyState(Icons.LOCK, "Not available in this profile",
                        "This title is hidden by the kids profile's content filter."))
                    return@launch
                }
                response = r
                playables = Engine.playables(r)
                val last = Library.lastWatched(api.name, url)
                groupIndex = groups().indexOfFirst { g -> g.second.any { it.data == last?.episodeData } }.coerceAtLeast(0)
                (r.backgroundPosterUrl ?: r.posterUrl)?.let { backdropLayer.children.setAll(backdrop(it, r.posterHeaders, 0.35)) }
                render()
                enrich(r)
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

    /** Fills in ratings, artwork, cast and similar titles from the metadata services, then re-renders. */
    private fun enrich(r: LoadResponse) {
        if (!Metadata.hasTmdb && !Metadata.cinemeta && !Metadata.anilist) return
        ui.launch {
            val found = runCatching { Metadata.details(r) }.getOrNull()
            if (response !== r) return@launch
            info = found
            if (r.backgroundPosterUrl == null && Metadata.artwork) found?.backdrop?.let { backdropLayer.children.setAll(backdrop(it, null, 0.35)) }
            render()
        }
    }

    private fun render() {
        val r = response ?: return
        val extra = info
        val meta = listOfNotNull(
            r.type.name,
            r.year?.toString(),
            r.duration?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
            r.contentRating ?: extra?.contentRating,
            if (playables.size > 1) "${playables.size} episodes" else null,
        )
        val tags = hbox(8.0).apply {
            extra?.tmdbVote?.let { children += ratingPill("TMDB", "rating-tmdb", it, extra.tmdbVotes) }
            extra?.imdbRating?.let { children += ratingPill("IMDb", "rating-imdb", it, null) }
            extra?.anilistScore?.let { children += ratingPill("AniList", "rating-anilist", it, null) }
            if (extra?.tmdbVote == null) r.score?.toDouble(10)?.takeIf { it > 0 }?.let { children += label("★ %.1f".format(it), "tag", "accent") }
            meta.forEach { children += label(it, "tag") }
            if (Metadata.hasTmdb) children += label(if (extra?.tmdbId != null) "Wrong match?" else "Find on TMDB", "link-button").apply {
                tooltip("Choose the right film or series on TMDB for ratings, artwork and cast")
                onClick { Nav.window.showOverlay(MatchSheet(r) { enrich(r) }) }
            }
        }
        val genres = FlowPane(8.0, 8.0).apply { r.tags.orEmpty().take(10).forEach { children += label(it, "chip") } }
        val text = (if (Metadata.replaceExtension) extra?.overview ?: r.plot else r.plot ?: extra?.overview)
        val plot = label(text ?: "No description available.", "muted").apply {
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
        val watched = playables.isNotEmpty() && playables.all { Library.isWatched(api.name, it.data) }
        val watchedButton = button(if (watched) "Watched" else "Mark as watched", if (watched) Icons.CHECK else Icons.CHECK, "btn-ghost") {
            if (watched) Library.markUnwatched(api.name, url, playables.map { it.data })
            else Library.markWatched(api.name, url, r.name, r.posterUrl ?: poster, playables.map { it.data to (if (playables.size > 1) it.label else null) })
            Toasts.show(if (watched) "Marked as not watched" else "Marked as watched")
            render()
        }.apply {
            isDisable = playables.isEmpty()
            if (watched) styleClass += "active"
            tooltip(if (watched) "Mark as not watched" else if (playables.size > 1) "Mark every episode as watched" else "Mark as watched")
        }
        val trailer = extra?.trailer?.takeIf { Metadata.extras }?.let { t -> button("Trailer", Icons.PLAY_CIRCLE, "btn-ghost") { openUrl(t) }.tooltip("Opens on YouTube") }
        val download = playables.singleOrNull()?.takeIf { api !is com.lagradost.cloudstream3.linux.OfflineApi }?.let { DownloadsUi.button(api, r, it) }
        val actions = FlowPane(10.0, 10.0, *listOfNotNull(playSlot, button("Sources", Icons.MOVIE, "btn-ghost") {
            target()?.let { Playback.open(api, r, it, chooseManually = true) }
        }.apply { isDisable = playables.isEmpty() }, trailer, download, watchedButton, bookmarkButton).toTypedArray()).apply { alignment = Pos.CENTER_LEFT; maxWidth = 900.0 }

        val logo = (r.logoUrl ?: extra?.logo?.takeIf { Metadata.artwork })?.let { url ->
            ImageView(javafx.scene.image.Image(url, 880.0, 280.0, true, true, true)).apply { isPreserveRatio = true; fitWidth = 440.0; fitHeight = 140.0 }
        }
        val info = vbox(16.0,
            logo?.let { StackPane(it).apply { alignment = Pos.BOTTOM_LEFT; maxWidth = 440.0 } } ?: label(r.name, "h1").apply { isWrapText = true; maxWidth = 820.0 },
            tags, plot, genres, actions.pad(6.0, 0.0, 0.0, 0.0),
        ).apply { alignment = Pos.BOTTOM_LEFT }
        val header = hbox(36.0, posterNode(r.posterUrl ?: poster, r.posterHeaders ?: posterHeaders), info).apply { alignment = Pos.BOTTOM_LEFT }

        val nodes = mutableListOf(topBar(), header)
        val cast = extra?.cast?.takeIf { it.isNotEmpty() && Metadata.extras }
        if (cast != null) nodes += vbox(12.0, label("Cast", "h3"), Shelf("").apply {
            children.removeAt(0) // no second header
            cast.forEach { p -> add(castCard(p)) }
        })
        else r.actors?.takeIf { it.isNotEmpty() }?.let { actors ->
            nodes += vbox(10.0, label("Cast", "h3"), FlowPane(8.0, 8.0).apply {
                actors.take(14).forEach { a -> children += label(listOfNotNull(a.actor.name, a.roleString).joinToString(" · "), "tag") }
            })
        }
        if (playables.size > 1) nodes += episodesBox.also { renderEpisodes() }
        val recs = r.recommendations.orEmpty()
        if (recs.isNotEmpty()) {
            nodes += Shelf("More like this").apply { recs.forEach { item -> add(PosterCard.of(item) { Nav.open(api, item) }) } }
        } else extra?.similar?.takeIf { it.isNotEmpty() && Metadata.extras }?.let { similar ->
            // From TMDB: opening one searches your extensions for it.
            nodes += Shelf("More like this", trailing = label("from TMDB · opens a search", "faint", "small")).apply {
                similar.forEach { s ->
                    add(PosterCard(s.title, s.poster, topLeft = s.vote?.let { "TMDB %.1f".format(it) }) { Nav.window.search(s.title) })
                }
            }
        }
        if (playables.isEmpty()) nodes += emptyState(Icons.INFO, "Nothing to play", "${api.name} didn't list any playable items for this title.")
        content.children.setAll(nodes)
    }

    private fun target(): Playable? = playTarget(api.name, url, playables)

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
        // A handful of seasons fit as chips; long-running shows (One Piece…) get a dropdown.
        val selector: Node = if (groups.size <= 8) {
            ChipGroup { i -> groupIndex = i; renderEpisodes() }.apply { setItems(groups.map { "${it.first}  ${it.second.size}" }, groupIndex) }
        } else {
            javafx.scene.control.ComboBox<String>().apply {
                items.setAll(groups.map { "${it.first}  (${it.second.size})" })
                selectionModel.select(groupIndex)
                prefWidth = 260.0
                setOnAction { groupIndex = selectionModel.selectedIndex; renderEpisodes() }
            }
        }
        val grid = FlowPane(16.0, 18.0)
        groups.getOrNull(groupIndex)?.second?.forEach { ep -> grid.children += episodeCard(ep) }
        val season = groups.getOrNull(groupIndex)?.second.orEmpty()
        val downloadAll = button("Download ${if (groups.size > 1) "these" else "all"}", Icons.DOWNLOAD, "btn-ghost", "btn-small") {
            val r = response ?: return@button
            val todo = season.filter { com.lagradost.cloudstream3.linux.Downloads.get(api.name, it.data) == null }
            todo.forEach { com.lagradost.cloudstream3.linux.Downloads.start(api, r, it) }
            Toasts.show(if (todo.isEmpty()) "Already downloading these" else "Downloading ${todo.size} episode(s)", "Downloads" to { LibraryPage.wantDownloads = true; Nav.window.show(Tab.LIBRARY) })
        }.tooltip("Download every episode in this list for offline viewing")
        episodesBox.children.setAll(FlowPane(14.0, 10.0, label("Episodes", "h3"), selector, downloadAll).apply { alignment = Pos.CENTER_LEFT }, grid)
    }

    private fun episodeCard(ep: Playable): Node {
        val w = 272.0
        val h = 153.0
        val r = response!!
        val image = ImageView()
        Images.load(image, ep.posterUrl ?: r.backgroundPosterUrl ?: r.posterUrl, w, h, r.posterHeaders)
        val progress = Library.progress(api.name, ep.data)?.let { if (it.duration > 0) it.position / it.duration else null }
        // Anti-spoiler: unwatched episodes show a blurred thumbnail and no description.
        val hidden = Settings.antiSpoiler && (progress == null || progress < 0.92) && ep.posterUrl != null
        if (hidden) image.effect = javafx.scene.effect.GaussianBlur(26.0)
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
            children += DownloadsUi.episodeBadge(api, r, ep).also { StackPane.setAlignment(it, Pos.TOP_LEFT); StackPane.setMargin(it, javafx.geometry.Insets(8.0)) }
            roundedClip(this, 10.0)
        }
        val number = listOfNotNull(ep.season?.let { "S$it" }, ep.episode?.let { "EPISODE $it" }).joinToString(" · ").ifEmpty { "EPISODE" }
        val text = vbox(4.0,
            label(number, "episode-number"),
            label(ep.name ?: ep.label, "episode-title").apply { maxWidth = w - 20 },
            label(if (hidden) "Description hidden (anti-spoiler)" else ep.description ?: "", "muted", "small").apply {
                isWrapText = true; maxWidth = w - 20; maxHeight = 34.0
                isVisible = hidden || !ep.description.isNullOrBlank(); isManaged = isVisible
            },
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

/** The episode Play should start: resume the last one, else the next unwatched, else the first. */
fun playTarget(apiName: String, url: String, playables: List<Playable>): Playable? {
    val last = Library.lastWatched(apiName, url)
    val index = playables.indexOfFirst { it.data == last?.episodeData }
    return when {
        index < 0 -> playables.firstOrNull()
        last!!.finished -> playables.getOrNull(index + 1) ?: playables[index]
        else -> playables[index]
    }
}

private fun ratingPill(name: String, style: String, value: Double, votes: Int?): Node = hbox(7.0,
    label(name, style), label("%.1f".format(value), "bold"),
    *listOfNotNull(votes?.let { label(if (it >= 1000) "${it / 1000}k votes" else "$it votes", "faint", "small") }).toTypedArray(),
).apply { styleClass += "rating-pill"; maxWidth = Region.USE_PREF_SIZE }

/** A round cast photo with name and role. */
private fun castCard(p: Metadata.Person): Node {
    val view = ImageView()
    Images.load(view, p.photo, 92.0, 92.0)
    val photo = StackPane(view).apply {
        setMinSize(92.0, 92.0); setMaxSize(92.0, 92.0)
        styleClass += "cast-photo"
        clip = javafx.scene.shape.Circle(46.0, 46.0, 46.0)
        style = "-fx-background-color: -cs-surface-3;"
        if (p.photo == null) children += label(p.name.split(' ').take(2).joinToString("") { it.take(1) }, "bold")
    }
    return vbox(6.0, photo, label(p.name, "cast-name").apply { maxWidth = 104.0 }, label(p.role ?: "", "cast-role").apply { maxWidth = 104.0 }).apply {
        alignment = Pos.TOP_CENTER; minWidth = 104.0; maxWidth = 104.0
    }
}

/** "Wrong match?": search TMDB and pin the right film or series for this title. */
class MatchSheet(private val r: LoadResponse, private val changed: () -> Unit) : StackPane() {
    private val field = javafx.scene.control.TextField(listOfNotNull(Metadata.cleanTitle(r.name).first, (r.year ?: Metadata.cleanTitle(r.name).second)?.toString()).joinToString(" "))
    private val results = VBox(6.0)
    private var job: Job? = null

    init {
        val scrim = Region().apply { styleClass += "scrim"; onClick { Nav.window.closeOverlay() } }
        HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS)
        field.setOnAction { search() }
        val chosen = Metadata.chosenMatch(r.apiName, r.url)
        val footer = hbox(10.0,
            button("Automatic", Icons.REFRESH, "btn-ghost", "btn-small") { done { Metadata.chooseMatch(r.apiName, r.url, null, automatic = true) } }
                .apply { isDisable = chosen == null }.tooltip("Forget the choice and match by title again"),
            button("No match", Icons.CLOSE, "btn-ghost", "btn-small") { done { Metadata.chooseMatch(r.apiName, r.url, null) } }
                .tooltip("Don't use TMDB for this title"),
            spacer(),
            button("Cancel", null, "btn-ghost") { Nav.window.closeOverlay() },
        )
        val scroll = pageScroll(results).apply { prefViewportHeight = 420.0; maxHeight = 420.0 }
        val sheet = VBox(14.0,
            vbox(4.0, label("Choose the right title", "h2"), label("Ratings, artwork, cast and similar titles for “${r.name}” come from the one you pick.", "muted")),
            hbox(10.0, field, button("Search", Icons.SEARCH, "btn-accent") { search() }),
            scroll, footer,
        ).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 20.0, 26.0)
            maxWidth = 720.0; maxHeight = Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        search()
    }

    private fun done(action: () -> Unit) {
        action()
        Nav.window.closeOverlay()
        changed()
    }

    private fun search() {
        job?.cancel()
        results.children.setAll(hbox(10.0, ProgressIndicator().apply { setPrefSize(18.0, 18.0) }, label("Searching TMDB…", "muted")))
        job = ui.launch {
            val found = runCatching { Metadata.searchCandidates(field.text) }.getOrDefault(emptyList())
            if (found.isEmpty()) { results.children.setAll(label("Nothing found. Try another spelling, or the original title.", "muted")); return@launch }
            val current = Metadata.chosenMatch(r.apiName, r.url)
            results.children.setAll(found.take(20).map { c ->
                val poster = ImageView()
                Images.load(poster, c.poster, 46.0, 69.0)
                val frame = StackPane(poster).apply { styleClass += "poster-frame"; setMinSize(46.0, 69.0); setMaxSize(46.0, 69.0) }
                roundedClip(frame, 6.0)
                hbox(14.0, frame, vbox(3.0,
                    hbox(8.0, label(c.title, "bold"), label(listOfNotNull(c.year?.toString(), if (c.mediaType == "tv") "Series" else "Film").joinToString(" · "), "faint", "small")),
                    label(c.overview ?: "", "muted", "small").apply { isWrapText = true; maxWidth = 540.0; maxHeight = 34.0 },
                )).apply {
                    styleClass.addAll("card-row", "hover-row")
                    if (current?.first == c.mediaType && current.second == c.id) styleClass += "selected"
                    pad(8.0, 10.0)
                    onClick { done { Metadata.chooseMatch(r.apiName, r.url, c) } }
                }
            })
        }
    }
}
