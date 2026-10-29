package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.Profiles
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.launch

/** Home's sections in the top bar. Each shows the extensions (and items) of its types. */
enum class Section(val label: String, val types: Set<TvType>?) {
    HOME("Home", null),
    MOVIES("Movies", setOf(TvType.Movie, TvType.AnimeMovie, TvType.Torrent)),
    SERIES("Series", setOf(TvType.TvSeries, TvType.AsianDrama)),
    ANIME("Anime", setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)),
    CARTOONS("Cartoons", setOf(TvType.Cartoon)),
    DOCS("Documentaries", setOf(TvType.Documentary)),
    LIVE("Live TV", setOf(TvType.Live));

    fun matches(api: MainAPI) = types == null || api.supportedTypes.any { it in types }

    /** Items without a type are kept: many extensions don't set one. */
    fun matches(item: SearchResponse) = types == null || item.type == null || item.type in types

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: HOME

        /** Sections that at least one usable extension serves. */
        fun available(): List<Section> {
            val apis = Engine.providers().filter { it.hasMainPage }
            return entries.filter { it == HOME || apis.any(it::matches) }
        }
    }
}

/**
 * The bar across the top: sections on the left; page controls, search and the profile on
 * the right. Transparent over Home's banner, solid once something scrolls beneath it.
 */
class TopBar(private val window: AppWindow) : StackPane() {
    private val sections = HBox(4.0).apply { alignment = Pos.CENTER_LEFT }
    private val actions = HBox(10.0).apply { alignment = Pos.CENTER_RIGHT }
    private val search = button("Search", Icons.SEARCH, "topbar-search") {
        window.show(Tab.SEARCH)
        (window.lookup(".search-field") as? javafx.scene.control.TextField)?.requestFocus()
    }
    private val profile = HBox(9.0).apply { styleClass += "topbar-profile"; alignment = Pos.CENTER }
    private var page: Node? = null
    private var tab = Tab.HOME

    init {
        styleClass += "topbar"
        minHeight = HEIGHT; maxHeight = HEIGHT
        val row = hbox(18.0, sections, spacer(), actions, search, profile).pad(0.0, 28.0, 0.0, 30.0)
        children += row
        isPickOnBounds = false
        row.isPickOnBounds = false
        render()
        ui.launch { PluginManager.version.collect { renderSections() } }
    }

    fun render() {
        renderSections()
        val p = Profiles.current
        profile.children.setAll(Avatars.node(p.avatar, 30.0, round = true), label(p.name, "topbar-name"), icon(Icons.EXPAND, 12.0))
        profile.onClick { ProfileMenu.show(profile) }
    }

    private fun renderSections() {
        val home = (page as? HomePage)?.takeIf { tab == Tab.HOME }
        sections.children.setAll(Section.available().map { s ->
            label(s.label, "topnav-item").apply {
                if (home != null && home.section == s) styleClass += "active"
                onClick { window.showSection(s) }
            }
        })
    }

    fun pageChanged(node: Node, tab: Tab) {
        page = node
        this.tab = tab
        actions.children.setAll((node as? TopBarActions)?.topBarActions().orEmpty())
        search.isVisible = tab != Tab.SEARCH
        search.isManaged = search.isVisible
        renderSections()
    }

    fun setSolid(solid: Boolean) {
        styleClass.remove("solid")
        if (solid) styleClass += "solid"
    }

    companion object {
        const val HEIGHT = 68.0
    }
}

/**
 * A small floating panel under [anchor] (menus in the top bar). Drawn inside the window
 * rather than as a separate popup window, so it follows the theme like everything else.
 */
object Popover {
    fun show(anchor: Node, panel: Region, alignRight: Boolean = true) {
        val bounds = anchor.localToScene(anchor.boundsInLocal)
        val layer = Pane().apply {
            styleClass += "popover-layer"
            onClick { Nav.window.closeOverlay() }
        }
        panel.styleClass += "popover"
        panel.setOnMouseClicked { it.consume() }
        panel.applyCss()
        panel.autosize()
        layer.children += panel
        val sceneWidth = anchor.scene.width
        panel.layoutY = bounds.maxY + 8
        panel.layoutXProperty().bind(panel.widthProperty().map { w ->
            if (alignRight) (bounds.maxX - w.toDouble()).coerceAtLeast(8.0) else bounds.minX.coerceAtMost(sceneWidth - w.toDouble() - 8)
        })
        Nav.window.showOverlay(layer)
    }

    /** A menu row: optional leading node, text, optional trailing text. */
    fun row(leading: Node?, text: String, trailing: String? = null, selected: Boolean = false, action: () -> Unit): Node =
        hbox(10.0, *listOfNotNull(leading, label(text, "popover-text"), spacer(), trailing?.let { label(it, "faint", "small") }).toTypedArray()).apply {
            styleClass += "popover-row"
            if (selected) styleClass += "selected"
            onClick { Nav.window.closeOverlay(); action() }
        }

    fun separator(): Node = Region().apply { styleClass += "popover-separator" }

    fun panel(vararg rows: Node): VBox = VBox(2.0, *rows).apply { padding = Insets(8.0); minWidth = 240.0 }
}
