package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginData
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.PluginWrapper
import com.lagradost.cloudstream3.linux.RepositoryData
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.LiveTv
import com.lagradost.cloudstream3.linux.StremioAddons
import com.lagradost.cloudstream3.linux.StremioApi
import com.lagradost.cloudstream3.linux.statusName
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One line in the extension list: something a repository offers, or a side-loaded file. */
data class ExtRow(val wrapper: PluginWrapper?, val installed: PluginData?) {
    val internalName get() = wrapper?.plugin?.internalName ?: installed!!.internalName
    val name get() = wrapper?.plugin?.name ?: installed?.name ?: installed!!.internalName
    val hasUpdate get() = installed != null && wrapper != null && wrapper.plugin.version > installed.version
}

/** Type filter chips, matched against a plugin's tvTypes. */
private val TYPE_FILTERS: List<Pair<String, Set<String>?>> = listOf(
    "All types" to null,
    "Movies" to setOf("Movie", "AnimeMovie", "Documentary"),
    "TV" to setOf("TvSeries", "AsianDrama", "Cartoon", "Documentary"),
    "Anime" to setOf("Anime", "AnimeMovie", "OVA"),
    "Live" to setOf("Live"),
    "Other" to setOf("Torrent", "Music", "AudioBook", "Audio", "Podcast", "CustomMedia", "Others", "NSFW"),
)

private fun typeName(type: String) = when (type) {
    "TvSeries" -> "TV"
    "AsianDrama" -> "Asian drama"
    "AnimeMovie" -> "Anime movie"
    "AudioBook" -> "Audiobook"
    "CustomMedia" -> "Custom"
    else -> type
}

class ExtensionsPage : StackPane(), Refreshable {
    private val repoField = TextField().apply { promptText = "Repository URL or shortcode (the same ones the Android app uses)" }
    private val repoList = VBox()
    private val filterField = TextField().apply { promptText = "Filter extensions" }
    private val language = ComboBox<String>().apply { items.setAll("All languages"); selectionModel.select(0) }
    private val repoFilter = ComboBox<String>().apply { items.setAll("All repositories"); selectionModel.select(0) }
    private val view = ChipGroup { applyFilter() }
    private val types = ChipGroup { applyFilter() }
    private var rows: List<ExtRow> = emptyList()
    private var shown = PAGE
    private val grid = FlowPane(14.0, 14.0)
    private val stats = label("", "muted")
    private val busy = mutableSetOf<String>()
    private var available: List<PluginWrapper> = emptyList()
    private var loadingRepos = false

    init {
        val header = hbox(12.0,
            vbox(4.0, label("Extensions", "h2"), stats),
            spacer(),
            button("Update all", Icons.REFRESH, "btn-ghost") { updateAll() },
            button("Install from file", Icons.FILE, "btn-ghost") { sideload() },
        )
        growWide(repoField)
        val addRow = hbox(10.0, repoField, button("Add repository", Icons.ADD, "btn-accent") { addRepository(repoField.text) })
        repoField.setOnAction { addRepository(repoField.text) }
        val repoCard = vbox(10.0, label("REPOSITORIES", "section-label"), addRow, repoList).apply { styleClass += "card" }

        view.setItems(listOf("All", "Installed", "Updates"))
        types.setItems(TYPE_FILTERS.map { it.first })
        filterField.textProperty().addListener { _, _, _ -> applyFilter() }
        language.setOnAction { applyFilter() }
        repoFilter.setOnAction { applyFilter() }
        growWide(filterField)
        val filterRow = hbox(10.0, filterField, repoFilter, language, view)
        // Cards share the row evenly: as many ~300px columns as fit.
        grid.widthProperty().addListener { _, _, _ -> sizeCards() }

        val extensions = VBox(18.0, header, repoCard, filterRow, types, grid)
        val addons = StremioPane()
        val body = StackPane(extensions)
        // Two kinds of sources: CloudStream extensions (repositories) and Stremio add-ons (manifest URLs).
        val kind = ChipGroup { i -> body.children.setAll(if (i == 0) extensions else addons.also { it.render() }) }.apply {
            setItems(listOf("CloudStream extensions", "Stremio add-ons"), 0)
            id = "ext-kind"
        }
        val page = VBox(18.0, kind, body).pad(22.0, 36.0, 32.0, 36.0)
        children += pageScroll(page)
        ui.launch { PluginManager.version.collect { applyFilter() } }
    }

    private fun cardWidth(): Double {
        val gap = grid.hgap
        val width = grid.width.takeIf { it > 0 } ?: 900.0
        val columns = ((width + gap) / (300.0 + gap)).toInt().coerceAtLeast(1)
        return Math.floor((width - gap * (columns - 1)) / columns) - 0.5
    }

    private fun sizeCards() {
        val w = cardWidth()
        grid.children.forEach { (it as? VBox)?.prefWidth = w }
    }

    private fun growWide(node: Node) = javafx.scene.layout.HBox.setHgrow(node, Priority.ALWAYS)

    override fun onShow() = refresh()

    fun refresh() {
        val repos = RepositoryManager.getRepositories()
        renderRepos(repos, emptyMap())
        if (loadingRepos) return
        loadingRepos = true
        stats.text = "Reading ${repos.size} repositories…"
        ui.launch {
            val perRepo = withContext(Engine.dispatcher) {
                repos.associateWith { RepositoryManager.getRepoPlugins(it) }
            }
            available = perRepo.values.filterNotNull().flatten()
            loadingRepos = false
            renderRepos(repos, perRepo)
            val selectedRepo = repoFilter.value
            repoFilter.items.setAll(listOf("All repositories") + repos.map { it.name }.distinct())
            repoFilter.value = selectedRepo?.takeIf { it in repoFilter.items } ?: "All repositories"
            val langs = available.mapNotNull { it.plugin.language }.distinct().sortedBy { languageName(it) }
            val selected = language.value
            language.items.setAll(listOf("All languages") + langs.map { "${languageName(it)} ($it)" })
            language.value = selected?.takeIf { it in language.items } ?: "All languages"
            applyFilter()
        }
    }

    private fun renderRepos(repos: List<RepositoryData>, plugins: Map<RepositoryData, List<PluginWrapper>?>) {
        if (repos.isEmpty()) {
            repoList.children.setAll(label("No repositories yet. Paste a repository link above — any CloudStream repository works.", "muted").apply { isWrapText = true })
            return
        }
        repoList.children.setAll(repos.map { repo ->
            val list = plugins[repo]
            val count = when {
                repo !in plugins -> "loading…"
                list == null -> "unreachable"
                else -> "${list.size} extensions"
            }
            hbox(12.0,
                vbox(2.0, label(repo.name, "bold"), label(repo.url, "faint", "small")),
                spacer(),
                label(count, if (list == null && repo in plugins) "error-text" else "muted", "small"),
                button("Install all", Icons.DOWNLOAD, "btn-ghost", "btn-small") { installAll(list.orEmpty()) }.apply { isDisable = list.isNullOrEmpty() },
                button(null, Icons.DELETE, "btn-danger", "btn-small") { removeRepository(repo) }.tooltip("Remove repository and its extensions"),
            ).apply { styleClass += "card-row" }
        })
    }

    private fun applyFilter() {
        val installed = PluginManager.installed()
        val byName = installed.associateBy { it.internalName }
        val rows = available.map { ExtRow(it, byName[it.plugin.internalName]) } +
            installed.filter { local -> available.none { it.plugin.internalName == local.internalName } }.map { ExtRow(null, it) }
        val query = filterField.text.trim().lowercase()
        val lang = language.value?.substringAfterLast('(')?.removeSuffix(")")?.takeIf { language.selectionModel.selectedIndex > 0 }
        val repo = repoFilter.value?.takeIf { repoFilter.selectionModel.selectedIndex > 0 }
        val typeSet = TYPE_FILTERS.getOrNull(types.selected)?.second
        this.rows = rows.filter { row ->
            (query.isEmpty() || row.name.lowercase().contains(query) || row.wrapper?.plugin?.description.orEmpty().lowercase().contains(query)) &&
                (lang == null || row.wrapper?.plugin?.language == lang) &&
                (repo == null || row.wrapper?.repository?.name == repo) &&
                (typeSet == null || row.wrapper?.plugin?.tvTypes.orEmpty().any { it in typeSet }) &&
                when (view.selected) {
                    1 -> row.installed != null
                    2 -> row.hasUpdate
                    else -> true
                }
        }.sortedWith(compareBy({ it.installed == null }, { it.name.lowercase() }))
        shown = PAGE
        renderGrid()
        val loaded = Engine.allProviders().size
        if (!loadingRepos) stats.text = "${available.size} available · ${installed.size} installed · $loaded providers loaded" +
            (PluginManager.failures.size.takeIf { it > 0 }?.let { " · $it failed" } ?: "")
    }

    /** Renders cards a page at a time: big repositories list hundreds of extensions. */
    private fun renderGrid() {
        val w = cardWidth()
        val nodes = rows.take(shown).map { card(it).apply { prefWidth = w } }.toMutableList<Node>()
        if (rows.isEmpty()) nodes += label(if (available.isEmpty() && loadingRepos) "Loading extensions…" else "No extensions match these filters", "muted")
        if (rows.size > shown) nodes += button("Show ${minOf(PAGE, rows.size - shown)} more (${rows.size - shown} left)", null, "btn-ghost") {
            shown += PAGE
            renderGrid()
        }
        grid.children.setAll(nodes)
    }

    private fun languageName(code: String) =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

    fun promptAddRepository(link: String) {
        val alert = Alert(Alert.AlertType.CONFIRMATION, "Add this extension repository?\n\n$link", ButtonType.OK, ButtonType.CANCEL).apply {
            title = "Add repository"
            headerText = null
            dialogPane.stylesheets += ExtensionsPage::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        }
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) addRepository(link)
    }

    private fun addRepository(input: String) {
        if (input.isBlank()) return
        ui.launch {
            try {
                val repo = withContext(Engine.dispatcher) { RepositoryManager.addRepository(input.trim()) }
                repoField.clear()
                Toasts.show("Added ${repo.name}")
                refresh()
            } catch (t: Throwable) {
                Toasts.show("Couldn't add repository: ${t.message ?: t}", seconds = 7.0)
            }
        }
    }

    private fun removeRepository(repo: RepositoryData) {
        val alert = Alert(Alert.AlertType.CONFIRMATION, "Remove ${repo.name} and uninstall its extensions?", ButtonType.OK, ButtonType.CANCEL).apply {
            headerText = null
            dialogPane.stylesheets += ExtensionsPage::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        }
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        RepositoryManager.removeRepository(repo.url)
        refresh()
    }

    private fun install(wrapper: PluginWrapper) {
        val name = wrapper.plugin.internalName
        if (!busy.add(name)) return
        renderGrid()
        ui.launch {
            val result = withContext(Engine.dispatcher) { PluginManager.install(wrapper) }
            busy.remove(name)
            result.fold(
                { Toasts.show("Installed ${wrapper.plugin.name}" + it.providers.takeIf { p -> p.isNotEmpty() }?.let { p -> " · ${p.joinToString { a -> a.name }}" }.orEmpty()) },
                { Toasts.show("${wrapper.plugin.name} failed: ${it.message ?: it}", seconds = 8.0) },
            )
            applyFilter()
        }
    }

    private fun installAll(list: List<PluginWrapper>) {
        val installed = PluginManager.installed().map { it.internalName }.toSet()
        list.filter { it.plugin.status != 0 && it.plugin.internalName !in installed }.forEach { install(it) }
    }

    private fun uninstall(row: ExtRow) {
        PluginManager.uninstall(row.internalName)
        Toasts.show("Uninstalled ${row.name}")
        applyFilter()
    }

    private fun updateAll() {
        ui.launch {
            Toasts.show("Checking for updates…")
            val updated = withContext(Engine.dispatcher) { PluginManager.updateAll() }
            Toasts.show(if (updated.isEmpty()) "All extensions are up to date" else "Updated ${updated.joinToString()}")
            refresh()
        }
    }

    private fun sideload() {
        val file: File = FileChooser().apply {
            title = "Install extension"
            extensionFilters += FileChooser.ExtensionFilter("CloudStream extension", "*.cs3", "*.jar")
        }.showOpenDialog(scene.window) ?: return
        ui.launch {
            val result = withContext(Engine.dispatcher) { PluginManager.sideload(file) }
            result.fold(
                { Toasts.show("Loaded ${it.manifest.name}: ${it.providers.joinToString { p -> p.name }.ifEmpty { "no providers" }}") },
                { Toasts.show("${file.name} failed: ${it.message ?: it}", seconds = 8.0) },
            )
            applyFilter()
        }
    }

    private fun card(row: ExtRow): VBox {
        val p = row.wrapper?.plugin
        val iconView = ImageView()
        Images.load(iconView, p?.iconUrl?.replace("%size%", "96"), 46.0, 46.0)
        val initials = label(row.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "bold", "muted")
        val iconBox = StackPane(initials, iconView).apply {
            styleClass += "poster-frame"
            setMinSize(46.0, 46.0); setMaxSize(46.0, 46.0)
            roundedClip(this, 12.0)
        }
        val version = (p?.version ?: row.installed?.version)?.let { "v$it" }
        val meta = listOfNotNull(p?.language?.let { languageName(it) }, version, row.wrapper?.repository?.name)
        val title = vbox(2.0,
            label(row.name, "bold").apply { isWrapText = false },
            label(meta.joinToString(" · "), "faint", "small"),
        ).apply { javafx.scene.layout.HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 }
        val head = hbox(12.0, iconBox, title)

        val card = VBox(10.0, head).apply { styleClass += "ext-card" }
        p?.description?.takeIf { it.isNotBlank() }?.let {
            card.children += label(it, "muted", "small").apply { isWrapText = true; maxHeight = 34.0 }
        }
        val tags = FlowPane(6.0, 6.0)
        p?.tvTypes?.take(4)?.forEach { tags.children += label(typeName(it), "tag") }
        if (p != null && p.status != 1) tags.children += label(statusName(p.status), "tag", "accent")
        if (tags.children.isNotEmpty()) card.children += tags

        val failure = PluginManager.failures[row.internalName]
        failure?.let { card.children += label("Failed to load: ${it.message ?: it}", "error-text", "small").apply { isWrapText = true } }

        val actions = hbox(8.0).apply { alignment = Pos.CENTER_RIGHT }
        when {
            row.internalName in busy -> actions.children += hbox(8.0, ProgressIndicator().apply { setPrefSize(18.0, 18.0) }, label("Installing…", "muted"))
            row.installed == null -> actions.children += button("Install", Icons.DOWNLOAD, "btn-accent", "btn-small") { install(row.wrapper!!) }
                .apply { isDisable = p?.status == 0 }
            else -> {
                actions.children += label(if (failure == null) "Installed" else "Error", if (failure == null) "good-text" else "error-text", "small")
                actions.children += spacer()
                if (row.hasUpdate) actions.children += button("Update", Icons.REFRESH, "btn-accent", "btn-small") { install(row.wrapper!!) }
                actions.children += button(null, Icons.DELETE, "btn-danger", "btn-small") { uninstall(row) }.tooltip("Uninstall")
            }
        }
        card.children += spacer()
        card.children += actions
        return card
    }

    private companion object {
        const val PAGE = 60
    }
}

/** Installed Stremio add-ons: add by manifest URL, turn on/off, remove. */
class StremioPane : VBox(18.0) {
    private val field = TextField().apply { promptText = "Add-on address: https://…/manifest.json or stremio://…" }
    private val list = VBox(12.0)
    private val status = label("", "muted")

    init {
        javafx.scene.layout.HBox.setHgrow(field, Priority.ALWAYS)
        val install = button("Install add-on", Icons.ADD, "btn-accent") { install(field.text) }
        field.setOnAction { install(field.text) }
        val header = vbox(4.0, label("Stremio add-ons", "h2"), label(
            "Stremio community add-ons work here: their catalogs show on Home, in Search and in the catalog editor, and their " +
                "streams play in the built-in player. Add-ons that only offer torrents aren't supported.", "muted").apply { isWrapText = true; maxWidth = 900.0 })
        val add = vbox(10.0, label("ADD AN ADD-ON", "section-label"), hbox(10.0, field, install), status).apply { styleClass += "card" }
        children.addAll(header, add, list)
        StremioAddons.onChange { javafx.application.Platform.runLater { render() } }
    }

    private fun install(text: String) {
        if (text.isBlank()) return
        status.text = "Installing…"
        status.styleClass.setAll("label", "muted")
        ui.launch {
            try {
                val addon = withContext(Engine.dispatcher) { StremioAddons.install(text) }
                field.clear()
                status.text = "Installed ${addon.manifest.name} ${addon.manifest.version}"
                Toasts.show("${addon.manifest.name} added")
            } catch (t: Throwable) {
                status.text = "Couldn't install: ${t.message ?: t}"
                status.styleClass.setAll("label", "error-text")
            }
        }
    }

    fun render() {
        val addons = StremioAddons.all()
        val nodes = addons.map { card(it) }.toMutableList<Node>()
        if (addons.none { it.url == StremioAddons.CINEMETA }) {
            nodes += hbox(12.0, vbox(2.0, label("Cinemeta", "bold"),
                label("Stremio's official catalog of films and series: popular, new and featured lists with rich details. Add a streaming add-on to play them.", "faint", "small").apply { isWrapText = true }).apply { HBox.setHgrow(this, Priority.ALWAYS) },
                button("Add", Icons.ADD, "btn-ghost") { install(StremioAddons.CINEMETA) }).apply { styleClass += "card" }
        }
        if (addons.isEmpty()) nodes.add(0, label("No add-ons yet. Paste an add-on's manifest address above, or start with Cinemeta below.", "muted"))
        list.children.setAll(nodes)
    }

    private fun card(a: StremioAddons.Addon): Node {
        val m = a.manifest
        val logo = StackPane().apply {
            styleClass += "addon-logo"
            setMinSize(46.0, 46.0); setMaxSize(46.0, 46.0)
            if (m.logo != null) children += ImageView().also { Images.load(it, m.logo, 46.0, 46.0) }
            else children += label(m.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "bold")
            roundedClip(this, 12.0)
        }
        val catalogs = m.catalogs.count { !it.needsExtra && !LiveTv.isTvType(it.type) }
        val tv = m.catalogs.any { LiveTv.isTvType(it.type) }
        val tags = hbox(6.0, *listOfNotNull(
            if (catalogs > 0) label("$catalogs catalogs", "tag") else null,
            if (tv) label("Live TV channels", "tag", "accent").tooltip("Its TV channels are in Live TV") else null,
            if (m.catalogs.any { it.searchable }) label("Search", "tag") else null,
            if (m.resources.any { it.name == "meta" }) label("Details", "tag") else null,
            if (m.resources.any { it.name == "stream" }) label("Streams", "tag", "accent") else null,
            if (m.resources.any { it.name == "subtitles" }) label("Subtitles", "tag") else null,
            label(m.types.joinToString(" · "), "faint", "small"),
        ).toTypedArray())
        val text = vbox(4.0, hbox(8.0, label(m.name, "bold"), label("v${m.version}", "faint", "small")),
            label(m.description.ifBlank { a.base }, "muted", "small").apply { isWrapText = true; maxHeight = 36.0 }, tags).apply {
            HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0
        }
        val api = if (catalogs > 0) Engine.allProviders().firstOrNull { it is StremioApi && it.mainUrl == a.base } else null
        val tune = api?.let { button(null, Icons.TUNE, "btn-ghost") { Nav.window.showOverlay(HomeManager(Engine.providers().filter { p -> p.hasMainPage }, it)) }.tooltip("Choose its catalogs for Home") }
        val remove = button(null, Icons.DELETE, "btn-ghost") { StremioAddons.remove(a.url); Toasts.show("${m.name} removed") }.tooltip("Remove")
        val toggle = Switch(a.enabled) { StremioAddons.setEnabled(a.url, it) }
        return hbox(14.0, *listOfNotNull(logo, text, tune, remove, toggle).toTypedArray()).apply {
            styleClass += "card"
            if (!a.enabled) opacity = 0.6
        }
    }
}
