package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.AppDirs
import com.lagradost.cloudstream3.linux.CompatChecker
import com.lagradost.cloudstream3.linux.DnsProvider
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Logs
import com.lagradost.cloudstream3.linux.Network
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.ProviderTest
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.StorageInfo
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings: a searchable category list on the left, the category's cards on the right. */
class SettingsPage : StackPane(), Refreshable {
    private data class Category(val id: String, val title: String, val icon: String, val build: () -> List<Node>)

    private val categories = listOf(
        Category("appearance", "Appearance", Icons.PALETTE, ::appearance),
        Category("playback", "Playback", Icons.PLAY_CIRCLE, ::playback),
        Category("network", "Network & DNS", Icons.NETWORK, ::network),
        Category("extensions", "Extensions", Icons.EXTENSION, ::extensions),
        Category("developer", "Developer & logs", Icons.CODE, ::developer),
        Category("storage", "Storage", Icons.STORAGE, ::storage),
        Category("about", "About", Icons.INFO, ::about),
    )

    /** Every setting's title and description, for search. */
    private val index = mutableListOf<Triple<String, String, String>>()
    private val nav = VBox()
    private val body = VBox(26.0)
    private val search = TextField().apply { promptText = "Search settings" }
    private var current = "appearance"

    init {
        nav.styleClass += "settings-nav"
        search.textProperty().addListener { _, _, q -> if (q.isBlank()) open(current) else showSearch(q) }
        val left = VBox(14.0, label("Settings", "h2"), search, nav).apply { minWidth = 250.0; maxWidth = 250.0 }
        val right = pageScroll(body.pad(6.0, 6.0, 40.0, 6.0))
        HBox.setHgrow(right, Priority.ALWAYS)
        children += HBox(26.0, left, right).pad(22.0, 32.0, 0.0, 22.0)
        buildIndex()
        renderNav()
        open(current)
    }

    override fun onShow() {
        if (search.text.isBlank()) open(current)
    }

    private fun renderNav() {
        nav.children.setAll(categories.map { c ->
            label(c.title, "settings-nav-item").apply {
                graphic = icon(c.icon, 18.0)
                if (c.id == current) styleClass += "active"
                onClick { search.clear(); open(c.id) }
            }
        })
    }

    fun open(id: String) {
        current = id
        renderNav()
        val c = categories.first { it.id == id }
        body.children.setAll(listOf<Node>(label(c.title, "h2")) + c.build())
    }

    private fun buildIndex() {
        index.clear()
        indexing = true
        categories.forEach { c -> indexCategory = c.id; c.build() }
        indexing = false
    }

    private var indexing = false
    private var indexCategory = ""

    private fun showSearch(q: String) {
        val query = q.lowercase()
        val hits = index.filter { (_, t, d) -> t.lowercase().contains(query) || d.lowercase().contains(query) }
        body.children.setAll(listOf<Node>(label("Results for “$q”", "h2")) + if (hits.isEmpty()) listOf(label("No settings match.", "muted")) else listOf(
            VBox(*hits.map { (cat, t, d) ->
                hbox(14.0,
                    vbox(3.0, label(t, "bold"), label(d, "muted", "small").apply { isWrapText = true; maxWidth = 640.0 }),
                    spacer(),
                    label(categories.first { it.id == cat }.title, "tag"),
                ).apply {
                    styleClass.addAll("card-row", "hover-row")
                    pad(10.0, 12.0)
                    onClick { search.clear(); open(cat) }
                }
            }.toTypedArray()).apply { styleClass += "card"; pad(6.0) },
        ))
    }

    // ---------- building blocks ----------

    private fun section(title: String, vararg rows: Node) = vbox(10.0,
        label(title.uppercase(), "section-label"),
        VBox(*rows).apply { styleClass += "card"; pad(4.0, 18.0) },
    )

    private fun row(title: String, description: String?, control: Node): Node {
        if (indexing) index += Triple(indexCategory, title, description ?: "")
        return hbox(18.0,
            vbox(3.0, label(title, "bold"), label(description, "muted", "small").apply {
                isWrapText = true; maxWidth = 560.0; isVisible = description != null; isManaged = isVisible
            }).apply { HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 },
            // Long descriptions (paths) wrap; the controls keep their full size.
            control.also { (it as? javafx.scene.layout.Region)?.minWidth = javafx.scene.layout.Region.USE_PREF_SIZE },
        ).apply { styleClass += "card-row" }
    }

    private fun <T> combo(options: List<T>, selected: T, text: (T) -> String, onChange: (T) -> Unit) = ComboBox<String>().apply {
        items.setAll(options.map(text))
        selectionModel.select(options.indexOf(selected).coerceAtLeast(0))
        prefWidth = 240.0
        setOnAction { options.getOrNull(selectionModel.selectedIndex)?.let(onChange) }
    }

    private fun textSetting(value: String, prompt: String, save: (String) -> Unit) = TextField(value).apply {
        promptText = prompt
        prefWidth = 300.0
        focusedProperty().addListener { _, _, focused -> if (!focused) save(text.trim()) }
        setOnAction { save(text.trim()); Toasts.show("Saved") }
    }

    private fun openFolder(file: File) {
        file.mkdirs()
        runCatching { ProcessBuilder("xdg-open", file.absolutePath).start() }
    }

    // ---------- categories ----------

    private fun appearance(): List<Node> {
        val presets = FlowPane(10.0, 10.0).apply {
            ThemePreset.entries.forEach { p ->
                val bg = when (p.mode) {
                    ThemeMode.GLASS -> "linear-gradient(to bottom right, ${p.accent}, #0b0820 70%)"
                    ThemeMode.LIGHT -> "#f4f4f7"
                    ThemeMode.AMOLED -> "#000000"
                    ThemeMode.MIDNIGHT -> "#0a0f1c"
                    ThemeMode.DARK -> "#0b0b10"
                }
                val dot = Region().apply { style = "-fx-background-color: ${p.accent}; -fx-background-radius: 999;"; setMinSize(14.0, 14.0); setMaxSize(14.0, 14.0) }
                val text = label(p.label, "bold").apply { style = "-fx-text-fill: ${if (p.mode == ThemeMode.LIGHT) "#15151b" else "white"};" }
                children += hbox(8.0, dot, text).apply {
                    styleClass += "preset-card"
                    if (Theme.preset == p) styleClass += "selected"
                    style = "-fx-background-color: $bg;"
                    onClick { Theme.apply(p); open("appearance") }
                }
            }
        }
        val modes = FlowPane(12.0, 12.0).apply {
            ThemeMode.entries.forEach { m ->
                val preview = when (m) {
                    ThemeMode.DARK -> "#0b0b10" to "#1d1d26"
                    ThemeMode.AMOLED -> "#000000" to "#131316"
                    ThemeMode.MIDNIGHT -> "#0a0f1c" to "#18223a"
                    ThemeMode.LIGHT -> "#f4f4f7" to "#ffffff"
                    ThemeMode.GLASS -> "linear-gradient(to bottom right, #6d28d9, #0ea5e9 60%, #ec4899)" to "rgba(255,255,255,0.28)"
                }
                val swatch = StackPane(Region().apply {
                    style = "-fx-background-color: ${preview.second}; -fx-background-radius: 6;"
                    setMaxSize(70.0, 26.0)
                }, Region().apply {
                    style = "-fx-background-color: ${Theme.accent}; -fx-background-radius: 999;"
                    setMaxSize(26.0, 6.0); StackPane.setAlignment(this, Pos.BOTTOM_LEFT); translateX = 10.0; translateY = -10.0
                }).apply {
                    style = "-fx-background-color: ${preview.first}; -fx-background-radius: 8;"
                    setMinSize(120.0, 64.0); setMaxSize(120.0, 64.0)
                }
                children += vbox(8.0, swatch, label(m.label, "bold")).apply {
                    styleClass += "mode-card"
                    if (Theme.mode == m) styleClass += "selected"
                    onClick { Theme.mode = m; open("appearance") }
                }
            }
        }
        val accents = hbox(10.0).apply {
            Accent.entries.forEach { a ->
                children += Region().apply {
                    styleClass += "swatch"
                    style = "-fx-background-color: ${a.hex};"
                    if (Theme.accent.equals(a.hex, true)) styleClass += "selected"
                    tooltip(a.label)
                    onClick { Theme.accent = a.hex; open("appearance") }
                }
            }
            val custom = TextField(Theme.accent).apply { prefWidth = 110.0; promptText = "#rrggbb" }
            custom.setOnAction {
                val v = custom.text.trim().let { if (it.startsWith("#")) it else "#$it" }
                if (v.matches(Regex("#[0-9a-fA-F]{6}"))) { Theme.accent = v; open("appearance") } else Toasts.show("Use a colour like #22c55e")
            }
            children.addAll(Region().apply { minWidth = 8.0 }, custom)
        }
        val wallpaper = Theme.wallpaper
        val dim = Slider(0.2, 0.95, Theme.wallpaperDim).apply {
            prefWidth = 240.0; minWidth = 240.0; maxWidth = 240.0
            valueChangingProperty().addListener { _, _, changing -> if (!changing) Theme.wallpaperDim = value }
            setOnMouseReleased { Theme.wallpaperDim = value }
        }
        return listOf(
            section("Theme",
                row("Presets", "One click for a matching mode and accent colour.", Region()),
                presets.pad(0.0, 0.0, 12.0, 0.0),
                row("Mode", "Dark, true-black AMOLED (saves power on OLED screens), midnight blue, light, or liquid glass (translucent panels over a soft colour backdrop or your wallpaper).", Region()),
                modes.pad(0.0, 0.0, 12.0, 0.0),
                row("Accent colour", "Buttons, highlights and the player's progress bar. Enter a hex colour for your own.", accents),
            ),
            section("Home & details",
                row("Featured slider", "Rotate the big banner on Home through the first catalog's top titles.",
                    Switch(Settings.heroSlider) { Settings.heroSlider = it }),
                row("Anti-spoiler mode", "Blur thumbnails and hide descriptions of episodes you haven't watched yet.",
                    Switch(Settings.antiSpoiler) { Settings.antiSpoiler = it }),
            ),
            section("Background",
                row("Wallpaper", wallpaper?.let { File(it).name } ?: "Show your own image behind the app.",
                    hbox(8.0,
                        button(if (wallpaper == null) "Choose image" else "Change", Icons.IMAGE, "btn-ghost", "btn-small") {
                            FileChooser().apply {
                                title = "Choose wallpaper"
                                extensionFilters += FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.gif")
                            }.showOpenDialog(scene.window)?.let { file ->
                                // Copy it so the wallpaper survives the original being moved.
                                val target = File(AppDirs.data, "wallpaper.${file.extension}")
                                file.copyTo(target, overwrite = true)
                                Theme.wallpaper = target.absolutePath
                                open("appearance")
                            }
                        },
                        if (wallpaper != null) button("Remove", Icons.DELETE, "btn-danger", "btn-small") { Theme.wallpaper = null; open("appearance") } else Region(),
                    )),
                row("Dim", "Darkens (or lightens) the wallpaper so text stays readable.", dim),
                row("Blur", "Soft-focus the wallpaper.", Switch(Theme.wallpaperBlur) { Theme.wallpaperBlur = it }),
            ),
        )
    }

    private fun playback(): List<Node> {
        val vlcEngine = Vlc.factory != null
        val qualities = listOf(0, 2160, 1440, 1080, 720, 480)
        return listOf(
            section("Player",
                row("Player engine", if (!vlcEngine) "VLC's libraries aren't installed, so videos can't play. Run: sudo apt install libvlc5 vlc-plugin-base" else
                    "Videos play inside this window using VLC ${Vlc.version.orEmpty()}: every format VLC plays, hardware decoding, source switching, online subtitles, audio languages, speed and zoom.",
                    label(if (vlcEngine) "Ready" else "Not installed", if (vlcEngine) "good-text" else "error-text")),
                row("Battery saver", "For low-end PCs and laptops on battery: skips the H.264 deblocking filter (much less CPU), buffers longer and does no extra processing. Hardware decoding stays on.",
                    Switch(Settings.batterySaver) { Settings.batterySaver = it; Vlc.optionsChanged(Playback.active != null) }),
            ),
            section("Sources",
                row("Auto-play best source", "Start the best source automatically instead of picking from the list.",
                    Switch(Settings.autoPlay) { Settings.autoPlay = it }),
                row("Preferred quality", "Sources up to this quality are ranked first. On a low-end PC, 1080p or 720p saves the most power.",
                    combo(qualities, Settings.preferredQuality, { if (it == 0) "Best available" else "${it}p" }) { Settings.preferredQuality = it }),
                row("Source search limit", "How long an extension may keep looking. Sources found earlier are usable right away.",
                    combo(listOf(20, 40, 60, 120), Settings.sourcesTimeoutSeconds, { "$it seconds" }) { Settings.sourcesTimeoutSeconds = it }),
            ),
            section("Episodes & subtitles",
                row("Auto-play next episode", "When an episode finishes, start finding sources for the next one.",
                    Switch(Settings.autoPlayNext) { Settings.autoPlayNext = it }),
                row("Subtitle languages", "Language codes to turn on automatically, e.g. en or ar,en. Empty = subtitles off by default.",
                    textSetting(Settings.subtitleLanguage, "en") { Settings.subtitleLanguage = it }),
                row("Audio languages", "Picked automatically when a stream has several, e.g. ja for original anime audio, or en. Empty = the stream's default.",
                    textSetting(Settings.audioLanguage, "en") { Settings.audioLanguage = it }),
            ),
            subtitleStyle(),
        )
    }

    /** Subtitle look, with a live preview. VLC applies it from the next video on. */
    private fun subtitleStyle(): Node {
        val sample = Label("The quick brown fox jumps over the lazy dog")
        fun refresh() {
            val px = when (Settings.subtitleSize) { "SMALL" -> 17; "LARGE" -> 27; "HUGE" -> 34; else -> 21 }
            val outline = Settings.subtitleOutline
            sample.style = "-fx-font-size: ${px}px; -fx-font-weight: bold; -fx-text-fill: ${Settings.subtitleColor};" +
                (if (outline > 0) " -fx-effect: dropshadow(gaussian, black, ${outline * 1.6}, 0.9, 0, 0);" else "") +
                (if (Settings.subtitleBackground) " -fx-background-color: rgba(0,0,0,0.67); -fx-padding: 2 8 2 8;" else "")
        }
        fun changed() { refresh(); Vlc.optionsChanged(Playback.active != null) }
        refresh()
        val preview = StackPane(sample).apply { styleClass += "subtitle-preview"; alignment = Pos.BOTTOM_CENTER }
        val colors = hbox(8.0).apply {
            listOf("#ffffff", "#fff176", "#80deea", "#a5d6a7", "#ffab91").forEach { c ->
                children += Region().apply {
                    styleClass += "swatch"; style = "-fx-background-color: $c;"
                    if (Settings.subtitleColor.equals(c, true)) styleClass += "selected"
                    onClick { Settings.subtitleColor = c; changed(); open("playback") }
                }
            }
        }
        return section("Subtitle style",
            preview.pad(4.0, 0.0, 8.0, 0.0),
            row("Size", null, combo(listOf("SMALL", "NORMAL", "LARGE", "HUGE"), Settings.subtitleSize, { it.lowercase().replaceFirstChar(Char::uppercase) }) { Settings.subtitleSize = it; changed() }),
            row("Colour", null, colors),
            row("Outline", "A dark edge keeps text readable on bright scenes.",
                combo(listOf(0, 2, 4, 6), Settings.subtitleOutline, { when (it) { 0 -> "None"; 2 -> "Thin"; 4 -> "Normal"; else -> "Thick" } }) { Settings.subtitleOutline = it; changed() }),
            row("Background box", "A dark box behind the text.", Switch(Settings.subtitleBackground) { Settings.subtitleBackground = it; changed() }),
        )
    }

    private fun network(): List<Node> {
        val result = label("", "muted", "small").apply { isWrapText = true }
        return listOf(
            section("DNS",
                row("DNS over HTTPS", "Gets past ISP blocking of streaming sites, like the Android app's DNS setting. Applies immediately.",
                    combo(DnsProvider.entries, Settings.dns, { it.label }) {
                        Settings.dns = it
                        Network.init()
                        Toasts.show("DNS: ${it.label}")
                    }),
                row("Automatic fallback", "If the DNS-over-HTTPS resolver fails, use system DNS for a minute instead of failing requests.",
                    Switch(Settings.dnsFallback) { Settings.dnsFallback = it; Network.init() }),
            ),
            section("Connections",
                row("Load repositories through jsDelivr", "Use the jsDelivr CDN when raw.githubusercontent.com is slow or blocked.",
                    Switch(RepositoryManager.useJsDelivr) { RepositoryManager.useJsDelivr = it }),
                row("Cookies", "Sites' login and session cookies (${Network.cookies.size} stored).",
                    button("Clear", Icons.DELETE, "btn-ghost", "btn-small") { Network.cookies.clear(); Toasts.show("Cookies cleared") }),
                row("Connection test", "Checks DNS and HTTPS to a few well-known hosts with the current settings.",
                    button("Run test", Icons.REFRESH, "btn-ghost", "btn-small") {
                        result.text = "Testing…"
                        ui.launch {
                            result.text = withContext(Dispatchers.IO) {
                                listOf("https://raw.githubusercontent.com", "https://www.google.com", "https://cloudflare.com").joinToString("\n") { url ->
                                    val start = System.currentTimeMillis()
                                    val r = runCatching {
                                        Network.client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { it.code }
                                    }
                                    "${url.removePrefix("https://")}: " + r.fold({ "HTTP $it in ${System.currentTimeMillis() - start} ms" }, { "failed (${it.message})" })
                                }
                            }
                        }
                    }),
                result,
            ),
        )
    }

    private fun extensions(): List<Node> = listOf(
        section("Extensions",
            row("Manage extensions", "Repositories, installing, updating and removing extensions.",
                button("Open", Icons.EXTENSION, "btn-accent", "btn-small") { Nav.window.show(Tab.EXTENSIONS) }),
            row("Update on startup", "Checks your repositories for newer versions each time the app starts.",
                Switch(Settings.autoUpdateExtensions) { Settings.autoUpdateExtensions = it }),
            row("Translated extensions", "Android extensions are converted for Linux once and cached. Clearing forces a fresh conversion on next start.",
                button("Clear", Icons.DELETE, "btn-ghost", "btn-small") {
                    StorageInfo.clear(File(com.lagradost.cloudstream3.linux.Platform.context.getCacheDir(), "translated"))
                    Toasts.show("Cleared — restart to re-translate")
                }),
        ),
    )

    private fun developer(): List<Node> {
        val tests = VBox(8.0)
        fun testRow(api: com.lagradost.cloudstream3.MainAPI): Node {
            val steps = VBox(4.0)
            val state = label("Not tested", "faint", "small")
            lateinit var run: () -> Unit
            val runButton = button("Test", Icons.BUG, "btn-ghost", "btn-small") { run() }
            run = {
                runButton.isDisable = true
                state.text = "Testing…"
                steps.children.clear()
                ui.launch {
                    var ok = true
                    ProviderTest.run(api).collect { s ->
                        ok = ok && s.ok
                        steps.children += hbox(8.0,
                            label(if (s.ok) "✓" else "✗", if (s.ok) "good-text" else "error-text", "bold"),
                            label(s.name, "bold", "small"),
                            label("${s.detail} · ${s.ms} ms", "muted", "small").apply { isWrapText = true; maxWidth = 560.0 },
                        )
                    }
                    state.text = if (ok) "Working" else "Problem found"
                    state.styleClass.setAll("label", if (ok) "good-text" else "error-text", "small")
                    runButton.isDisable = false
                }
            }
            return vbox(6.0, hbox(12.0, label(api.name, "bold"), label(api.mainUrl, "faint", "small"), spacer(), state, runButton), steps)
                .apply { styleClass += "card-row" }
        }
        Engine.providers().forEach { tests.children += testRow(it) }
        if (tests.children.isEmpty()) tests.children += label("No extensions loaded.", "muted").pad(12.0)

        val compat = VBox(6.0)
        val compatButton = button("Check installed extensions", Icons.BUG, "btn-ghost", "btn-small") {
            compat.children.setAll(hbox(8.0, ProgressIndicator().apply { setPrefSize(16.0, 16.0) }, label("Checking…", "muted")))
            ui.launch {
                val results = withContext(Dispatchers.IO) {
                    PluginManager.installed().map { p ->
                        p.name.orEmpty().ifEmpty { p.internalName } to runCatching { CompatChecker.check(File(p.filePath)) }
                    }
                }
                compat.children.setAll(results.map { (name, r) ->
                    val issues = r.getOrNull()
                    // UI-only gaps (settings screens, dialogs) don't affect browsing or playback.
                    val relevant = issues?.filterNot { i -> listOf("Fragment", "Dialog", "View", "Adapter", "Layout", "Widget", "R$", "Preference").any { i.usedIn.contains(it) } }
                    vbox(3.0,
                        hbox(8.0, label(name, "bold"), spacer(), label(when {
                            issues == null -> "Couldn't check"
                            relevant!!.isEmpty() -> "Compatible"
                            else -> "${relevant.size} issue(s)"
                        }, if (relevant?.isEmpty() == true) "good-text" else "error-text", "small")),
                        *relevant.orEmpty().take(6).map { label(it.toString(), "muted", "small").apply { isWrapText = true } }.toTypedArray(),
                    ).apply { styleClass += "card-row" }
                })
            }
        }

        val log = TextArea(Logs.tail().joinToString("\n")).apply {
            styleClass += "log-view"
            isEditable = false
            // Wrapped, so jumping to the newest line never scrolls the view sideways.
            isWrapText = true
            prefRowCount = 18
            javafx.application.Platform.runLater { positionCaret(text.length) }
        }
        return listOf(
            section("Provider testing",
                row("Test extensions", "Runs home → search → details → sources against each provider and shows exactly where it fails.",
                    button("Test all", Icons.BUG, "btn-accent", "btn-small") {
                        tests.children.forEach { r -> ((r as VBox).children[0] as HBox).children.last().let { (it as javafx.scene.control.Button).fire() } }
                    }),
                tests,
            ),
            section("Compatibility",
                row("Linux compatibility check", "Looks for Android APIs an extension uses that don't exist on Linux, without running it.", compatButton),
                compat,
            ),
            section("Logs",
                row("Application log", Logs.file.absolutePath, hbox(8.0,
                    button("Refresh", Icons.REFRESH, "btn-ghost", "btn-small") { log.text = Logs.tail().joinToString("\n"); log.positionCaret(log.text.length) },
                    button("Copy", Icons.COPY, "btn-ghost", "btn-small") {
                        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(log.text) })
                        Toasts.show("Log copied")
                    },
                    button("Open folder", Icons.FOLDER, "btn-ghost", "btn-small") { openFolder(Logs.file.parentFile) },
                )),
                log.pad(0.0, 0.0, 12.0, 0.0),
            ),
        )
    }

    private fun storage(): List<Node> {
        val rows = StorageInfo.entries().map { e ->
            val size = label("…", "stat-value")
            ui.launch { size.text = withContext(Dispatchers.IO) { StorageInfo.human(StorageInfo.size(e.dir)) } }
            row(e.label, e.description, hbox(10.0, size,
                button("Open", Icons.FOLDER, "btn-ghost", "btn-small") { openFolder(e.dir) },
                if (e.clearable) button("Clear", Icons.DELETE, "btn-danger", "btn-small") {
                    ui.launch {
                        withContext(Dispatchers.IO) { StorageInfo.clear(e.dir) }
                        if (e.dir.name == "images") Images.clearDisk()
                        Toasts.show("${e.label} cleared")
                        open("storage")
                    }
                } else Region(),
            ))
        }
        return listOf(
            section("Space used", *rows.toTypedArray()),
            section("Folders",
                row("App data", AppDirs.data.absolutePath, button("Open", Icons.FOLDER, "btn-ghost", "btn-small") { openFolder(AppDirs.data) }),
            ),
        )
    }

    private fun about(): List<Node> = listOf(
        section("CloudStream for Linux",
            row("Version ${AppInfo.VERSION}", "Runs CloudStream extensions from the same repositories as the Android app. Free software under GPL-3.0.",
                label("v${AppInfo.VERSION}", "tag", "accent")),
            row("Made by", AppInfo.CREDIT, label("", "muted")),
            row("Built on", "The upstream CloudStream library, libVLC through vlcj (player), JavaFX. Online subtitles: OpenSubtitles via the Stremio addon protocol.", label("", "muted")),
        ),
        section("Keyboard",
            row("Anywhere", "/ or Ctrl+F search · Esc back · Alt+← back · F11 fullscreen", label("", "muted")),
            row("In the player", "Space/K pause · ←/→ seek 10 s (Shift: 1 min) · ↑/↓ volume · M mute · F full screen · S sources · C subtitles · A audio language · Z zoom · [ ] speed · G/H subtitle delay · 0–9 jump · N next episode · Esc back", label("", "muted")),
        ),
    )
}
