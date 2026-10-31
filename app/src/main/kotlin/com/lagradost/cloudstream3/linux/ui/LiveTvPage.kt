package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.ChannelLogos
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.LiveApi
import com.lagradost.cloudstream3.linux.LiveTv
import com.lagradost.cloudstream3.linux.StremioAddons
import com.lagradost.cloudstream3.linux.StremioApi
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Live TV: channels from IPTV playlists, Xtream logins and Stremio TV add-ons (see [LiveTv]).
 * A featured channel with what's on now and next, category chips from the playlists'
 * groups, rows (recently watched, favourites, each group) or a grid when filtering.
 */
class LiveTvPage : StackPane(), Refreshable, TopBarActions, Disposable {
    private var all: List<LiveTv.Channel> = emptyList()
    private var sourceId: String? = null
    private var country: String? = null
    private var group: String? = ALL
    private var loading = false
    private var job: Job? = null
    private val heroHolder = StackPane().apply { minWidth = 0.0 }
    private val chips = ChipGroup { i -> group = chipKeys.getOrNull(i); renderBody() }
    private var chipKeys: List<String?> = emptyList()
    private val body = VBox(30.0)
    private val chipsNode = chipsScroll()
    private val content = VBox(22.0, chipsNode, body).apply { pad(0.0, 40.0, 48.0, 40.0) }
    private val scroll = pageScroll(VBox(heroHolder, content))
    private var shown = PAGE
    private val searchField = TextField().apply {
        promptText = "Search channels"
        styleClass += "topbar-field"
        prefWidth = 230.0
        textProperty().addListener { _, _, _ -> shown = PAGE; renderBody() }
    }
    private val sourceButton = button("All sources", null, "topbar-source") { sourceMenu() }.apply {
        contentDisplay = javafx.scene.control.ContentDisplay.RIGHT; graphic = icon(Icons.EXPAND, 12.0)
    }
    private val countryButton = button("All countries", null, "topbar-source") { countryMenu() }.apply {
        contentDisplay = javafx.scene.control.ContentDisplay.RIGHT; graphic = icon(Icons.EXPAND, 12.0)
    }
    private val unsubscribe: () -> Unit
    private var guideMode = false
    private val guideHolder = StackPane().apply { VBox.setVgrow(this, javafx.scene.layout.Priority.ALWAYS); alignment = Pos.TOP_LEFT }
    private val guideLayout = VBox(18.0).apply { pad(TopBar.HEIGHT + 14, 40.0, 24.0, 40.0) }
    private val guideButton = button("TV guide", Icons.HISTORY, "topbar-source") { toggleGuide() }.apply { tooltip("Switch between the channel list and the TV guide") }

    init {
        children += scroll
        scroll.vvalueProperty().addListener { _, _, _ -> onScroll() }
        unsubscribe = LiveTv.onChange { javafx.application.Platform.runLater { if (scene != null) { renderHero(); renderBody() } } }
        StremioAddons.onChange { javafx.application.Platform.runLater { reload() } }
        reload()
    }

    override fun topBarActions(): List<Node> = listOf(label("Live TV", "topbar-title"), sourceButton, countryButton, guideButton, searchField)
    override val hasOwnSearch get() = true

    override fun onShow() {
        renderHero()
        renderBody()
        ui.launch { LiveTv.loadGuides() }
    }

    override fun dispose() {
        job?.cancel()
        unsubscribe()
    }

    private fun chipsScroll(): Node = ScrollPane(chips).apply {
        styleClass += "chip-scroll"
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER; vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        isFitToHeight = true
    }

    fun reload(force: Boolean = false) {
        job?.cancel()
        loading = true
        renderBody()
        job = ui.launch {
            all = LiveTv.channels(sourceId, force)
            loading = false
            val countries = all.mapNotNull { it.country }.distinct()
            countryButton.isVisible = countries.size > 1; countryButton.isManaged = countryButton.isVisible
            renderHero()
            renderBody()
            LiveTv.loadGuides(force)
        }
    }

    private fun toggleGuide() {
        guideMode = !guideMode
        guideButton.text = if (guideMode) "Channels" else "TV guide"
        guideButton.graphic = icon(if (guideMode) Icons.APPS else Icons.HISTORY, 18.0)
        if (guideMode) {
            guideLayout.children.setAll(chipsNode, guideHolder)
            children.setAll(guideLayout)
            Nav.window.setTopBarSolid(true)
        } else {
            content.children.add(0, chipsNode)
            children.setAll(scroll)
            updateTopBar()
        }
        renderBody()
    }

    /** The page's main area: the channel rows, or the guide grid in guide mode. */
    private fun show(nodes: List<Node>) {
        if (guideMode) guideHolder.children.setAll(if (nodes.size == 1) nodes else listOf(VBox(16.0, *nodes.toTypedArray())))
        else body.children.setAll(nodes)
    }
    private fun show(vararg nodes: Node) = show(nodes.toList())

    private fun renderGuide(list: List<LiveTv.Channel>, favs: Set<String>, query: String) {
        val filtered = list.filter { c ->
            (group == ALL || (group == FAVOURITES && c.key in favs) || (c.group ?: "Other") == group) &&
                (query.isEmpty() || c.name.lowercase().contains(query) || c.group.orEmpty().lowercase().contains(query))
        }.sortedByDescending { it.key in favs }
        when {
            !LiveTv.hasGuide() -> show(emptyState(Icons.HISTORY, "No TV guide yet",
                "None of your sources has a guide (XMLTV) loaded. Xtream logins include one; for an M3U playlist, " +
                    "add its guide address in Settings → Live TV sources. It can take a moment to load after adding.",
                button("Refresh guide", Icons.REFRESH, "btn-accent") { ui.launch { LiveTv.loadGuides(force = true) } }))
            filtered.isEmpty() -> show(label("No channels match.", "muted"))
            else -> {
                val grid = GuideGrid(filtered.take(GuideGrid.MAX_CHANNELS)) { play(it) }
                VBox.setVgrow(grid, javafx.scene.layout.Priority.ALWAYS)
                val note = if (filtered.size > GuideGrid.MAX_CHANNELS) label("Showing the first ${GuideGrid.MAX_CHANNELS} of ${filtered.size} channels: pick a category or search to narrow.", "muted") else null
                guideHolder.children.setAll(VBox(10.0, *listOfNotNull(note, grid).toTypedArray()).apply { VBox.setVgrow(grid, javafx.scene.layout.Priority.ALWAYS) })
            }
        }
    }

    private fun visible(): List<LiveTv.Channel> = all.filter { country == null || it.country?.split(';')?.contains(country) == true }

    // ---------- top bar menus ----------

    private fun sourceMenu() {
        val sources = LiveTv.sources().filter { it.enabled }
        val rows = mutableListOf(Popover.row(icon(Icons.APPS, 18.0), "All sources", null, sourceId == null) { pickSource(null, "All sources") })
        sources.forEach { s -> rows += Popover.row(label(s.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "provider-badge", "small-badge"), s.name, s.kind.label, s.id == sourceId) { pickSource(s.id, s.name) } }
        rows += Popover.separator()
        rows += Popover.row(icon(Icons.REFRESH, 18.0), "Refresh channels and guide") { reload(force = true) }
        if (!com.lagradost.cloudstream3.linux.Profiles.isKids) rows += Popover.row(icon(Icons.SETTINGS, 18.0), "Live TV sources…") { Nav.window.openSettings("livetv") }
        Popover.show(sourceButton, Popover.panel(*rows.toTypedArray()), alignRight = false)
    }

    private fun pickSource(id: String?, name: String) {
        sourceId = id
        sourceButton.text = name
        group = ALL
        reload()
    }

    private fun countryMenu() {
        val counts = all.flatMap { it.country?.split(';').orEmpty() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val rows = mutableListOf(Popover.row(null, "All countries", null, country == null) { country = null; countryButton.text = "All countries"; renderBody(); renderHero() })
        counts.entries.sortedByDescending { it.value }.take(40).forEach { (code, n) ->
            val name = java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { code }
            rows += Popover.row(null, name, "$n", code == country) { country = code; countryButton.text = name; group = ALL; renderBody(); renderHero() }
        }
        val panel = Popover.panel(*rows.toTypedArray())
        Popover.show(countryButton, ScrollPane(panel).apply { maxHeight = 460.0; isFitToWidth = true; hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER }, alignRight = false)
    }

    // ---------- featured channel ----------

    private fun featured(): LiveTv.Channel? {
        val list = visible()
        val byKey = list.associateBy { it.key }
        return LiveTv.recent().firstNotNullOfOrNull { byKey[it] } ?: LiveTv.favourites().firstNotNullOfOrNull { byKey[it] } ?: list.firstOrNull()
    }

    private fun renderHero() {
        val c = featured()
        if (c == null) {
            heroHolder.children.setAll(Region().apply { minHeight = TopBar.HEIGHT + 12 })
            Nav.window.setTopBarSolid(true)
            return
        }
        val (now, next) = LiveTv.nowNext(c)
        val fav = c.key in LiveTv.favourites()
        val bg = backdrop(c.logo, null, 0.55)
        val tint = Region().apply { style = "-fx-background-color: linear-gradient(to right, ${colorFor(c.name)}55, transparent 70%);"; isMouseTransparent = true }
        val shadeL = Region().apply { styleClass += "hero-shade-left"; isMouseTransparent = true }
        val shadeB = Region().apply { styleClass += "hero-shade-bottom"; isMouseTransparent = true }
        val shadeT = Region().apply { styleClass += "hero-shade-top"; isMouseTransparent = true }
        val head = hbox(10.0, label("● LIVE", "live-badge"), label(listOfNotNull(c.number.takeIf { it > 0 }?.let { "Channel $it" }, c.group).joinToString(" · "), "hero-source"))
        val title = hbox(20.0, logoTile(c, 84.0), label(c.name, "hero-big-title").apply { isWrapText = true; maxWidth = 620.0 }).apply { alignment = Pos.CENTER_LEFT }
        val guide = vbox(8.0).apply {
            if (now != null) {
                val left = ((now.stop - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                children += hbox(12.0, label("Now: ${now.title}", "hero-meta-item"), label("${time(now.start)} – ${time(now.stop)} · $left min left", "muted"))
                children += progressBar(progressOf(now), 420.0)
            } else children += label(c.group?.let { "Live · $it" } ?: "Live", "muted")
            if (next != null) children += label("Next: ${next.title} · ${time(next.start)}", "muted")
        }
        val buttons = hbox(12.0,
            button("Watch live", Icons.PLAY, "hero-play") { play(c) },
            button(if (fav) "Favourite" else "Add to favourites", if (fav) Icons.STAR else Icons.STAR_OUTLINE, "hero-info") { LiveTv.toggleFavourite(c) }.apply {
                if (fav) styleClass += "is-favourite"
            },
        ).pad(6.0, 0.0, 0.0, 0.0)
        val text = vbox(16.0, head, title, guide, buttons).apply { alignment = Pos.BOTTOM_LEFT; maxWidth = 760.0; isPickOnBounds = false }
        val textLayer = StackPane(text).apply { alignment = Pos.BOTTOM_LEFT; pad(TopBar.HEIGHT + 20, 40.0, 56.0, 40.0); isPickOnBounds = false }
        val hero = StackPane(bg, tint, shadeL, shadeB, shadeT, textLayer).apply {
            styleClass += "hero-full"
            minHeight = 440.0; maxHeight = 440.0; prefHeight = 440.0
            minWidth = 0.0
        }
        heroHolder.children.setAll(hero)
        updateTopBar()
    }

    private fun updateTopBar() {
        if (scene == null || guideMode) return
        val offset = scroll.vvalue * ((scroll.content as Region).height - scroll.viewportBounds.height).coerceAtLeast(0.0)
        Nav.window.setTopBarSolid(heroHolder.height < 100 || offset > heroHolder.height - TopBar.HEIGHT - 40)
    }

    // ---------- chips and channels ----------

    private fun renderBody() {
        val list = visible()
        val sources = LiveTv.sources()
        when {
            sources.isEmpty() -> {
                chips.isVisible = false
                show(emptyState(Icons.TV, "Add a Live TV source",
                    "Add an IPTV playlist (M3U), an Xtream Codes login, or a Stremio / Nuvio TV add-on. " +
                        "Live TV sources only show here, never on Home.",
                    if (com.lagradost.cloudstream3.linux.Profiles.isKids) null
                    else button("Add a source", Icons.ADD, "btn-accent") { Nav.window.openSettings("livetv") }))
                return
            }
            loading && list.isEmpty() -> {
                show(hbox(10.0, ProgressIndicator().apply { setPrefSize(22.0, 22.0) }, label("Loading channels…", "muted")))
                return
            }
            list.isEmpty() -> {
                chips.isVisible = false
                show(emptyState(Icons.INFO, "No channels",
                    if (com.lagradost.cloudstream3.linux.Profiles.isKids) "There are no children's channels in these sources."
                    else "The sources returned no channels. Check the address in Settings → Live TV sources, or refresh."))
                return
            }
        }
        chips.isVisible = true
        val favs = LiveTv.favourites()
        val groups = list.groupingBy { it.group ?: "Other" }.eachCount().entries.sortedByDescending { it.value }
        chipKeys = listOf(ALL, FAVOURITES) + groups.map { it.key }
        val labels = listOf("All ${list.size}", "★ Favourites ${list.count { it.key in favs }}") + groups.map { it.key }
        chips.setItems(labels, chipKeys.indexOf(group).coerceAtLeast(0))
        val query = searchField.text.trim().lowercase()
        if (guideMode) { renderGuide(list, favs, query); return }

        if (group == ALL && query.isEmpty()) {
            val byKey = list.associateBy { it.key }
            val rows = mutableListOf<Node>()
            LiveTv.recent().mapNotNull { byKey[it] }.takeIf { it.isNotEmpty() }?.let { rows += row("Recently watched", it, null) }
            list.filter { it.key in favs }.takeIf { it.isNotEmpty() }?.let { rows += row("Favourites", it, FAVOURITES) }
            groups.take(14).forEach { (g, n) -> rows += row(g, list.filter { (it.group ?: "Other") == g }, g, n) }
            show(rows)
            return
        }
        val filtered = list.filter { c ->
            (group == ALL || (group == FAVOURITES && c.key in favs) || (c.group ?: "Other") == group) &&
                (query.isEmpty() || c.name.lowercase().contains(query) || c.group.orEmpty().lowercase().contains(query))
        }
        if (filtered.isEmpty()) {
            show(label(if (group == FAVOURITES) "No favourites yet. Use the star on a channel to add it." else "No channels match.", "muted"))
            return
        }
        val grid = FlowPane(16.0, 18.0).apply { children.setAll(filtered.take(shown).map { card(it) }) }
        show(listOfNotNull(label("${filtered.size} channels", "muted"), grid,
            if (filtered.size > shown) label("Scroll for more", "faint") else null))
    }

    private fun onScroll() {
        updateTopBar()
        // Grids grow as you scroll, like "View all" pages.
        val content = (scroll.content as Region).height
        val viewport = scroll.viewportBounds.height
        if (viewport > 0 && (content - viewport) * (1 - scroll.vvalue) < viewport && (group != ALL || searchField.text.isNotBlank())) {
            val before = shown
            shown += PAGE
            if (before < all.size) renderBody()
        }
    }

    private fun row(title: String, channels: List<LiveTv.Channel>, key: String?, count: Int = channels.size): Node =
        Shelf(title, onViewAll = key?.let { { group = it; shown = PAGE; renderBody(); scroll.vvalue = 0.0 } },
            trailing = if (key != null && count > 20) label("$count", "faint") else null).apply {
            channels.take(20).forEach { add(card(it)) }
        }

    private fun card(c: LiveTv.Channel): Node {
        val w = 236.0
        val (now, _) = LiveTv.nowNext(c)
        val fav = c.key in LiveTv.favourites()
        val pic = StackPane(logoTile(c, 64.0)).apply {
            styleClass += "channel-pic"
            setMinSize(w, 124.0); setMaxSize(w, 124.0)
            style = "-fx-background-color: linear-gradient(to bottom right, ${colorFor(c.name)}cc, #120a22);"
        }
        val live = label("● LIVE", "live-badge").also { StackPane.setAlignment(it, Pos.TOP_LEFT); StackPane.setMargin(it, javafx.geometry.Insets(8.0)) }
        pic.children += live
        if (c.number > 0) pic.children += label("${c.number}", "badge").also { StackPane.setAlignment(it, Pos.TOP_RIGHT); StackPane.setMargin(it, javafx.geometry.Insets(8.0)) }
        val star = StackPane(icon(if (fav) Icons.STAR else Icons.STAR_OUTLINE, 18.0)).apply {
            styleClass += "channel-star"
            if (fav) styleClass += "on"
            StackPane.setAlignment(this, Pos.BOTTOM_RIGHT); StackPane.setMargin(this, javafx.geometry.Insets(6.0))
            setMaxSize(30.0, 30.0)
            setOnMouseClicked { it.consume(); LiveTv.toggleFavourite(c) }
            tooltip(if (fav) "Remove from favourites" else "Add to favourites")
        }
        pic.children += star
        val info = vbox(3.0,
            label(c.name, "channel-name").apply { maxWidth = w - 24 },
            label(now?.let { "Now: ${it.title} · ${time(it.start)}" } ?: (c.group ?: " "), "channel-now").apply { maxWidth = w - 24 },
        ).apply { pad(10.0, 12.0, 12.0, 12.0) }
        if (now != null) info.children += progressBar(progressOf(now), w - 24, 3.0).apply { VBox.setMargin(this, javafx.geometry.Insets(6.0, 0.0, 0.0, 0.0)) }
        val box = VBox(pic, info).apply {
            styleClass += "channel-card"
            setMinWidth(w); setMaxWidth(w)
            onClick { play(c) }
        }
        roundedClip(box, 12.0)
        box.hoverGrow(1.03)
        return box
    }

    /** The channel's logo, kept whole (not cropped), on a tile; its initials when there's no logo. */
    private fun logoTile(c: LiveTv.Channel, size: Double): Node {
        val tile = StackPane().apply {
            styleClass += "channel-logo"
            setMinSize(size, size); setMaxSize(size, size)
            style = "-fx-background-color: ${colorFor(c.name)};"
        }
        val initials = label(c.name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "TV" }, "channel-initials").apply {
            style = "-fx-font-size: ${size * 0.3}px;"
        }
        tile.children += initials
        // The playlist's logo, else one from the open iptv-org database; initials until (or unless) one loads.
        var started = false
        tile.sceneProperty().addListener { _, _, scene ->
            if (scene == null || started) return@addListener
            started = true
            ui.launch {
                for (url in ChannelLogos.candidates(c)) {
                    val image = Images.image(url, size * 0.82, size * 0.82) ?: continue
                    val view = ImageView(image).apply { isPreserveRatio = true; fitWidth = size * 0.82; fitHeight = size * 0.82; isSmooth = true }
                    tile.children.setAll(view)
                    tile.style = "-fx-background-color: rgba(255,255,255,0.94);"
                    break
                }
            }
        }
        roundedClip(tile, size * 0.22)
        return tile
    }

    private fun play(c: LiveTv.Channel) {
        LiveTv.watched(c)
        ui.launch {
            try {
                if (c.source.startsWith("stremio:")) {
                    val addon = StremioAddons.all().first { "stremio:" + it.manifest.id == c.source }
                    val api = StremioApi(addon)
                    val r = Engine.load(api, c.url) ?: error("The add-on returned nothing for ${c.name}")
                    val playable = Engine.playables(r).firstOrNull() ?: error("Nothing to play")
                    Playback.open(api, r, playable)
                } else {
                    val r = LiveApi.load(LiveApi.data(c))!!
                    Playback.open(LiveApi, r, Engine.playables(r).first())
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Toasts.show("Couldn't play ${c.name}: ${t.message ?: t}")
            }
        }
    }

    private fun progressOf(p: LiveTv.Programme) =
        ((System.currentTimeMillis() - p.start).toDouble() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0.0, 1.0)

    private fun time(ms: Long) = clockFormat.format(Instant.ofEpochMilli(ms))

    companion object {
        private const val ALL = "__all__"
        private const val FAVOURITES = "__fav__"
        private const val PAGE = 120
        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        private val palette = listOf("#b91c1c", "#16a34a", "#d97706", "#7c3aed", "#0891b2", "#db2777", "#2563eb", "#0d9488", "#9333ea", "#ea580c")

        fun colorFor(name: String) = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    }
}
