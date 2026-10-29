package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Catalogs
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.Settings
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ProgressIndicator
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

class HomePage : StackPane(), Refreshable {
    /** A chip: one extension, or the combined feed. */
    private data class Source(val key: String, val label: String, val apis: List<MainAPI>)

    private val chips = ChipGroup { i -> sources.getOrNull(i)?.let { select(it) } }
    private val body = VBox(34.0).apply { pad(8.0, 36.0, 40.0, 36.0) }
    private val sections = TreeMap<Int, Node>()
    private var providers: List<MainAPI> = emptyList()
    private var sources: List<Source> = emptyList()
    private var current: Source? = null
    private var job: Job? = null
    private var hero: Node? = null
    private var heroIndex = Int.MAX_VALUE
    private var continueShelf: Node? = null
    private var loading = false

    init {
        val refresh = button(null, Icons.REFRESH, "btn-ghost") { current?.let { select(it, force = true) } }.tooltip("Reload")
        val customize = button("Customize", Icons.TUNE, "btn-ghost") {
            Nav.window.showOverlay(HomeManager(providers, null))
        }.tooltip("Choose extensions and catalogs for Home").apply { id = "home-customize" }
        // Chips scroll sideways instead of squeezing when many extensions are installed.
        val chipScroll = javafx.scene.control.ScrollPane(chips).apply {
            styleClass += "chip-scroll"
            hbarPolicy = javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER
            isFitToHeight = true
            javafx.scene.layout.HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS)
        }
        val header = hbox(14.0, label("Home", "h2"), Region().apply { minWidth = 12.0 }, chipScroll, customize, refresh).pad(22.0, 36.0, 10.0, 36.0)
        val scroll = pageScroll(VBox(header, body))
        children += scroll
        ui.launch { PluginManager.version.collect { refreshProviders() } }
        Library.onChange { ui.launch { if (scene != null) renderContinue() } }
        // Saved in the Home manager: reload, opening whichever extension or feed was picked there.
        Catalogs.onChange { ui.launch { current = null; refreshProviders(force = true) } }
    }

    override fun onShow() {
        renderContinue()
        render()
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
        chips.setItems(sources.map { it.label }, preferred)
        sources.getOrNull(preferred)?.let { select(it, force = true) } ?: run { current = null; render() }
    }

    private fun select(source: Source, force: Boolean = false) {
        if (source == current && !force) return
        current = source
        Settings.lastHomeProvider = source.key
        job?.cancel()
        sections.clear()
        (hero as? HeroSlider)?.stop()
        hero = null
        heroIndex = Int.MAX_VALUE
        loading = true
        render()
        job = ui.launch {
            val mixed = source.apis.size > 1
            Engine.home(source.apis).collect { row ->
                // Feature the first item of the first section, whichever order sections arrive in.
                if (row.order < heroIndex && row.list.list.isNotEmpty()) {
                    (hero as? HeroSlider)?.stop()
                    val featured = row.list.list.take(if (Settings.heroSlider) 6 else 1)
                    hero = if (featured.size > 1) HeroSlider(row.api, featured) else hero(row.api, featured.first())
                    heroIndex = row.order
                }
                sections[row.order] = shelf(row, mixed)
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
                add(WideCard(e.name, e.episodeLabel ?: "${(e.duration - e.position).div(60).toInt()} min left", e.posterUrl, e.progress) {
                    Nav.open(e.apiName, e.url, e.name, e.posterUrl)
                })
            }
        }
        render()
    }

    private fun render() {
        val nodes = mutableListOf<Node>()
        if (providers.isEmpty() && !PluginManager.progress.value.running) {
            nodes += emptyState(
                Icons.EXTENSION, "No extensions yet",
                "Add a repository and install extensions to fill your home screen. CloudStream repositories for Android work here unchanged.",
                button("Open Extensions", Icons.EXTENSION, "btn-accent") { Nav.window.show(Tab.EXTENSIONS) },
            )
        }
        hero?.let { nodes += it }
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
    }

    /**
     * The featured banner, rotating through several titles with a crossfade every 8 s.
     * It only ticks while actually on screen, so a hidden home page costs nothing.
     */
    private inner class HeroSlider(private val api: MainAPI, private val items: List<SearchResponse>) : StackPane() {
        private var index = 0
        private val dots = hbox(7.0).apply {
            alignment = Pos.CENTER
            isPickOnBounds = false
            // Only as big as the dots themselves, so it sits at the banner's bottom edge.
            maxHeight = Region.USE_PREF_SIZE
            maxWidth = Region.USE_PREF_SIZE
        }
        private val timer = javafx.animation.Timeline(javafx.animation.KeyFrame(javafx.util.Duration.seconds(8.0), {
            if (onScreen()) show(index + 1)
        })).apply { cycleCount = javafx.animation.Animation.INDEFINITE }

        init {
            minHeight = 390.0; maxHeight = 390.0
            children += hero(api, items[0])
            children += dots
            StackPane.setAlignment(dots, Pos.BOTTOM_CENTER)
            StackPane.setMargin(dots, javafx.geometry.Insets(0.0, 0.0, 16.0, 0.0))
            renderDots()
            timer.play()
        }

        private fun onScreen(): Boolean =
            scene?.window?.isShowing == true && generateSequence(this as Node) { it.parent }.all { it.isVisible }

        fun show(i: Int) {
            index = (i + items.size) % items.size
            val next = hero(api, items[index]).apply { opacity = 0.0 }
            val old = children.firstOrNull { it !== dots }
            children.add(children.indexOf(dots), next)
            javafx.animation.FadeTransition(javafx.util.Duration.millis(650.0), next).apply {
                toValue = 1.0
                setOnFinished { old?.let { children.remove(it) } }
                play()
            }
            renderDots()
            timer.playFromStart()
        }

        private fun renderDots() {
            dots.children.setAll(items.indices.map { i ->
                Region().apply {
                    styleClass += "hero-dot"
                    if (i == index) styleClass += "active"
                    onClick { if (i != index) show(i) }
                }
            })
        }

        fun stop() = timer.stop()
    }

    private fun hero(api: MainAPI, item: SearchResponse): Node {
        val poster = ImageView()
        Images.load(poster, item.posterUrl, 210.0, 315.0, item.posterHeaders)
        val posterFrame = StackPane(poster).apply { styleClass += "poster-frame"; setMaxSize(210.0, 315.0) }
        roundedClip(posterFrame, 12.0)
        posterFrame.style = "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 30, 0.2, 0, 8);"
        val meta = listOfNotNull(item.type?.name, qualityText(item.quality), item.score?.toDouble(10)?.takeIf { it > 0 }?.let { "★ %.1f".format(it) })
        val text = vbox(14.0,
            label(api.name.uppercase(), "section-label"),
            label(item.name, "hero-title").apply { isWrapText = true; maxWidth = 620.0 },
            label(meta.joinToString("   •   "), "hero-meta"),
            hbox(10.0,
                button("Watch now", Icons.PLAY, "btn-primary") { Nav.open(api, item) },
                button("Details", Icons.INFO, "btn-ghost") { Nav.open(api, item) },
            ).pad(10.0, 0.0, 0.0, 0.0),
        ).apply { alignment = Pos.CENTER_LEFT }
        val shade = Region().apply {
            style = "-fx-background-color: linear-gradient(to right, -cs-bg-96 0%, -cs-bg-85 45%, -cs-bg-0 100%);"
        }
        val layout = hbox(40.0, text, spacer(), posterFrame).pad(36.0, 56.0, 36.0, 48.0)
        return StackPane(backdrop(item.posterUrl, item.posterHeaders, 0.3), shade, layout).apply {
            styleClass += "hero"
            minHeight = 390.0
            maxHeight = 390.0
            roundedClip(this, 18.0)
            style = "-fx-background-color: -cs-surface;"
        }
    }
}

/** "View all" for one home section, with paging when the provider supports it. */
class GridPage(private val api: MainAPI, title: String, private val section: MainPageData, first: List<SearchResponse>) : StackPane(), Disposable {
    private val grid = FlowPane(18.0, 22.0)
    private var page = 1
    private val more = button("Load more", Icons.REFRESH, "btn-ghost") { loadMore() }
    private var job: Job? = null

    init {
        val header = hbox(14.0, button(null, Icons.BACK, "btn-ghost") { Nav.window.back() }, label(title, "h2"), label(api.name, "tag"))
        first.forEach { add(it) }
        children += pageScroll(vbox(24.0, header, grid, more).pad(22.0, 36.0, 40.0, 36.0))
    }

    private fun add(item: SearchResponse) {
        grid.children += PosterCard.of(item) { Nav.open(api, item) }
    }

    private fun loadMore() {
        more.isDisable = true
        more.text = "Loading…"
        job = ui.launch {
            val response = Engine.homePage(api, section, ++page)
            val items = response?.items?.flatMap { it.list }.orEmpty()
            items.forEach { add(it) }
            more.isDisable = false
            more.text = "Load more"
            more.isVisible = items.isNotEmpty() && response?.hasNext != false
        }
    }

    override fun dispose() {
        job?.cancel()
    }
}
