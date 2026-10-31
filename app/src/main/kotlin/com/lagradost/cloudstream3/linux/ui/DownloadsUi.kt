package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.linux.Downloads
import com.lagradost.cloudstream3.linux.Downloads.State
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.OfflineApi
import com.lagradost.cloudstream3.linux.Playable
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.launch
import java.io.File

/** Download buttons for title pages and the Downloads tab of the Library. */
object DownloadsUi {
    /** Runs [update] now and whenever downloads change, while [node] is on screen. */
    fun <T : Node> live(node: T, update: (T) -> Unit): T {
        var off: (() -> Unit)? = null
        var pending = false
        update(node)
        node.sceneProperty().addListener { _, _, scene ->
            off?.invoke(); off = null
            if (scene != null) {
                update(node)
                off = Downloads.onChange {
                    if (pending) return@onChange
                    pending = true
                    Platform.runLater { pending = false; update(node) }
                }
            }
        }
        return node
    }

    fun status(i: Downloads.Item): String = when (i.state) {
        State.QUEUED -> "Waiting"
        State.RUNNING -> if (i.progress >= 0) "${(i.progress * 100).toInt()}%" + (if (i.bytes > 0) " · ${human(i.bytes)}" else "") else "Starting…"
        State.PAUSED -> "Paused" + (if (i.progress > 0) " at ${(i.progress * 100).toInt()}%" else "")
        State.DONE -> "Downloaded" + (if (i.total > 0) " · ${human(i.total)}" else "")
        State.FAILED -> "Failed: ${i.error ?: "unknown error"}"
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    /** "Download" on a movie's page; shows progress and offers pause, resume or delete. */
    fun button(api: MainAPI, r: LoadResponse, p: Playable, label: String = "Download"): Button {
        val b = button(label, Icons.DOWNLOAD, "btn-ghost") {}
        b.setOnAction {
            val item = Downloads.get(api.name, p.data)
            if (item == null) start(api, r, p) else menu(b, item)
        }
        return live(b) { btn ->
            val item = Downloads.get(api.name, p.data)
            btn.text = when (item?.state) {
                null -> label
                State.RUNNING -> "Downloading " + (if (item.progress >= 0) "${(item.progress * 100).toInt()}%" else "…")
                State.QUEUED -> "Download waiting"
                State.PAUSED -> "Download paused"
                State.DONE -> "Downloaded"
                State.FAILED -> "Download failed"
            }
            btn.graphic = icon(if (item?.state == State.DONE) Icons.CHECK else Icons.DOWNLOAD, 18.0)
            btn.styleClass.remove("active"); if (item?.state == State.DONE) btn.styleClass += "active"
        }
    }

    /** A small round download icon for an episode card; the ring fills with progress. */
    fun episodeBadge(api: MainAPI, r: LoadResponse, p: Playable): Node {
        val text = label("", "dl-badge-text")
        val box = javafx.scene.layout.HBox(4.0, icon(Icons.DOWNLOAD, 16.0), text).apply {
            styleClass += "dl-badge"
            alignment = Pos.CENTER
            setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE)
        }
        box.setOnMouseClicked { e ->
            e.consume()
            val item = Downloads.get(api.name, p.data)
            if (item == null) start(api, r, p) else menu(box, item)
        }
        return live(box) { b ->
            val item = Downloads.get(api.name, p.data)
            b.styleClass.removeAll("idle", "busy", "done", "failed")
            b.styleClass += when (item?.state) { null -> "idle"; State.DONE -> "done"; State.FAILED -> "failed"; else -> "busy" }
            b.children[0] = icon(if (item?.state == State.DONE) Icons.CHECK else Icons.DOWNLOAD, 16.0)
            text.text = item?.takeIf { it.state == State.RUNNING || it.state == State.PAUSED }?.progress?.takeIf { it >= 0 }?.let { "${(it * 100).toInt()}%" } ?: ""
            text.isVisible = text.text.isNotEmpty(); text.isManaged = text.isVisible
            Tooltip(b, item?.let { status(it) } ?: "Download for offline viewing")
        }
    }

    private fun Tooltip(node: Node, text: String) {
        javafx.scene.control.Tooltip.uninstall(node, null)
        (node.properties["dl-tip"] as? javafx.scene.control.Tooltip)?.let { javafx.scene.control.Tooltip.uninstall(node, it) }
        val tip = javafx.scene.control.Tooltip(text).apply { showDelay = javafx.util.Duration.millis(300.0) }
        node.properties["dl-tip"] = tip
        javafx.scene.control.Tooltip.install(node, tip)
    }

    fun start(api: MainAPI, r: LoadResponse, p: Playable) {
        Downloads.start(api, r, p)
        Toasts.show("Downloading ${if (Engine.playables(r).size > 1) "${r.name} ${shortCode(p)}" else r.name}", "Downloads" to { LibraryPage.wantDownloads = true; Nav.window.show(Tab.LIBRARY) })
    }

    fun menu(anchor: Node, item: Downloads.Item) {
        val rows = mutableListOf<Node>()
        rows += Popover.row(null, status(item).take(60)) {}
        rows += Popover.separator()
        when (item.state) {
            State.DONE -> rows += Popover.row(icon(Icons.PLAY, 18.0), "Play downloaded copy") { play(item) }
            State.RUNNING, State.QUEUED -> rows += Popover.row(icon(Icons.PAUSE, 18.0), "Pause") { Downloads.pause(item.id) }
            State.PAUSED, State.FAILED -> rows += Popover.row(icon(Icons.REFRESH, 18.0), if (item.state == State.FAILED) "Try again" else "Resume") { Downloads.resume(item.id) }
        }
        rows += Popover.row(icon(Icons.FOLDER, 18.0), "Show in folder") { openFolder() }
        rows += Popover.row(icon(Icons.DELETE, 18.0), if (item.state == State.DONE) "Delete download" else "Cancel and delete") { Downloads.delete(item.id) }
        Popover.show(anchor, Popover.panel(*rows.toTypedArray()), alignRight = false)
    }

    fun play(item: Downloads.Item) {
        ui.launch {
            if (item.file == null || !File(item.file).isFile) { Toasts.show("The file is gone: ${item.file ?: ""}"); return@launch }
            val api = OfflineApi(item.apiName)
            val r = api.response(item)
            val p = Engine.playables(r).firstOrNull { it.data == item.data } ?: return@launch
            Playback.open(api, r, p)
        }
    }

    fun openFolder() {
        val dir = Downloads.folder().apply { mkdirs() }
        runCatching { ProcessBuilder("xdg-open", dir.absolutePath).start() }
    }

    /** The Library's Downloads tab: one row per movie or episode, grouped by title. */
    fun page(): Node {
        val list = Downloads.all()
        val head = hbox(12.0, label(Downloads.folder().path, "muted", "small"), spacer(),
            button("Open folder", Icons.FOLDER, "btn-ghost", "btn-small") { openFolder() })
        if (list.isEmpty()) return VBox(18.0, head, emptyState(Icons.DOWNLOAD, "No downloads",
            "Use Download on a movie, or the download icon on an episode, to watch it later without a connection."))
        val groups = list.groupBy { it.apiName + "\n" + it.url }.values
        val box = VBox(26.0, head)
        for (g in groups) {
            val first = g.first()
            val poster = ImageView().apply { fitWidth = 72.0; fitHeight = 108.0; isPreserveRatio = false }
            Images.load(poster, first.poster, 72.0, 108.0, null)
            val posterBox = StackPane(poster).apply { styleClass += "poster-frame"; setMinSize(72.0, 108.0); setMaxSize(72.0, 108.0); roundedClip(this, 8.0) }
            val rows = VBox(8.0)
            g.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).forEach { rows.children += row(it) }
            val done = g.filter { it.state == State.DONE }
            val header = hbox(12.0, label(first.title, "h3"), label(first.apiName, "tag"), spacer(),
                label("${done.size} of ${g.size} downloaded · ${human(done.sumOf { it.total })}", "muted", "small"))
            box.children += hbox(18.0, posterBox, VBox(10.0, header, rows).apply { javafx.scene.layout.HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS) }).apply { alignment = Pos.TOP_LEFT }
        }
        return box
    }

    private fun row(item: Downloads.Item): Node {
        val code = listOfNotNull(item.season?.let { "S$it" }, item.episode?.let { "E$it" }).joinToString(" ")
        val title = label(when {
            !item.isEpisode -> "Movie"
            code.isNotEmpty() -> listOfNotNull(code, item.episodeName).joinToString(" · ")
            else -> item.label ?: item.episodeName ?: "Episode"
        }, "bold")
        val stat = label("", "muted", "small")
        val bar = StackPane()
        val actions = hbox(6.0)
        val row = hbox(14.0, VBox(3.0, title, stat).apply { minWidth = 260.0; maxWidth = 420.0 }, bar, spacer(), actions).apply {
            styleClass.addAll("card-row", "hover-row"); pad(10.0, 12.0)
        }
        return live(row) {
            val i = Downloads.all().firstOrNull { it.id == item.id } ?: return@live
            stat.text = status(i)
            stat.styleClass.remove("bad"); if (i.state == State.FAILED) stat.styleClass += "bad"
            bar.children.setAll(if (i.state != State.DONE && i.progress >= 0) progressBar(i.progress, 200.0) else javafx.scene.layout.Region())
            actions.children.setAll(listOfNotNull(
                if (i.state == State.DONE) button("Play", Icons.PLAY, "btn-accent", "btn-small") { play(i) } else null,
                when (i.state) {
                    State.RUNNING, State.QUEUED -> button(null, Icons.PAUSE, "btn-ghost", "btn-small") { Downloads.pause(i.id) }.tooltip("Pause")
                    State.PAUSED, State.FAILED -> button(null, Icons.REFRESH, "btn-ghost", "btn-small") { Downloads.resume(i.id) }.tooltip(if (i.state == State.FAILED) "Try again" else "Resume")
                    State.DONE -> null
                },
                button(null, Icons.DELETE, "btn-ghost", "btn-small") { Downloads.delete(i.id) }.tooltip(if (i.state == State.DONE) "Delete" else "Cancel and delete"),
            ))
        }
    }
}
