package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.linux.Catalogs
import com.lagradost.cloudstream3.linux.Settings
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.input.ClipboardContent
import javafx.scene.input.TransferMode
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * Home manager: pick which extension Home shows, or combine several into one feed, and
 * per extension turn catalogs (its home sections) on/off and put them in any order.
 * Edits stay in this dialog until "Save & close", then apply in one go.
 */
class HomeManager(private val providers: List<MainAPI>, focus: MainAPI?) : StackPane(), Disposable {
    private val edits = LinkedHashMap<MainAPI, MutableList<Catalogs.Entry>>()
    private var multiFeed = Catalogs.multiFeed
    private val feed = Catalogs.feedProviders(providers).map { it.name }.toMutableList()
    private var picked: String? = null
    private var typeFilter = 0
    private val title = label("", "h2")
    private val subtitle = label("", "muted")
    private val body = VBox(14.0)
    private val footer = hbox(10.0).apply { alignment = Pos.CENTER_RIGHT }

    private val types: List<Pair<String, Set<TvType>?>> = listOf(
        "All" to null,
        "Movies" to setOf(TvType.Movie, TvType.AnimeMovie),
        "Series" to setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon),
        "Anime" to setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA),
        "Docs" to setOf(TvType.Documentary),
        "Live" to setOf(TvType.Live),
    )

    init {
        val scrim = Region().apply { styleClass += "scrim"; onClick { Nav.window.closeOverlay() } }
        val scroll = pageScroll(body).apply {
            maxHeightProperty().bind(Nav.window.heightProperty().multiply(0.66))
            prefViewportHeightProperty().bind(body.heightProperty())
        }
        val sheet = VBox(16.0, vbox(4.0, title, subtitle), scroll, footer).apply {
            styleClass += "sheet"
            pad(26.0, 28.0, 22.0, 28.0)
            maxWidth = 980.0
            maxHeight = Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
        if (focus != null) showCatalogs(focus) else showProviders()
    }

    private fun entries(api: MainAPI) = edits.getOrPut(api) { Catalogs.all(api).toMutableList() }

    // ---------- page 1: providers ----------

    private fun showProviders() {
        title.text = "Home screen"
        subtitle.text = if (multiFeed) "Pick extensions for your feed, in order. Use the tune button to choose each one's catalogs."
            else "Choose the extension Home opens with. Use the tune button to choose its catalogs."
        val feedSwitch = hbox(12.0, vbox(2.0, label("Combine extensions into one feed", "bold"),
            label("Show catalogs from several extensions together on Home", "faint", "small")), spacer(),
            Switch(multiFeed) { multiFeed = it; showProviders() })
        val chips = ChipGroup { typeFilter = it; showProviders() }.apply { setItems(types.map { it.first }, typeFilter) }
        val wanted = types[typeFilter].second
        val shown = providers.filter { wanted == null || it.supportedTypes.any { t -> t in wanted } }
        val grid = FlowPane(12.0, 12.0).apply { children.setAll(shown.map { providerCard(it) }) }
        body.children.setAll(listOfNotNull(
            feedSwitch.apply { styleClass += "card-row" },
            if (multiFeed) feedOrder() else null,
            chips,
            if (shown.isEmpty()) label("No installed extension offers this type.", "muted") else grid,
        ))
        footer.children.setAll(button("Cancel", null, "btn-ghost") { Nav.window.closeOverlay() }, saveButton())
    }

    private fun providerCard(api: MainAPI): Node {
        val all = entries(api)
        val on = all.count { it.enabled }
        val inFeed = api.name in feed
        val active = if (multiFeed) inFeed else api.name == (picked ?: Settings.lastHomeProvider)
        val badge = label(api.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "provider-badge")
        val text = vbox(2.0,
            label(api.name, "bold").apply { maxWidth = 170.0 },
            label(if (on == all.size) "${all.size} catalogs" else "$on of ${all.size} catalogs", "faint", "small"),
        ).apply { minWidth = 0.0 }
        val position = if (multiFeed && inFeed) label("${feed.indexOf(api.name) + 1}", "feed-number") else null
        val tune = button(null, Icons.TUNE, "btn-ghost", "btn-small") { showCatalogs(api) }.apply {
            tooltip("Customize ${api.name}'s catalogs")
            id = "tune-" + api.name.filter { it.isLetterOrDigit() }
            addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED) { it.consume() }
        }
        return hbox(12.0, *listOfNotNull(badge, text, spacer(), position, tune).toTypedArray()).apply {
            styleClass += "provider-card"
            if (active) styleClass += "selected"
            prefWidth = 290.0
            onClick {
                if (multiFeed) { if (!feed.remove(api.name)) feed += api.name } else picked = api.name
                showProviders()
            }
        }
    }

    /** The feed's extensions in order, with arrows to move them. */
    private fun feedOrder(): Node {
        if (feed.isEmpty()) return label("Click extensions below to add them to your feed.", "muted")
        val rows = feed.mapIndexed { i, name ->
            hbox(10.0,
                label("${i + 1}", "feed-number"), label(name, "bold"), spacer(),
                button(null, Icons.ARROW_UP, "btn-ghost", "btn-small") { move(feed, i, -1); showProviders() }.apply { isDisable = i == 0 },
                button(null, Icons.ARROW_DOWN, "btn-ghost", "btn-small") { move(feed, i, 1); showProviders() }.apply { isDisable = i == feed.lastIndex },
                button(null, Icons.CLOSE, "btn-ghost", "btn-small") { feed.removeAt(i); showProviders() }.tooltip("Remove from feed"),
            ).apply { styleClass += "catalog-row" }
        }
        return vbox(8.0, label("YOUR FEED", "section-label"), *rows.toTypedArray())
    }

    // ---------- page 2: one provider's catalogs ----------

    private fun showCatalogs(api: MainAPI) {
        val list = entries(api)
        title.text = "Customize ${api.name}"
        subtitle.text = "Turn catalogs on or off, and drag (or use the arrows) to change their order on Home."
        val rows = VBox(8.0)
        fun render() {
            rows.children.setAll(list.mapIndexed { i, entry -> catalogRow(list, i, entry, ::render) })
        }
        render()
        val bulk = hbox(10.0,
            label("${list.size} catalogs", "muted"), spacer(),
            button("All on", null, "btn-ghost", "btn-small") { list.replaceAll { it.copy(enabled = true) }; render() },
            button("All off", null, "btn-ghost", "btn-small") { list.replaceAll { it.copy(enabled = false) }; render() },
            button("Default order", Icons.REFRESH, "btn-ghost", "btn-small") {
                val sorted = list.sortedBy { it.index }
                list.clear(); list.addAll(sorted); render()
            },
        )
        body.children.setAll(
            if (list.isEmpty()) label("${api.name} has no catalogs.", "muted") else bulk,
            rows,
        )
        footer.children.setAll(button("Back", Icons.BACK, "btn-ghost") { showProviders() }, saveButton())
    }

    private fun catalogRow(list: MutableList<Catalogs.Entry>, i: Int, entry: Catalogs.Entry, render: () -> Unit): Node {
        val handle = icon(Icons.DRAG, 18.0).apply { styleClass += "drag-handle" }
        val name = entry.section.name.ifBlank { "Home" }
        val row = hbox(12.0,
            handle,
            label("${i + 1}.", "faint").apply { minWidth = 26.0 },
            label(name, if (entry.enabled) "bold" else "muted"),
            spacer(),
            button(null, Icons.ARROW_UP, "btn-ghost", "btn-small") { move(list, i, -1); render() }.apply { isDisable = i == 0 },
            button(null, Icons.ARROW_DOWN, "btn-ghost", "btn-small") { move(list, i, 1); render() }.apply { isDisable = i == list.lastIndex },
            Switch(entry.enabled) { on -> list[list.indexOf(entry)] = entry.copy(enabled = on); render() },
        ).apply { styleClass += "catalog-row"; if (!entry.enabled) styleClass += "off" }

        // Drag and drop to reorder.
        row.setOnDragDetected { e ->
            row.startDragAndDrop(TransferMode.MOVE).setContent(ClipboardContent().apply { putString(i.toString()) })
            e.consume()
        }
        row.setOnDragOver { e ->
            if (e.dragboard.hasString() && e.gestureSource != row) e.acceptTransferModes(TransferMode.MOVE)
            e.consume()
        }
        row.setOnDragEntered { if (it.gestureSource != row) row.styleClass += "drop-target" }
        row.setOnDragExited { row.styleClass.remove("drop-target") }
        row.setOnDragDropped { e ->
            val from = e.dragboard.string?.toIntOrNull()
            if (from != null && from in list.indices) {
                val moved = list.removeAt(from)
                list.add(i.coerceAtMost(list.size), moved)
                render()
            }
            e.isDropCompleted = from != null
            e.consume()
        }
        return row
    }

    private fun <T> move(list: MutableList<T>, i: Int, by: Int) {
        val j = i + by
        if (j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
    }

    private fun saveButton() = button("Save & close", Icons.CHECK, "btn-accent") {
        picked?.let { Settings.lastHomeProvider = it }
        if (multiFeed && feed.isNotEmpty()) Settings.lastHomeProvider = HomePage.FEED
        Catalogs.saveAll(edits, multiFeed, feed)
        Nav.window.closeOverlay()
    }

    override fun dispose() = Unit
}
