package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Catalogs
import com.lagradost.cloudstream3.linux.ContentFilter
import com.lagradost.cloudstream3.linux.Profiles
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.Settings
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.effect.GaussianBlur
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.TreeMap

/**
 * Blurred, darkened backdrop. The image is decoded tiny and stretched, which looks like
 * a heavy blur at almost no cost; a light GaussianBlur smooths the upscaling.
 */
fun backdrop(url: String?, headers: Map<String, String>? = null, darkness: Double = 0.55): Node {
    val view = ImageView()
    Images.load(view, url, 96.0, 54.0, headers)
    view.isPreserveRatio = false
    view.effect = GaussianBlur(18.0)
    view.opacity = 1.0 - darkness
    val holder = StackPane(view).apply { isMouseTransparent = true; minWidth = 0.0; minHeight = 0.0 }
    view.fitWidthProperty().bind(holder.widthProperty().add(80))
    view.fitHeightProperty().bind(holder.heightProperty().add(80))
    holder.clip = javafx.scene.shape.Rectangle().apply {
        widthProperty().bind(holder.widthProperty()); heightProperty().bind(holder.heightProperty())
    }
    return holder
}

/**
 * Home: a full-width featured banner under the top bar, then Continue Watching and the
 * catalogs. The source picker in the top bar chooses one extension or the combined feed.
 */
class HomePage : StackPane(), Refreshable, TopBarActions {
    /** One extension, or the combined feed. */
    private data class Source(val key: String, val label: String, val apis: List<MainAPI>)

    private val heroHolder = StackPane().apply { minWidth = 0.0 }
    private val body = VBox(34.0).apply { pad(0.0, 40.0, 48.0, 40.0) }
    private val scroll = pageScroll(VBox(heroHolder, body))
    private val sections = TreeMap<Int, Node>()
    private var providers: List<MainAPI> = emptyList()
    private var sources: List<Source> = emptyList()
    private var current: Source? = null
    private var job: Job? = null
    private var hero: Hero? = null
    private var heroIndex = Int.MAX_VALUE
    private var continueShelf: Node? = null
    private var loading = false
    private val sourceButton = button("", null, "topbar-source") { showSourceMenu() }.apply {
        contentDisplay = javafx.scene.control.ContentDisplay.RIGHT
        graphic = icon(Icons.EXPAND, 12.0)
        id = "home-source"
    }

    init {
        children += scroll
        // The top bar turns solid once the banner has scrolled away beneath it.
        scroll.vvalueProperty().addListener { _, _, _ -> updateTopBar() }
        ui.launch { PluginManager.version.collect { refreshProviders() } }
        Library.onChange { ui.launch { if (scene != null) renderContinue() } }
        // Saved in the Home manager: reload, opening whichever extension or feed was picked there.
        Catalogs.onChange { ui.launch { current = null; refreshProviders(force = true) } }
    }

    override fun topBarActions(): List<Node> = listOf(sourceButton)

    private fun updateTopBar() {
        if (scene == null) return
        val offset = scroll.vvalue * ((scroll.content as Region).height - scroll.viewportBounds.height).coerceAtLeast(0.0)
        Nav.window.setTopBarSolid(hero == null || offset > heroHolder.height - TopBar.HEIGHT - 40)
    }

    override fun onShow() {
        renderContinue()
        render()
        hero?.resume()
    }

    private fun showSourceMenu() {
        val rows = sources.map { src ->
            Popover.row(if (src.key == FEED) icon(Icons.APPS, 18.0) else label(src.label.filter { it.isLetterOrDigit() }.take(2).uppercase(), "provider-badge", "small-badge"),
                src.label, if (src.key == FEED) "${src.apis.size} extensions" else null, selected = src == current) { select(src) }
        }.toMutableList()
        if (rows.isNotEmpty()) rows += Popover.separator()
        if (!Profiles.isKids) rows += Popover.row(icon(Icons.TUNE, 18.0), "Customize home…") { Nav.window.showOverlay(HomeManager(providers, null)) }
        rows += Popover.row(icon(Icons.REFRESH, 18.0), "Reload") { current?.let { select(it, force = true) } }
        Popover.show(sourceButton, Popover.panel(*rows.toTypedArray()))
    }

    private fun refreshProviders(force: Boolean = false) {
        val fresh = Engine.providers().filter { it.hasMainPage }
        val feed = Catalogs.feedProviders(fresh).takeIf { Catalogs.multiFeed && it.isNotEmpty() }
        val fresher = listOfNotNull(feed?.let { Source(FEED, "My feed", it) }) + fresh.map { Source(it.name, it.name, listOf(it)) }
        if (!force && fresher == sources && current != null) return
        providers = fresh
        sources = fresher
        val wanted = current?.key ?: Settings.lastHomeProvider
        val preferred = sources.indexOfFirst { it.key == wanted }.coerceAtLeast(0)
        sources.getOrNull(preferred)?.let { select(it, force = true) } ?: run {
            current = null; job?.cancel(); sections.clear(); hero?.stop(); hero = null; loading = false
            sourceButton.isVisible = false
            render()
        }
    }

    private fun select(source: Source, force: Boolean = false) {
        if (source == current && !force) return
        current = source
        sourceButton.text = source.label
        sourceButton.isVisible = sources.size > 1 || source.key == FEED
        Settings.lastHomeProvider = source.key
        job?.cancel()
        sections.clear()
        hero?.stop()
        hero = null
        heroIndex = Int.MAX_VALUE
        loading = true
        render()
        job = ui.launch {
            val mixed = source.apis.size > 1
            Engine.home(source.apis).collect { row ->
                val items = row.list.list
                if (items.isEmpty()) return@collect
                // Feature the first items of the first catalog, whichever order catalogs arrive in.
                if (row.order < heroIndex) {
                    hero?.stop()
                    hero = Hero(row.api, items.take(if (Settings.heroSlider) 6 else 1), row.list.name)
                    heroIndex = row.order
                }
                sections[row.order] = shelf(row.copy(list = row.list.copy(list = items)), mixed)
                render()
            }
            loading = false
            render()
        }
    }

    private fun renderContinue() {
        val items = Library.continueWatching()
        continueShelf = if (items.isEmpty()) null else Shelf("Continue Watching").apply {
            items.take(20).forEach { e ->
                add(WideCard(e.name, e.episodeLabel ?: "${(e.duration - e.position).div(60).toInt()} min left", e.posterUrl, e.progress,
                    continueActions(e)) { Nav.open(e.apiName, e.url, e.name, e.posterUrl) })
            }
        }
        render()
    }

    private fun render() {
        val nodes = mutableListOf<Node>()
        val noProviders = providers.isEmpty() && !PluginManager.progress.value.running
        when {
            noProviders && Profiles.isKids -> nodes += emptyState(Icons.FACE, "Nothing here yet",
                "Ask a grown-up to allow some extensions for this profile (Manage profiles → edit → Allowed extensions).")
            noProviders -> nodes += emptyState(
                Icons.EXTENSION, "No extensions yet",
                "Add a CloudStream repository or a Stremio add-on to fill your home screen. CloudStream repositories for Android work here unchanged.",
                button("Open Extensions", Icons.EXTENSION, "btn-accent") { Nav.window.show(Tab.EXTENSIONS) },
            )
        }
        val h = hero
        if (h != null) {
            heroHolder.children.setAll(h)
            // The first row tucks up into the banner's fade, as on streaming sites.
            VBox.setMargin(body, javafx.geometry.Insets(-86.0, 0.0, 0.0, 0.0))
        } else {
            heroHolder.children.setAll(Region().apply { minHeight = TopBar.HEIGHT + 12 })
            VBox.setMargin(body, javafx.geometry.Insets.EMPTY)
        }
        continueShelf?.let { nodes += it }
        nodes += sections.values
        if (loading) {
            if (sections.isEmpty()) repeat(3) { nodes += skeletonShelf() }
            else nodes += hbox(10.0, ProgressIndicator().apply { setPrefSize(18.0, 18.0) }, label("Loading more sections…", "muted"))
        } else if (current != null && sections.isEmpty()) {
            val all = current?.apis.orEmpty()
            nodes += if (all.isNotEmpty() && all.all { Catalogs.enabled(it).isEmpty() }) emptyState(Icons.TUNE, "All catalogs are hidden",
                "You turned off every catalog for ${current?.label}.",
                button("Customize", Icons.TUNE, "btn-accent") { Nav.window.showOverlay(HomeManager(providers, all.singleOrNull())) })
            else emptyState(Icons.INFO, "Nothing here", "${current?.label} returned no home sections. Its site may be down or blocked — try Settings → Network & DNS, or use Search.")
        }
        body.children.setAll(nodes)
        javafx.application.Platform.runLater { updateTopBar() }
    }

    private fun shelf(row: Engine.HomeSection, mixed: Boolean): Node {
        val (_, api, section, list) = row
        return Shelf(list.name, onViewAll = { Nav.window.push(GridPage(api, list.name, section, list.list)) },
            trailing = if (mixed) label(api.name, "tag") else null).apply {
            list.list.forEach { item -> add(PosterCard.of(item) { Nav.open(api, item) }) }
        }
    }

    companion object {
        const val FEED = "__feed__"

        /** Full details for featured titles (backdrop, logo, plot…), kept for the session. */
        private val details = object : LinkedHashMap<String, com.lagradost.cloudstream3.LoadResponse>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.lagradost.cloudstream3.LoadResponse>) = size > 40
        }
        /** TMDB etc. for featured titles whose extension lacks artwork. */
        private val extras = java.util.concurrent.ConcurrentHashMap<String, com.lagradost.cloudstream3.linux.Metadata.Info>()
    }

    /**
     * The featured banner across the full width, rotating through the first catalog's top
     * titles every 9 s. Each title's details load in the background for a wide backdrop,
     * logo and plot; until then (or without them) the poster stands in.
     */
    private inner class Hero(private val api: MainAPI, private val items: List<SearchResponse>, private val rowName: String) : StackPane() {
        private var index = 0
        private val slides = StackPane()
        private val dots = hbox(7.0).apply {
            alignment = Pos.CENTER_RIGHT
            isPickOnBounds = false
            maxHeight = Region.USE_PREF_SIZE
            maxWidth = Region.USE_PREF_SIZE
        }
        private val timer = javafx.animation.Timeline(javafx.animation.KeyFrame(javafx.util.Duration.seconds(9.0), {
            if (onScreen()) show(index + 1)
        })).apply { cycleCount = javafx.animation.Animation.INDEFINITE }
        private val loads = mutableListOf<Job>()

        init {
            styleClass += "hero-full"
            // Tall, but leave room for the first row to peek in.
            val height = Nav.window.heightProperty().map { (it.toDouble() * 0.74).coerceIn(500.0, 760.0) }
            minHeightProperty().bind(height); prefHeightProperty().bind(height); maxHeightProperty().bind(height)
            minWidth = 0.0
            // A soft mask: the banner itself fades out at the bottom, so it melts into any
            // background (the glass backdrop and wallpapers too, not only plain colours).
            clip = javafx.scene.shape.Rectangle().apply {
                widthProperty().bind(this@Hero.widthProperty()); heightProperty().bind(this@Hero.heightProperty())
                fill = javafx.scene.paint.LinearGradient(0.0, 0.0, 0.0, 1.0, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                    javafx.scene.paint.Stop(0.0, javafx.scene.paint.Color.BLACK), javafx.scene.paint.Stop(0.78, javafx.scene.paint.Color.BLACK),
                    javafx.scene.paint.Stop(1.0, javafx.scene.paint.Color.TRANSPARENT))
            }
            children += slides
            if (items.size > 1) {
                children += dots
                StackPane.setAlignment(dots, Pos.BOTTOM_RIGHT)
                StackPane.setMargin(dots, javafx.geometry.Insets(0.0, 44.0, 124.0, 0.0))
            }
            slides.children += slide(0)
            renderDots()
            if (items.size > 1) timer.play()
            // Fetch details for the first slides ahead of time.
            items.take(3).forEach { fetch(it) }
        }

        private fun onScreen(): Boolean =
            scene?.window?.isShowing == true && generateSequence(this as Node) { it.parent }.all { it.isVisible }

        private fun fetch(item: SearchResponse) {
            if (synchronized(details) { details[item.url] } != null) return
            loads += ui.launch {
                val r = runCatching { kotlinx.coroutines.withTimeoutOrNull(12_000) { Engine.load(api, item.url) } }.getOrNull() ?: return@launch
                synchronized(details) { details[item.url] = r }
                // Upgrade the slide on screen in place.
                if (items.getOrNull(index)?.url == item.url) slides.children.setAll(slide(index))
                val m = com.lagradost.cloudstream3.linux.Metadata
                if (m.hasTmdb || m.cinemeta || m.anilist) {
                    runCatching { m.details(r) }.getOrNull()?.let { extras[item.url] = it }
                    if (items.getOrNull(index)?.url == item.url && extras[item.url] != null) slides.children.setAll(slide(index))
                }
            }
        }

        fun show(i: Int) {
            index = (i + items.size) % items.size
            val next = slide(index).apply { opacity = 0.0 }
            val old = slides.children.lastOrNull()
            slides.children += next
            javafx.animation.FadeTransition(javafx.util.Duration.millis(700.0), next).apply {
                toValue = 1.0
                setOnFinished { old?.let { slides.children.remove(it) } }
                play()
            }
            renderDots()
            timer.playFromStart()
            items.getOrNull(index + 1)?.let { fetch(it) }
        }

        fun resume() { if (items.size > 1) timer.play() }

        private fun renderDots() {
            dots.children.setAll(items.indices.map { i ->
                Region().apply {
                    styleClass += "hero-dot"
                    if (i == index) styleClass += "active"
                    onClick { if (i != index) show(i) }
                }
            })
        }

        fun stop() {
            timer.stop()
            loads.forEach { it.cancel() }
        }

        private fun slide(i: Int): Node {
            val item = items[i]
            val r = synchronized(details) { details[item.url] }
            val ex = extras[item.url]
            val art = com.lagradost.cloudstream3.linux.Metadata.artwork
            val wide = r?.backgroundPosterUrl ?: ex?.backdrop?.takeIf { art }
            val layers = mutableListOf<Node>()
            if (wide != null) {
                // The backdrop covers the banner (like CSS background-size: cover), anchored top right.
                val view = ImageView()
                Images.load(view, wide, 1600.0, 900.0, r?.posterHeaders?.takeIf { r.backgroundPosterUrl != null })
                val holder = StackPane(view).apply { minWidth = 0.0; minHeight = 0.0; alignment = Pos.TOP_RIGHT; isMouseTransparent = true }
                val cover = javafx.beans.binding.Bindings.createDoubleBinding({
                    maxOf(holder.width, holder.height * 16 / 9)
                }, holder.widthProperty(), holder.heightProperty())
                view.fitWidthProperty().bind(cover)
                view.fitHeightProperty().bind(cover.multiply(9.0 / 16))
                layers += holder
            } else {
                layers += backdrop(item.posterUrl, item.posterHeaders, 0.25)
            }
            layers += Region().apply { styleClass += "hero-shade-left"; isMouseTransparent = true }
            layers += Region().apply { styleClass += "hero-shade-bottom"; isMouseTransparent = true }
            layers += Region().apply { styleClass += "hero-shade-top"; isMouseTransparent = true }

            val source = hbox(8.0, Logo(22.0), label(api.name, "hero-source"))
            val title: Node = (r?.logoUrl ?: ex?.logo?.takeIf { art })?.let { logo ->
                val view = ImageView().apply { isPreserveRatio = true; fitWidth = 460.0; fitHeight = 150.0 }
                view.image = javafx.scene.image.Image(logo, 920.0, 300.0, true, true, true)
                StackPane(view).apply { alignment = Pos.BOTTOM_LEFT; maxWidth = 460.0; minHeight = 90.0 }
            } ?: label(r?.name ?: item.name, "hero-big-title").apply { isWrapText = true; maxWidth = 640.0 }
            val type = r?.type ?: item.type
            val episodes = (r as? com.lagradost.cloudstream3.EpisodeResponse)?.let { Engine.playables(r) }
            val seasons = episodes?.mapNotNull { it.season }?.distinct()?.size?.takeIf { it > 0 }
            val meta = hbox(14.0).apply {
                styleClass += "hero-meta-row"
                (r?.year ?: (item as? com.lagradost.cloudstream3.MovieSearchResponse)?.year)?.let { children += label("$it", "hero-meta-item") }
                when {
                    seasons != null && seasons > 1 -> children += label("$seasons Seasons", "hero-meta-item")
                    episodes != null && episodes.size > 1 -> children += label("${episodes.size} Episodes", "hero-meta-item")
                    (r?.duration ?: 0) > 0 -> children += label(r!!.duration!!.let { d -> if (d >= 60) "${d / 60}h ${d % 60}m" else "${d}m" }, "hero-meta-item")
                }
                (r?.contentRating ?: ex?.contentRating)?.takeIf { it.isNotBlank() }?.let { children += label(it, "hero-rating") }
                when {
                    ex?.tmdbVote != null -> children += label("TMDB %.1f".format(ex.tmdbVote), "hero-meta-item")
                    ex?.imdbRating != null -> children += label("IMDb %.1f".format(ex.imdbRating), "hero-meta-item")
                    else -> (r?.score ?: item.score)?.toDouble(10)?.takeIf { it > 0 }?.let { children += label("★ %.1f".format(it), "hero-meta-item") }
                }
                if (children.isEmpty()) type?.let { children += label(typeName(it), "hero-meta-item") }
            }
            val rank = hbox(10.0, icon(Icons.TV, 22.0).apply { styleClass += "text-icon" }, label("#${i + 1} in $rowName", "hero-rank"))
            val plot = (r?.plot?.takeIf { it.isNotBlank() } ?: ex?.overview)?.let { label(it, "hero-plot").apply { isWrapText = true; maxWidth = 560.0; maxHeight = 72.0 } }
            val play = button("Play", Icons.PLAY, "hero-play") { play(item, r) }
            val info = button("More Info", Icons.INFO, "hero-info") { Nav.open(api, item) }
            val text = vbox(16.0, *listOfNotNull(source, title, meta, rank, plot, hbox(12.0, play, info).pad(6.0, 0.0, 0.0, 0.0)).toTypedArray()).apply {
                alignment = Pos.BOTTOM_LEFT
                maxWidth = 680.0
                isPickOnBounds = false
            }
            val textLayer = StackPane(text).apply { alignment = Pos.BOTTOM_LEFT; pad(TopBar.HEIGHT + 20, 40.0, 128.0, 40.0); isPickOnBounds = false }
            layers += textLayer
            if (wide == null) {
                // Without a wide picture, the poster stands on the right, as before.
                val poster = ImageView()
                Images.load(poster, item.posterUrl, 230.0, 345.0, item.posterHeaders)
                val frame = StackPane(poster).apply { styleClass += "poster-frame"; setMaxSize(230.0, 345.0) }
                roundedClip(frame, 14.0)
                frame.style = "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 34, 0.2, 0, 10);"
                layers += StackPane(frame).apply { alignment = Pos.CENTER_RIGHT; pad(TopBar.HEIGHT, 72.0, 110.0, 0.0); isPickOnBounds = false }
            }
            return StackPane(*layers.toTypedArray()).apply { minWidth = 0.0 }
        }

        private fun play(item: SearchResponse, r: com.lagradost.cloudstream3.LoadResponse?) {
            val playables = r?.let { Engine.playables(it) }.orEmpty()
            val target = r?.let { playTarget(api.name, item.url, playables) }
            if (r != null && target != null) Playback.open(api, r, target) else Nav.open(api, item)
        }
    }
}

private fun typeName(t: com.lagradost.cloudstream3.TvType) = when (t) {
    com.lagradost.cloudstream3.TvType.TvSeries -> "Series"
    com.lagradost.cloudstream3.TvType.AnimeMovie -> "Anime film"
    com.lagradost.cloudstream3.TvType.AsianDrama -> "Drama"
    else -> t.name
}

/**
 * "View all" for one home section. Pages load by themselves while scrolling (and while the
 * grid doesn't fill the window yet) until the extension has nothing more.
 */
class GridPage(
    private val api: MainAPI,
    title: String,
    private val section: MainPageData,
    first: List<SearchResponse>,
) : StackPane(), Disposable {
    private val grid = FlowPane(18.0, 22.0)
    private var page = 1
    private var loading = false
    private var finished = false
    private val seen = HashSet<String>()
    private val spinner = ProgressIndicator().apply { setPrefSize(22.0, 22.0); setMaxSize(22.0, 22.0) }
    private val status = label("", "muted")
    private val retry = button("Try again", Icons.REFRESH, "btn-ghost") { loadMore() }.apply { isVisible = false; isManaged = false }
    private val footer = hbox(10.0, spinner, status, retry).apply { alignment = Pos.CENTER; minHeight = 44.0 }
    private val scroll: ScrollPane
    private var job: Job? = null

    init {
        val header = hbox(14.0, button(null, Icons.BACK, "btn-ghost") { Nav.window.back() }, label(title, "h2"), label(api.name, "tag"))
        first.forEach { add(it) }
        scroll = pageScroll(vbox(24.0, header, grid, footer).pad(22.0, 36.0, 40.0, 36.0))
        children += scroll
        idle()
        scroll.vvalueProperty().addListener { _, _, _ -> maybeLoad() }
        scroll.viewportBoundsProperty().addListener { _, _, _ -> maybeLoad() }
        grid.heightProperty().addListener { _, _, _ -> maybeLoad() }
    }

    private fun add(item: SearchResponse): Boolean {
        if (!seen.add(item.url)) return false
        grid.children += PosterCard.of(item) { Nav.open(api, item) }
        return true
    }

    /** Loads the next page when the bottom is near: the last ~1.5 screens, or no scrolling yet. */
    private fun maybeLoad() {
        if (loading || finished || retry.isVisible || scene == null) return
        val content = (scroll.content as Region).height
        val viewport = scroll.viewportBounds.height
        if (viewport <= 0) return
        val below = (content - viewport) * (1 - scroll.vvalue)
        if (below < viewport * 1.5) javafx.application.Platform.runLater { if (!loading) loadMore() }
    }

    private fun loadMore() {
        if (loading || finished) return
        loading = true
        retry.isVisible = false; retry.isManaged = false
        spinner.isVisible = true; spinner.isManaged = true
        status.text = "Loading more…"
        job = ui.launch {
            val response = runCatching { Engine.homePage(api, section, page + 1) }.getOrNull()
            loading = false
            if (response == null) {
                spinner.isVisible = false; spinner.isManaged = false
                status.text = "Couldn't load more."
                retry.isVisible = true; retry.isManaged = true
                return@launch
            }
            page++
            val added = response.items.flatMap { it.list }.filter(ContentFilter::allows).count { add(it) }
            // Done when the page was empty, said so, or only repeated what we already have.
            finished = added == 0 || !response.hasNext
            idle()
            if (!finished) maybeLoad()
        }
    }

    private fun idle() {
        spinner.isVisible = false; spinner.isManaged = false
        status.text = if (finished) "That's everything · ${grid.children.size} titles" else ""
    }

    override fun dispose() {
        job?.cancel()
    }
}

/** ✓ mark watched, ✕ remove, ⋯ more: the hover buttons on a Continue Watching card. */
fun continueActions(e: com.lagradost.cloudstream3.linux.WatchEntry): List<CardAction> = listOf(
    CardAction(Icons.CHECK, "Mark as watched") {
        Library.markWatched(e); Toasts.show("Marked ${e.episodeLabel ?: e.name} as watched")
    },
    CardAction(Icons.CLOSE, "Remove from Continue Watching") {
        Library.removeHistory(e.apiName, e.url); Toasts.show("Removed ${e.name} from Continue Watching")
    },
    CardAction(Icons.MORE, "More") { anchor ->
        Popover.show(anchor, Popover.panel(
            Popover.row(icon(Icons.CHECK, 18.0), "Mark as watched") { Library.markWatched(e) },
            Popover.row(icon(Icons.CLOSE, 18.0), "Remove from Continue Watching") { Library.removeHistory(e.apiName, e.url) },
            Popover.separator(),
            Popover.row(icon(Icons.INFO, 18.0), "Details") { Nav.open(e.apiName, e.url, e.name, e.posterUrl) },
        ))
    },
)
