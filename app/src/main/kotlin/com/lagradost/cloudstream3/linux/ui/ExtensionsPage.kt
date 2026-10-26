package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginData
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.PluginWrapper
import com.lagradost.cloudstream3.linux.RepositoryData
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.statusName
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
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

class ExtensionsPage : StackPane(), Refreshable {
    private val repoField = TextField().apply { promptText = "Repository URL or shortcode (the same ones the Android app uses)" }
    private val repoList = VBox()
    private val filterField = TextField().apply { promptText = "Filter extensions" }
    private val language = ComboBox<String>().apply { items.setAll("All languages"); selectionModel.select(0) }
    private val view = ChipGroup { applyFilter() }
    private val items = FXCollections.observableArrayList<ExtRow>()
    private val listView = ListView(items)
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
        filterField.textProperty().addListener { _, _, _ -> applyFilter() }
        language.setOnAction { applyFilter() }
        growWide(filterField)
        val filterRow = hbox(10.0, filterField, language, view)
        listView.setCellFactory { ExtCell() }
        listView.placeholder = label("No extensions to show", "muted")
        VBox.setVgrow(listView, Priority.ALWAYS)

        children += VBox(18.0, header, repoCard, filterRow, listView).pad(22.0, 36.0, 24.0, 36.0)
        ui.launch { PluginManager.version.collect { applyFilter() } }
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
        items.setAll(rows.filter { row ->
            (query.isEmpty() || row.name.lowercase().contains(query) || row.wrapper?.plugin?.description.orEmpty().lowercase().contains(query)) &&
                (lang == null || row.wrapper?.plugin?.language == lang) &&
                when (view.selected) {
                    1 -> row.installed != null
                    2 -> row.hasUpdate
                    else -> true
                }
        }.sortedWith(compareBy({ it.installed == null }, { it.name.lowercase() })))
        val loaded = Engine.providers().size
        if (!loadingRepos) stats.text = "${available.size} available · ${installed.size} installed · $loaded providers loaded" +
            (PluginManager.failures.size.takeIf { it > 0 }?.let { " · $it failed" } ?: "")
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
        listView.refresh()
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

    private inner class ExtCell : ListCell<ExtRow>() {
        override fun updateItem(item: ExtRow?, empty: Boolean) {
            super.updateItem(item, empty)
            text = null
            graphic = if (empty || item == null) null else render(item)
        }

        private fun render(row: ExtRow): Node {
            val p = row.wrapper?.plugin
            val iconView = ImageView()
            Images.load(iconView, p?.iconUrl?.replace("%size%", "64"), 40.0, 40.0)
            val initials = label(row.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "bold", "muted")
            val iconBox = StackPane(initials, iconView).apply {
                styleClass += "poster-frame"
                setMinSize(40.0, 40.0); setMaxSize(40.0, 40.0)
                roundedClip(this, 10.0)
            }
            val tags = hbox(6.0, label(row.name, "bold"))
            (p?.version ?: row.installed?.version)?.let { tags.children += label("v$it", "tag") }
            p?.language?.let { tags.children += label(languageName(it), "tag") }
            p?.tvTypes?.take(3)?.forEach { tags.children += label(it, "tag") }
            if (p != null && p.status != 1) tags.children += label(statusName(p.status), "tag", "accent")
            if (row.wrapper == null) tags.children += label("local", "tag")
            val failure = PluginManager.failures[row.internalName]
            val lines = vbox(4.0, tags).apply { javafx.scene.layout.HBox.setHgrow(this, Priority.ALWAYS) }
            p?.description?.takeIf { it.isNotBlank() }?.let {
                lines.children += label(it, "muted", "small").apply { maxWidth = 760.0 }
            }
            row.wrapper?.repository?.name?.let { lines.children += label(it, "faint", "small") }
            failure?.let { lines.children += label("Failed to load: ${it.message ?: it}", "error-text", "small").apply { isWrapText = true; maxWidth = 760.0 } }

            val actions = hbox(8.0).apply { alignment = Pos.CENTER_RIGHT }
            when {
                row.internalName in busy -> actions.children += hbox(8.0, ProgressIndicator().apply { setPrefSize(18.0, 18.0) }, label("Installing…", "muted"))
                row.installed == null -> actions.children += button("Install", Icons.DOWNLOAD, "btn-accent", "btn-small") { install(row.wrapper!!) }
                else -> {
                    if (row.hasUpdate) actions.children += button("Update", Icons.REFRESH, "btn-accent", "btn-small") { install(row.wrapper!!) }
                    else actions.children += label(if (failure == null) "Installed" else "Error", if (failure == null) "good-text" else "error-text", "small")
                    actions.children += button(null, Icons.DELETE, "btn-danger", "btn-small") { uninstall(row) }.tooltip("Uninstall")
                }
            }
            return hbox(14.0, iconBox, lines, actions).apply {
                styleClass += "source-row"
                style = "-fx-cursor: default;"
                prefWidthProperty().bind(listView.widthProperty().subtract(24))
            }
        }
    }
}
