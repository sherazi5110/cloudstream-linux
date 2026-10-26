package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.AppDirs
import com.lagradost.cloudstream3.linux.DnsProvider
import com.lagradost.cloudstream3.linux.Logs
import com.lagradost.cloudstream3.linux.Network
import com.lagradost.cloudstream3.linux.Platform
import com.lagradost.cloudstream3.linux.Player
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.Settings
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.io.File

class SettingsPage : StackPane(), Refreshable {
    private val body = VBox(26.0)

    init {
        children += pageScroll(vbox(22.0, label("Settings", "h2"), body).pad(22.0, 36.0, 40.0, 36.0).apply { maxWidth = 980.0 })
        render()
    }

    override fun onShow() = render()

    private fun section(title: String, vararg rows: Node) = vbox(10.0,
        label(title.uppercase(), "section-label"),
        VBox(*rows).apply { styleClass += "card"; pad(4.0, 18.0) },
    )

    private fun row(title: String, description: String?, control: Node) = hbox(18.0,
        vbox(3.0, label(title, "bold"), label(description, "muted", "small").apply {
            isWrapText = true; maxWidth = 560.0; isVisible = description != null; isManaged = isVisible
        }),
        spacer(),
        control,
    ).apply { styleClass += "card-row" }

    private fun <T> combo(options: List<T>, selected: T, text: (T) -> String, onChange: (T) -> Unit) = ComboBox<String>().apply {
        items.setAll(options.map(text))
        selectionModel.select(options.indexOf(selected).coerceAtLeast(0))
        prefWidth = 220.0
        setOnAction { options.getOrNull(selectionModel.selectedIndex)?.let(onChange) }
    }

    private fun open(file: File) {
        runCatching { ProcessBuilder("xdg-open", file.absolutePath).start() }
    }

    private fun render() {
        val installed = Player.installed()
        val qualities = listOf(0, 2160, 1440, 1080, 720, 480, 360)
        val mpvArgs = TextField(Settings.mpvArgs).apply {
            promptText = "e.g. --fs --volume=80"
            prefWidth = 320.0
            focusedProperty().addListener { _, _, focused -> if (!focused) Settings.mpvArgs = text.trim() }
            setOnAction { Settings.mpvArgs = text.trim(); Toasts.show("Saved") }
        }
        body.children.setAll(
            section("Playback",
                row("Video player", if (installed.isEmpty()) "No player found — install one with: sudo apt install mpv" else "mpv is recommended: hardware decoding, resume and progress tracking.",
                    combo(Player.supported, Settings.player, { if (it in installed) it else "$it (not installed)" }) { Settings.player = it }),
                row("Auto-play best source", "Start the best source automatically instead of picking from the list.",
                    Switch(Settings.autoPlay) { Settings.autoPlay = it }),
                row("Preferred quality", "Sources up to this quality are ranked first.",
                    combo(qualities, Settings.preferredQuality, { if (it == 0) "Best available" else "${it}p" }) { Settings.preferredQuality = it }),
                row("Extra mpv options", "Passed to mpv as-is.", mpvArgs),
                row("Source search limit", "How long an extension may keep looking for sources. Sources found earlier play right away.",
                    combo(listOf(20, 40, 60, 120), Settings.sourcesTimeoutSeconds, { "$it seconds" }) { Settings.sourcesTimeoutSeconds = it }),
            ),
            section("Network",
                row("DNS over HTTPS", "Bypasses ISP DNS blocking of streaming sites, like the Android app's DNS setting. Applies immediately.",
                    combo(DnsProvider.entries, Settings.dns, { it.label }) {
                        Settings.dns = it
                        Network.init()
                        Toasts.show("DNS: ${it.label}")
                    }),
                row("Load repositories through jsDelivr", "Use the jsDelivr CDN for GitHub-hosted repositories when raw.githubusercontent.com is slow or blocked.",
                    Switch(RepositoryManager.useJsDelivr) { RepositoryManager.useJsDelivr = it }),
            ),
            section("Extensions",
                row("Update extensions on startup", "Checks your repositories for newer versions each time the app starts.",
                    Switch(Settings.autoUpdateExtensions) { Settings.autoUpdateExtensions = it }),
            ),
            section("Storage",
                row("Data folder", AppDirs.data.absolutePath, button("Open", Icons.FOLDER, "btn-ghost", "btn-small") { open(AppDirs.data) }),
                row("Logs", "Attach cloudstream.log when reporting a problem.", button("Open", Icons.FOLDER, "btn-ghost", "btn-small") { open(Logs.file.parentFile) }),
                row("Image cache", "Posters are cached on disk for fast scrolling.", button("Clear", Icons.DELETE, "btn-ghost", "btn-small") {
                    Images.clearDisk(); Toasts.show("Image cache cleared")
                }),
                row("Translated extensions", "Android extensions are converted once and cached; clearing forces a fresh conversion on next start.",
                    button("Clear", Icons.DELETE, "btn-ghost", "btn-small") {
                        File(Platform.context.getCacheDir(), "translated").deleteRecursively()
                        Toasts.show("Cleared — restart to re-translate")
                    }),
            ),
            section("About",
                row(AppInfo.NAME, "Version ${AppInfo.VERSION} · runs CloudStream extensions with the upstream CloudStream library. Free software under GPL-3.0.",
                    label("v${AppInfo.VERSION}", "tag", "accent")),
                row("Keyboard", "/ or Ctrl+F search · Esc back / close · Alt+← back", label("", "muted")),
            ),
        )
    }
}
