package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
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
    private val chips = ChipGroup { i -> providers.getOrNull(i)?.let { select(it) } }
    private val body = VBox(34.0).apply { pad(8.0, 36.0, 40.0, 36.0) }
    private val sections = TreeMap<Int, Node>()
    private var providers: List<MainAPI> = emptyList()
    private var current: MainAPI? = null
    private var job: Job? = null
    private var hero: Node? = null
    private var heroIndex = Int.MAX_VALUE
    private var continueShelf: Node? = null
    private var loading = false

    init {
        val refresh = button(null, Icons.REFRESH, "btn-ghost") { current?.let { select(it, force = true) } }.tooltip("Reload")
        val header = hbox(14.0, label("Home", "h2"), Region().apply { minWidth = 12.0 }, chips, spacer(), refresh).pad(22.0, 36.0, 10.0, 36.0)
        val scroll = pageScroll(VBox(header, body))
        children += scroll
        ui.launch { PluginManager.version.collect { refreshProviders() } }
        Library.onChange { ui.launch { if (scene != null) renderContinue() } }
    }

    override fun onShow() {
        renderContinue()
        render()
    }

    private fun refreshProviders() {
        val fresh = Engine.providers().filter { it.hasMainPage }
        if (fresh.map { it.name } == providers.map { it.name } && current != null) return
        providers = fresh
        val preferred = providers.indexOfFirst { it.name == (current?.name ?: Settings.lastHomeProvider) }.coerceAtLeast(0)
        chips.setItems(providers.map { it.name }, preferred)
        providers.getOrNull(preferred)?.let { select(it) } ?: render()
    }

    private fun select(api: MainAPI, force: Boolean = false) {
        if (api == current && !force) return
        current = api
        Settings.lastHomeProvider = api.name
        job?.cancel()
        sections.clear()
        hero = null
        heroIndex = Int.MAX_VALUE
        loading = true
        render()
        job = ui.launch {
            Engine.home(api).collect { (index, list) ->
                // Feature the first item of the first section, whichever order sections arrive in.
                if (index < heroIndex) list.list.firstOrNull()?.let { hero = hero(api, it); heroIndex = index }
                sections[index] = shelf(api, index, list)
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
            nodes += emptyState(Icons.INFO, "Nothing here", "${current?.name} returned no home sections. Its site may be down or blocked — try Settings → DNS, or use Search.")
        }
        body.children.setAll(nodes)
    }

    private fun shelf(api: MainAPI, index: Int, list: HomePageList): Node {
        val section = api.mainPage.getOrNull(index / 100)
        return Shelf(list.name, onViewAll = section?.let { { Nav.window.push(GridPage(api, list.name, it, list.list)) } }).apply {
            list.list.forEach { item -> add(PosterCard.of(item) { Nav.open(api, item) }) }
        }
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
            style = "-fx-background-color: linear-gradient(to right, rgba(9,9,12,0.96) 0%, rgba(9,9,12,0.75) 45%, rgba(9,9,12,0.25) 100%);"
        }
        val layout = hbox(40.0, text, spacer(), posterFrame).pad(36.0, 56.0, 36.0, 48.0)
        return StackPane(backdrop(item.posterUrl, item.posterHeaders, 0.3), shade, layout).apply {
            styleClass += "hero"
            minHeight = 390.0
            maxHeight = 390.0
            roundedClip(this, 18.0)
            style = "-fx-background-color: #14141a;"
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
