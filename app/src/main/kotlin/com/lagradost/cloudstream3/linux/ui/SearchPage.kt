package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginManager
import javafx.animation.PauseTransition
import javafx.geometry.Pos
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Searches every provider in parallel; each provider's row appears the moment it answers. */
class SearchPage : StackPane(), Refreshable {
    private val field = TextField().apply {
        styleClass.setAll("text-field", "search-field")
        promptText = "Search movies, series, anime…"
    }
    private val filter = ChipGroup { run() }
    private var apis: List<MainAPI> = emptyList()
    private val status = hbox(10.0)
    private val results = VBox(30.0)
    private var job: Job? = null
    private val debounce = PauseTransition(Duration.millis(450.0)).apply { setOnFinished { run() } }
    private var lastQuery = ""

    init {
        val searchIcon = icon(Icons.SEARCH, 20.0).apply { style += "-fx-background-color: #6b6b78;" }
        val fieldBox = StackPane(field, searchIcon).apply { maxWidth = 760.0 }
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT)
        StackPane.setMargin(searchIcon, javafx.geometry.Insets(0.0, 0.0, 0.0, 16.0))
        field.textProperty().addListener { _, _, _ -> debounce.playFromStart() }
        field.setOnAction { debounce.stop(); run() }
        val top = vbox(16.0, label("Search", "h2"), fieldBox, filter, status)
        children += pageScroll(vbox(26.0, top, results).pad(22.0, 36.0, 40.0, 36.0))
        ui.launch { PluginManager.version.collect { refreshProviders() } }
    }

    fun focusField() {
        field.requestFocus()
        field.selectAll()
    }

    override fun onShow() {
        if (results.children.isEmpty() && field.text.isBlank()) {
            results.children.setAll(emptyState(Icons.SEARCH, "Find something to watch", "Results stream in from every installed extension as each one answers. Press / anywhere to search."))
        }
        javafx.application.Platform.runLater { field.requestFocus() }
    }

    private fun refreshProviders() {
        apis = Engine.providers()
        val names = listOf("All") + apis.map { it.name }
        filter.setItems(names, filter.selected.coerceAtLeast(0))
    }

    fun run(query: String = field.text.trim()) {
        if (query.length < 2) return
        if (field.text.trim() != query) field.text = query
        val selected = filter.selected
        val targets = if (selected <= 0) apis else listOfNotNull(apis.getOrNull(selected - 1))
        if (targets.isEmpty()) {
            results.children.setAll(emptyState(Icons.EXTENSION, "No extensions installed", "Install extensions from a repository first.",
                button("Open Extensions", Icons.EXTENSION, "btn-accent") { Nav.window.show(Tab.EXTENSIONS) }))
            return
        }
        lastQuery = query
        job?.cancel()
        results.children.clear()
        var answered = 0
        var found = 0
        val empty = mutableListOf<String>()
        fun updateStatus() {
            val done = answered == targets.size
            status.children.setAll(listOfNotNull(
                if (!done) ProgressIndicator().apply { setPrefSize(16.0, 16.0) } else null,
                label(
                    if (done) "$found result(s) from ${targets.size - empty.size} of ${targets.size} provider(s)" +
                        (if (empty.isNotEmpty()) " · nothing from ${empty.joinToString()}" else "")
                    else "Searching ${targets.size} provider(s)… $answered answered, $found result(s)",
                    "muted",
                ).apply { isWrapText = true },
            ))
        }
        updateStatus()
        job = ui.launch {
            Engine.search(query, targets).collect { batch ->
                answered++
                if (batch.results.isEmpty()) {
                    empty += batch.api.name
                } else {
                    found += batch.results.size
                    results.children += Shelf(batch.api.name, trailing = label("${batch.results.size}", "tag")).apply {
                        batch.results.forEach { item -> add(PosterCard.of(item) { Nav.open(batch.api, item) }) }
                    }
                }
                updateStatus()
            }
            if (found == 0) results.children.setAll(emptyState(Icons.SEARCH, "No results for \"$query\"", "Try a different spelling, or check that the extensions' sites are reachable (Settings → DNS)."))
        }
    }
}
