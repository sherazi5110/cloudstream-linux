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
        tabs.setItems(listOf("Continue Watching", "My list", "History", "Downloads"))
        children += pageScroll(vbox(22.0, label("Library", "h2"), tabs, body).pad(22.0, 36.0, 40.0, 36.0))
        Library.onChange { ui.launch { if (scene != null) render() } }
        // The Downloads tab rows update themselves; re-render only when one is added or removed.
        var count = -1
        com.lagradost.cloudstream3.linux.Downloads.onChange {
            javafx.application.Platform.runLater {
                val n = com.lagradost.cloudstream3.linux.Downloads.all().size
                if (n != count) { count = n; if (scene != null && tabs.selected == 3) render() }
            }
        }
    }

    override fun onShow() {
        if (wantDownloads) { wantDownloads = false; tabs.select(3) }
        render()
    }

    private fun render() {
        body.children.setAll(when (tabs.selected) {
            0 -> continueWatching()
            1 -> bookmarks()
            2 -> history()
            else -> DownloadsUi.page()
        })
    }

    private fun continueWatching(): Node {
        val items = Library.continueWatching()
        if (items.isEmpty()) return emptyState(Icons.PLAY, "Nothing in progress", "Titles you start watching show up here so you can resume where you left off.")
        return FlowPane(18.0, 22.0).apply {
            items.forEach { e ->
                children += WideCard(e.name, listOfNotNull(e.episodeLabel, "${clock(e.position)} / ${clock(e.duration)}").joinToString("  ·  "), e.posterUrl, e.progress,
                    continueActions(e)) {
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
                val watched = Library.lastWatched(b.apiName, b.url)?.finished == true
                children += PosterCard(b.name, b.posterUrl, topLeft = if (watched) "✓ Watched" else null, topRight = b.apiName.take(14)) { Nav.open(b.apiName, b.url, b.name, b.posterUrl) }
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
                if (e.finished) label("✓ Watched", "watched-badge") else progressBar(e.progress, 120.0),
                button(null, if (e.finished) Icons.REPLAY_10 else Icons.CHECK, "btn-ghost", "btn-small") {
                    if (e.finished) Library.markUnwatched(e.apiName, e.url, listOf(e.episodeData)) else Library.markWatched(e)
                }.apply {
                    tooltip(if (e.finished) "Mark as not watched" else "Mark as watched")
                    addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED) { it.consume() }
                },
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

    companion object {
        /** Set before showing the Library to open it on the Downloads tab. */
        @JvmStatic var wantDownloads = false
    }
}
