package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Library
import javafx.scene.Node
import javafx.scene.layout.FlowPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Continue Watching, bookmarks and full history. */
class LibraryPage : StackPane(), Refreshable {
    private val tabs = ChipGroup { render() }
    private val body = VBox(20.0)

    init {
        tabs.setItems(listOf("Continue Watching", "My List", "History"))
        children += pageScroll(vbox(22.0, label("Library", "h2"), tabs, body).pad(22.0, 36.0, 40.0, 36.0))
        Library.onChange { ui.launch { if (scene != null) render() } }
    }

    override fun onShow() = render()

    private fun render() {
        body.children.setAll(when (tabs.selected) {
            0 -> continueWatching()
            1 -> bookmarks()
            else -> history()
        })
    }

    private fun continueWatching(): Node {
        val items = Library.continueWatching()
        if (items.isEmpty()) return emptyState(Icons.PLAY, "Nothing in progress", "Titles you start watching show up here so you can resume where you left off.")
        return FlowPane(18.0, 22.0).apply {
            items.forEach { e ->
                children += WideCard(e.name, listOfNotNull(e.episodeLabel, "${clock(e.position)} / ${clock(e.duration)}").joinToString("  ·  "), e.posterUrl, e.progress) {
                    Nav.open(e.apiName, e.url, e.name, e.posterUrl)
                }
            }
        }
    }

    private fun bookmarks(): Node {
        val items = Library.bookmarks()
        if (items.isEmpty()) return emptyState(Icons.BOOKMARK_OUTLINE, "Your list is empty", "Use “Add to Library” on any title to keep it here.")
        return FlowPane(18.0, 22.0).apply {
            items.forEach { b ->
                children += PosterCard(b.name, b.posterUrl, topRight = b.apiName.take(14)) { Nav.open(b.apiName, b.url, b.name, b.posterUrl) }
            }
        }
    }

    private fun history(): Node {
        val items = Library.history()
        if (items.isEmpty()) return emptyState(Icons.HISTORY, "No history yet", "Everything you play is listed here.")
        val format = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
        val rows = items.map { e ->
            hbox(14.0,
                label(format.format(Instant.ofEpochMilli(e.updatedAt)), "faint", "small").apply { minWidth = 92.0 },
                vbox(2.0, label(e.name, "bold"), label(listOfNotNull(e.episodeLabel, e.apiName).joinToString("  ·  "), "muted", "small")),
                spacer(),
                progressBar(e.progress, 120.0),
                button(null, Icons.DELETE, "btn-ghost", "btn-small") { Library.removeHistory(e.apiName, e.url) }.apply {
                    tooltip("Remove from history")
                    addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED) { it.consume() }
                },
            ).apply {
                styleClass.addAll("card-row", "hover-row")
                pad(10.0, 12.0)
                onClick { Nav.open(e.apiName, e.url, e.name, e.posterUrl) }
            }
        }
        return vbox(12.0,
            hbox(10.0, spacer(), button("Clear history", Icons.DELETE, "btn-danger", "btn-small") { Library.clearHistory() }),
            VBox(*rows.toTypedArray()).apply { styleClass += "card"; pad(6.0) },
        )
    }
}
