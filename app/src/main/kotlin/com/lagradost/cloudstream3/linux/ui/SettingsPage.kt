package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.AppDirs
import com.lagradost.cloudstream3.linux.CompatChecker
import com.lagradost.cloudstream3.linux.DnsProvider
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.LiveTv
import com.lagradost.cloudstream3.linux.Metadata
import com.lagradost.cloudstream3.linux.StremioApi
import com.lagradost.cloudstream3.linux.Logs
import com.lagradost.cloudstream3.linux.Network
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.Profiles
import com.lagradost.cloudstream3.linux.ProviderTest
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.SiteOverrides
import com.lagradost.cloudstream3.linux.Updates
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
        Category("profiles", "Profiles", Icons.PEOPLE, ::profiles),
        Category("appearance", "Appearance", Icons.PALETTE, ::appearance),
        Category("playback", "Playback", Icons.PLAY_CIRCLE, ::playback),
        Category("livetv", "Live TV sources", Icons.TV, ::liveTv),
        Category("metadata", "Metadata & integrations", Icons.SPARK, ::metadata),
        Category("network", "Network & DNS", Icons.NETWORK, ::network),
        Category("extensions", "Extensions", Icons.EXTENSION, ::extensions),
        Category("developer", "Developer & logs", Icons.CODE, ::developer),
        Category("storage", "Storage", Icons.STORAGE, ::storage),
        Category("shortcuts", "Keyboard shortcuts", Icons.KEYBOARD, ::shortcuts),
        Category("about", "About", Icons.INFO, ::about),
    )

    /** Sub-pages opened from a category (not in the list on the left). */
    private val subPages = listOf(Category("tmdb", "TMDB", Icons.SPARK, ::tmdbPage), Category("anilist", "AniList", Icons.SPARK, ::anilistPage))
    private val parentOf = mapOf("tmdb" to "metadata", "anilist" to "metadata")

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
                if (c.id == current || c.id == parentOf[current]) styleClass += "active"
                onClick { search.clear(); open(c.id) }
            }
        })
    }

    fun open(id: String) {
        current = id
        renderNav()
        val c = (categories + subPages).first { it.id == id }
        val parent = parentOf[id]?.let { p -> categories.first { it.id == p } }
        val title: Node = if (parent == null) label(c.title, "h2") else hbox(12.0,
            button("Back", Icons.BACK, "btn-ghost", "btn-small") { open(parent.id) },
            vbox(0.0, label(parent.title, "faint", "small"), label(c.title, "h2")))
        body.children.setAll(listOf(title) + c.build())
    }

    private fun buildIndex() {
        index.clear()
        indexing = true
        (categories + subPages).forEach { c -> indexCategory = c.id; c.build() }
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
                    label((categories + subPages).first { it.id == cat }.title, "tag"),
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

    private fun profiles(): List<Node> {
        val list = VBox(4.0).apply {
            children.setAll(Profiles.all().map { p ->
                hbox(14.0, Avatars.node(p.avatar, 40.0, round = true), vbox(2.0, label(p.name, "bold"),
                    label(listOfNotNull(if (p.kids) "Kids profile · ${p.filter.extensions.size} extensions allowed" else "Profile",
                        if (p.pin != null) "PIN" else null, if (p.id == Profiles.current.id) "you are here" else null).joinToString(" · "), "faint", "small")),
                ).apply { styleClass += "card-row" }
            })
        }
        return listOf(
            section("Profiles", list, row("Manage profiles", "Add, edit or delete profiles; set avatars, PINs and kids profiles.",
                button("Manage profiles", Icons.PEOPLE, "btn-ghost") { Nav.window.showOverlay(ProfilePicker(startup = false, manage = true)) })),
            section("Adult content",
                row("Show adult (NSFW) content", "Extensions and titles marked NSFW, and adult Live TV groups. Off by default, like the Android app. " +
                    "Only for this profile; kids profiles never see it.", Switch(Settings.showNsfw) { on -> setNsfw(on) })),
            section("Kids",
                row("Parent PIN", if (Profiles.hasParentPin) "Needed to leave a kids profile. Change it here (you'll need the current one)."
                    else "Not set yet. It's created when you make a kids profile, or set one now.",
                    button(if (Profiles.hasParentPin) "Change PIN" else "Set PIN", Icons.LOCK, "btn-ghost", "btn-small") { changeParentPin() })),
            section("Startup",
                row("Ask who's watching", "With more than one profile, show the profile picker when the app starts. It always shows when the last profile has a PIN.",
                    Switch(Profiles.askOnStart) { Profiles.askOnStart = it })),
            section("What belongs to a profile", label(
                "Each profile has its own history, continue watching, list, home layout, theme and player preferences (subtitles, audio, volume). " +
                    "Extensions, Stremio add-ons, network and storage settings are shared by everyone.", "muted").apply { isWrapText = true; pad(10.0, 0.0) }),
        )
    }

    /** Turning adult content on needs a confirmation (and the parent PIN, if one is set); off is immediate. */
    private fun setNsfw(on: Boolean) {
        val done = { value: Boolean ->
            Settings.showNsfw = value
            PluginManager.bumpVersion()
            open("profiles")
        }
        if (!on) return done(false)
        open("profiles") // shows it off until confirmed
        Nav.window.showOverlay(ConfirmSheet("Show adult content?",
            "Adult (18+) extensions and titles will show on Home, in Search and in Extensions for this profile. Only turn this on if you're an adult.",
            "I'm 18+, show it") {
            if (Profiles.hasParentPin) javafx.application.Platform.runLater {
                Nav.window.showOverlay(PinPrompt("Enter the parent PIN") { p -> Profiles.checkParentPin(p).also { if (it) javafx.application.Platform.runLater { done(true) } } })
            } else done(true)
        })
    }

    /** Current PIN (if any) → new PIN → repeat, each in the PIN pad. */
    private fun changeParentPin() {
        fun askNew() = Nav.window.showOverlay(PinPrompt("New parent PIN") { first ->
            javafx.application.Platform.runLater {
                Nav.window.showOverlay(PinPrompt("Repeat the new PIN") { again ->
                    (again == first).also { ok -> if (ok) { Profiles.setParentPin(first); Toasts.show("Parent PIN saved"); open("profiles") } }
                })
            }
            true
        })
        if (Profiles.hasParentPin) Nav.window.showOverlay(PinPrompt("Current parent PIN") { p ->
            Profiles.checkParentPin(p).also { if (it) javafx.application.Platform.runLater { askNew() } }
        }) else askNew()
    }

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
                row("Auto-play next episode", "Near the end (or at the credits, for anime) a 10-second countdown starts the next episode; Cancel stops it.",
                    Switch(Settings.autoPlayNext) { Settings.autoPlayNext = it }),
                row("Skip intro buttons (anime)", "Shows “Skip intro”, “Skip recap” and “Skip credits” while they play. Times come from AniSkip, a free community database.",
                    Switch(Settings.skipButtons) { Settings.skipButtons = it }),
                row("Skip intros automatically (anime)", "Jumps over intros and recaps without asking.",
                    Switch(Settings.autoSkip) { Settings.autoSkip = it }),
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
        siteAddresses(),
    )

    /** Point an extension at a new domain when its site moves (until the extension is updated). */
    private fun siteAddresses(): Node {
        val apis = Engine.allProviders().filter { it.mainUrl.startsWith("http") && it !is StremioApi }
        val rows = apis.map { api ->
            val original = SiteOverrides.original(api)
            val field = TextField(SiteOverrides.get(api) ?: "").apply { promptText = original; prefWidth = 300.0 }
            val save = {
                SiteOverrides.set(api, field.text.takeIf { it.isNotBlank() })
                Toasts.show(if (field.text.isBlank()) "${api.name} uses its own address again" else "${api.name} now uses ${api.mainUrl}")
            }
            field.setOnAction { save() }
            row(api.name, "Default: $original", hbox(8.0, field,
                button("Save", null, "btn-ghost", "btn-small") { save() },
                button(null, Icons.REFRESH, "btn-ghost", "btn-small") { field.clear(); save() }.tooltip("Use the default address"),
            ))
        }
        return section("Site addresses",
            row("When a site moves", "Streaming sites often change domain before their extension is updated. Enter the new address (e.g. https://newsite.example) to keep using the extension.", Region()),
            *rows.ifEmpty { listOf(label("No extensions installed.", "muted")) }.toTypedArray(),
        )
    }

    /**
     * Deletes the extension (or Stremio add-on) that provides [api]. An extension can provide several
     * providers; they all go, so the confirmation names them. [done] runs after it's removed.
     */
    private fun deleteProvider(api: com.lagradost.cloudstream3.MainAPI, done: (List<com.lagradost.cloudstream3.MainAPI>) -> Unit) {
        if (api is com.lagradost.cloudstream3.linux.StremioApi) {
            val addon = com.lagradost.cloudstream3.linux.StremioAddons.all().firstOrNull { it.base == api.mainUrl } ?: return
            Nav.window.showOverlay(ConfirmSheet("Remove ${addon.manifest.name}?", "This Stremio add-on is removed from Home, Search and Live TV.", "Remove") {
                com.lagradost.cloudstream3.linux.StremioAddons.remove(addon.url)
                Toasts.show("${addon.manifest.name} removed")
                done(listOf(api))
            })
            return
        }
        val plugin = PluginManager.loaded.values.firstOrNull { p -> p.providers.any { it === api } }
            ?: return Toasts.show("Couldn't find the extension file for ${api.name}")
        val providers = plugin.providers
        val name = plugin.data.name ?: plugin.data.internalName
        val also = providers.filter { it !== api }.map { it.name }.distinct()
        val message = if (also.isEmpty()) "The extension is uninstalled. You can install it again from Extensions."
            else "This extension also provides ${also.joinToString()}; those are removed too. You can install it again from Extensions."
        Nav.window.showOverlay(ConfirmSheet("Delete $name?", message, "Delete") {
            PluginManager.uninstall(plugin.data.internalName)
            Toasts.show("$name deleted")
            done(providers)
        })
    }

    private fun developer(): List<Node> {
        val tests = VBox(8.0)
        val rowsByApi = mutableMapOf<com.lagradost.cloudstream3.MainAPI, Node>()
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
            val deleteButton = button(null, Icons.DELETE, "btn-danger", "btn-small") {
                deleteProvider(api) { gone -> gone.forEach { g -> rowsByApi.remove(g)?.let { tests.children.remove(it) } } }
            }.tooltip("Delete this extension")
            return vbox(6.0, hbox(12.0, label(api.name, "bold"), label(api.mainUrl, "faint", "small"), spacer(), state, runButton, deleteButton), steps)
                .apply { styleClass += "card-row" }
                .also { rowsByApi[api] = it }
        }
        Engine.allProviders().forEach { tests.children += testRow(it) }
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
                row("Downloads", com.lagradost.cloudstream3.linux.Downloads.folder().absolutePath + "\nMovies and episodes you download for offline viewing.", hbox(8.0,
                    button("Change…", Icons.EDIT, "btn-ghost", "btn-small") {
                        val dir = javafx.stage.DirectoryChooser().apply {
                            title = "Folder for downloads"
                            com.lagradost.cloudstream3.linux.Downloads.folder().let { f -> (if (f.isDirectory) f else f.parentFile)?.takeIf { it.isDirectory }?.let { initialDirectory = it } }
                        }.showDialog(scene?.window) ?: return@button
                        Settings.downloadFolder = dir.absolutePath
                        open("storage")
                    },
                    button("Open", Icons.FOLDER, "btn-ghost", "btn-small") { openFolder(com.lagradost.cloudstream3.linux.Downloads.folder()) },
                    if (Settings.downloadFolder.isNotBlank()) button("Default", Icons.REFRESH, "btn-ghost", "btn-small") { Settings.downloadFolder = ""; open("storage") } else Region(),
                )),
            ),
            section("Reset",
                row("Reset settings", "App settings and this profile's theme, player and home settings go back to defaults. Extensions, repositories, profiles, library and history are kept.",
                    button("Reset settings", Icons.REFRESH, "btn-ghost", "btn-small") {
                        if (!confirm("Reset all settings to their defaults?")) return@button
                        listOf("linux_settings", Profiles.file("home_catalogs"), Profiles.file("profile_settings"), "site_overrides").forEach {
                            com.lagradost.cloudstream3.linux.Platform.context.getSharedPreferences(it, 0).edit().clear().apply()
                        }
                        Theme.mode = Theme.mode // re-applies the (default) theme
                        Toasts.show("Settings reset. Restart the app to finish.")
                    }),
                row("Erase everything", "Deletes all extensions, repositories, library, history, settings and caches, then closes the app. This can't be undone.",
                    button("Erase all data", Icons.DELETE, "btn-danger", "btn-small") {
                        if (!confirm("Erase ALL CloudStream data (extensions, library, history, settings)? This can't be undone.")) return@button
                        AppDirs.data.listFiles()?.forEach { it.deleteRecursively() }
                        kotlin.system.exitProcess(0)
                    }),
            ),
        )
    }

    private fun confirm(text: String): Boolean =
        javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION, text,
            javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL).apply {
            headerText = null
            dialogPane.stylesheets += SettingsPage::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        }.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK

    // ---------- Metadata & integrations ----------

    private fun hubCard(logo: Node, color: String, title: String, text: String, tags: List<Node>, right: List<Node>): Node {
        val tile = StackPane(logo).apply { styleClass += "hub-logo"; style = "-fx-background-color: $color;" }
        roundedClip(tile, 13.0)
        val info = vbox(5.0, label(title, "bold").apply { style = "-fx-font-size: 15px;" },
            label(text, "muted").apply { isWrapText = true; maxWidth = 640.0 },
            hbox(6.0, *tags.toTypedArray()).pad(4.0, 0.0, 0.0, 0.0)).apply { HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 }
        val side = vbox(12.0, *right.toTypedArray()).apply { alignment = javafx.geometry.Pos.TOP_RIGHT; minWidth = javafx.scene.layout.Region.USE_PREF_SIZE }
        if (indexing) index += Triple(indexCategory, title, text)
        return hbox(16.0, tile, info, side).apply { styleClass += "hub-card"; alignment = javafx.geometry.Pos.TOP_LEFT }
    }

    private fun metadata(): List<Node> {
        val tmdbStatus = when {
            Metadata.tmdbKey.isBlank() -> label("Add your free API key", "status-warn")
            else -> label("✓ Your API key", "status-ok")
        }
        return listOf(
            label("Extensions often give only a title and a poster. These services fill in ratings, artwork and cast for every extension " +
                "and Stremio add-on, matched by title, year and IMDb ID. Settings and keys stay on this computer.", "muted").apply { isWrapText = true; maxWidth = 860.0 },
            hubCard(label("TMDB"), "linear-gradient(to bottom right, #0d253f, #01b4e4)", "The Movie Database (TMDB)",
                "Ratings, wide backdrops, title logos, cast with photos, trailers and similar titles for films and series from every extension.",
                listOf(tmdbStatus, label(Metadata.language, "tag")) + if (Metadata.posterRatings) listOf(label("Ratings on posters", "tag")) else emptyList(),
                listOf(Switch(Metadata.tmdbEnabled) { Metadata.tmdbEnabled = it },
                    button("Configure TMDB", Icons.CHEVRON_RIGHT, "btn-accent", "btn-small") { open("tmdb") }.apply { contentDisplay = javafx.scene.control.ContentDisplay.RIGHT; id = "configure-tmdb" })),
            hubCard(label("AL"), "linear-gradient(to bottom right, #02a9ff, #2b2d42)", "AniList (anime)",
                "Anime scores and banners for anime titles. No account or key needed.",
                listOf(label("✓ Ready · no key needed", "status-ok")),
                listOf(Switch(Metadata.anilist) { Metadata.anilist = it })),
            hubCard(label("IMDb"), "linear-gradient(to bottom right, #7c3aed, #c026d3)", "Cinemeta (IMDb ratings)",
                "Stremio's free catalog: IMDb ratings and IDs, also when TMDB has no match or no key is set. No key needed.",
                listOf(label("✓ Ready", "status-ok")),
                listOf(Switch(Metadata.cinemeta) { Metadata.cinemeta = it })),
            hubCard(icon(Icons.SUBTITLES, 26.0).apply { style += "-fx-background-color: white;" }, "linear-gradient(to bottom right, #16a34a, #065f46)", "OpenSubtitles",
                "Online subtitles in the player (Subtitles → Search online). No account needed.",
                listOf(label("✓ Ready", "status-ok")), emptyList()),
            hbox(10.0, label("TMDB", "tmdb-badge"), label("This product uses the TMDB API but is not endorsed or certified by TMDB.", "faint", "small")),
        )
    }

    private fun tmdbPage(): List<Node> {
        val key = javafx.scene.control.PasswordField().apply {
            text = Metadata.tmdbKey
            promptText = "TMDB API key (v3) or read access token"
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val status = label(if (Metadata.tmdbKey.isBlank()) "" else "Saved", "muted")
        val test = button("Test & save", Icons.CHECK, "btn-accent", "btn-small") {
            status.text = "Checking…"; status.styleClass.setAll("label", "muted")
            ui.launch {
                val problem = Metadata.testKey(key.text)
                if (problem == null) {
                    Metadata.tmdbKey = key.text
                    status.text = "✓ Key works and is saved"; status.styleClass.setAll("label", "status-ok")
                } else {
                    status.text = problem; status.styleClass.setAll("label", "error-text")
                }
            }
        }
        val help = hbox(6.0, label("Free: make an account on themoviedb.org → Settings → API → request a key (Developer, personal use), then paste the \"API Key\" or the \"API Read Access Token\".", "muted", "small").apply { isWrapText = true; maxWidth = 640.0 },
            label("Open themoviedb.org", "link-button").onClick { openUrl("https://www.themoviedb.org/settings/api") })
        val (count, bytes) = Metadata.cacheStats()
        val languages = listOf("en-US" to "English (US)", "en-GB" to "English (UK)", "ar-SA" to "Arabic", "hi-IN" to "Hindi", "ur-PK" to "Urdu", "es-ES" to "Spanish",
            "fr-FR" to "French", "de-DE" to "German", "it-IT" to "Italian", "pt-BR" to "Portuguese (Brazil)", "tr-TR" to "Turkish", "ja-JP" to "Japanese", "ko-KR" to "Korean")
        return listOf(
            section("TMDB", row("Use TMDB", "Turn TMDB on or off without removing your key.", Switch(Metadata.tmdbEnabled) { Metadata.tmdbEnabled = it })),
            vbox(10.0, label("API KEY", "section-label"), VBox(10.0, hbox(10.0, icon(Icons.KEY, 18.0).apply { styleClass += "faint-icon" }, key, test), status, help).apply { styleClass += "card"; pad(16.0, 18.0) }),
            section("What to use TMDB for",
                row("Ratings on posters", "A small TMDB score on cards in Home, Search and View all. Looked up as posters appear, then cached.", Switch(Metadata.posterRatings) { Metadata.posterRatings = it }),
                row("Banner and title page artwork", "Wide backdrops and title logos when the extension only has a poster.", Switch(Metadata.artwork) { Metadata.artwork = it }),
                row("Cast with photos, trailers and similar titles", "Shown on the title page.", Switch(Metadata.extras) { Metadata.extras = it }),
                row("Replace the extension's plot", "Use TMDB's description even when the extension has its own.", Switch(Metadata.replaceExtension) { Metadata.replaceExtension = it })),
            section("Language & cache",
                row("Language", "For plots, titles and logos.", combo(languages, languages.firstOrNull { it.first == Metadata.language } ?: languages[0], { it.second }) { Metadata.language = it.first }),
                row("Cache", "$count lookups saved · ${bytes / 1024} KB. Kept for a week.", button("Clear cache", Icons.DELETE, "btn-ghost", "btn-small") { Metadata.clearCache(); open("tmdb") })),
            hbox(10.0, label("TMDB", "tmdb-badge"), label("This product uses the TMDB API but is not endorsed or certified by TMDB.", "faint", "small")),
        )
    }

    private fun anilistPage(): List<Node> = listOf(
        section("AniList", row("Use AniList for anime", "Scores and banners for titles extensions mark as anime.", Switch(Metadata.anilist) { Metadata.anilist = it })),
    )

    // ---------- Live TV sources ----------

    private fun liveTv(): List<Node> {
        val kinds = listOf(LiveTv.Kind.STREMIO to "Stremio / Nuvio add-on", LiveTv.Kind.M3U to "M3U playlist", LiveTv.Kind.XTREAM to "Xtream Codes login")
        var kind = LiveTv.Kind.M3U
        val url = TextField().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val epg = TextField().apply { promptText = "TV guide (XMLTV) address, optional, e.g. https://…/guide.xml.gz"; HBox.setHgrow(this, Priority.ALWAYS) }
        val user = TextField().apply { promptText = "Username"; HBox.setHgrow(this, Priority.ALWAYS) }
        val pass = javafx.scene.control.PasswordField().apply { promptText = "Password"; HBox.setHgrow(this, Priority.ALWAYS) }
        val name = TextField().apply { promptText = "Name (optional)"; prefWidth = 200.0 }
        val status = label("", "muted").apply { isWrapText = true }
        val extra = VBox(10.0)
        val row = hbox(10.0)
        lateinit var add: javafx.scene.control.Button
        fun runAdd(work: suspend () -> String) {
            status.text = "Checking…"; status.styleClass.setAll("label", "muted")
            ui.launch {
                try {
                    val message = withContext(Dispatchers.IO) { work() }
                    status.text = message
                    url.clear(); epg.clear(); user.clear(); pass.clear(); name.clear()
                    Toasts.show(message, seconds = 6.0)
                    open("livetv")
                } catch (t: Throwable) {
                    status.text = "Couldn't add it: ${t.message ?: t}"
                    status.styleClass.setAll("label", "error-text")
                }
            }
        }
        val chooseFile = button("Choose file…", Icons.FILE, "btn-ghost") {
            FileChooser().apply {
                title = "Choose an M3U playlist"
                extensionFilters += FileChooser.ExtensionFilter("Playlists", "*.m3u", "*.m3u8", "*.txt")
            }.showOpenDialog(scene.window)?.let { url.text = it.toURI().toString() }
        }.tooltip("A playlist file on this computer")
        fun layoutFields() {
            url.promptText = when (kind) {
                LiveTv.Kind.STREMIO -> "Add-on address: https://…/manifest.json or stremio://…"
                LiveTv.Kind.M3U -> "Playlist address (…/playlist.m3u), or choose a file"
                LiveTv.Kind.XTREAM -> "Server address, e.g. http://provider.example:8080"
            }
            row.children.setAll(when (kind) {
                LiveTv.Kind.M3U -> listOf(url, chooseFile, add)
                else -> listOf(url, add)
            })
            extra.children.setAll(when (kind) {
                LiveTv.Kind.STREMIO -> emptyList()
                LiveTv.Kind.M3U -> listOf(hbox(10.0, epg, name))
                LiveTv.Kind.XTREAM -> listOf(hbox(10.0, user, pass, name))
            })
        }
        add = button("Add source", Icons.ADD, "btn-accent") {
            val k = kind
            runAdd {
                val (source, count) = LiveTv.add(k, url.text, name.text, epg.text, user.text, pass.text)
                "Added ${source.name}: $count channels. They're in Live TV."
            }
        }
        val tabs = ChipGroup { i -> kind = kinds[i].first; layoutFields() }.apply { setItems(kinds.map { it.second }, 1) }
        layoutFields()
        val form = VBox(12.0, tabs, row, extra, status).apply { styleClass += "card"; pad(16.0, 18.0) }
        val sources = LiveTv.sources()
        val list = VBox().apply {
            styleClass += "card"; pad(4.0, 18.0)
            if (sources.isEmpty()) children += label("No sources yet.", "muted").apply { pad(12.0, 0.0) }
            sources.forEach { s ->
                val badge = label(s.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), "provider-badge")
                val info = vbox(3.0, hbox(8.0, label(s.name, "bold"), label(s.kind.label.uppercase(), "tag")),
                    label(when (s.kind) {
                        LiveTv.Kind.M3U -> (if (s.url.startsWith("file:")) "File: " + java.io.File(java.net.URI(s.url)).name else s.url) + (s.epgUrl?.let { " · guide: $it" } ?: "")
                        LiveTv.Kind.XTREAM -> "${s.url} · ${s.user}"
                        LiveTv.Kind.STREMIO -> "Stremio add-on · also listed under Extensions → Stremio add-ons"
                    }, "muted", "small").apply { maxWidth = 620.0 }).apply { HBox.setHgrow(this, Priority.ALWAYS); minWidth = 0.0 }
                val refresh = button(null, Icons.REFRESH, "btn-ghost", "btn-small") {
                    ui.launch { val n = LiveTv.channels(s.id, force = true).size; LiveTv.loadGuides(force = true); Toasts.show("${s.name}: $n channels") }
                }.tooltip("Download the channel list and guide again")
                val remove = button(null, Icons.DELETE, "btn-ghost", "btn-small") { LiveTv.remove(s.id); open("livetv") }.tooltip("Remove")
                children += hbox(14.0, badge, info, refresh, remove, Switch(s.enabled) { LiveTv.setEnabled(s.id, it) }).apply { styleClass += "card-row" }
            }
        }
        return listOf(
            label("Sources added here appear only in Live TV, never on Home or in Search. Works with Stremio and Nuvio TV add-ons, " +
                "M3U playlists with optional XMLTV TV guides (by address or as a file), and Xtream Codes logins from IPTV providers.", "muted").apply { isWrapText = true; maxWidth = 860.0 },
            vbox(10.0, label("ADD A SOURCE", "section-label"), form),
            vbox(10.0, label("YOUR LIVE TV SOURCES", "section-label"), list),
            section("Options",
                row("Refresh playlists every day", "Download channel lists again when they're more than a day old. Guides refresh every 12 hours.",
                    Switch(LiveTv.refreshDaily) { LiveTv.refreshDaily = it }),
                row("Allow Live TV in kids profiles", "Kids then see only channels in children's groups (Kids, Cartoons, Animation, Family…).",
                    Switch(LiveTv.allowKids) { LiveTv.allowKids = it })),
        )
    }

    private fun about(): List<Node> = listOf(
        section("CloudStream for Linux",
            row("Version ${AppInfo.VERSION}", "Runs CloudStream extensions from the same repositories as the Android app. Free software under GPL-3.0.",
                label("v${AppInfo.VERSION}", "tag", "accent")),
            row("Made by", AppInfo.CREDIT, label("", "muted")),
            row("Built on", "The upstream CloudStream library, libVLC through vlcj (player), JavaFX. Online subtitles: OpenSubtitles via the Stremio addon protocol.", label("", "muted")),
        ),
        section("Updates",
            row("Check for updates", "Looks for a newer release on GitHub.", updateButton()),
            row("Check automatically", "Once a day when the app starts.", Switch(Settings.checkUpdates) { Settings.checkUpdates = it }),
            row("Release notes", Updates.RELEASES, button("Open", Icons.FILE, "btn-ghost", "btn-small") { openUrl(Updates.RELEASES) }),
        ),
    )

    private fun updateButton(): Node {
        val status = label("", "muted", "small")
        val check = button("Check now", Icons.REFRESH, "btn-ghost", "btn-small") {}
        check.setOnAction {
            check.isDisable = true
            status.text = "Checking…"
            ui.launch {
                val result = runCatching { Updates.check(AppInfo.VERSION) }
                check.isDisable = false
                result.fold({ r ->
                    if (r == null) status.text = "You have the latest version (${AppInfo.VERSION})"
                    else {
                        status.text = "Version ${r.version} is available"
                        openUrl(r.url)
                    }
                }, { status.text = "Couldn't check: ${it.message ?: it}" })
            }
        }
        return hbox(10.0, status, check)
    }

    /** Every shortcut in one searchable list. */
    private fun shortcuts(): List<Node> {
        val all = listOf(
            Triple("Anywhere", "/  or  Ctrl+F", "Search"),
            Triple("Anywhere", "Esc  or  Alt+←", "Back / close"),
            Triple("Anywhere", "F11", "Full screen"),
            Triple("Player", "Space  or  K", "Play / pause"),
            Triple("Player", "←  /  →  (J / L)", "Seek 10 seconds"),
            Triple("Player", "Shift + ←  /  →", "Seek 1 minute"),
            Triple("Player", "↑  /  ↓  or mouse wheel", "Volume"),
            Triple("Player", "M", "Mute"),
            Triple("Player", "F  or double-click", "Full screen"),
            Triple("Player", "S", "Sources"),
            Triple("Player", "C", "Subtitles (and online search)"),
            Triple("Player", "A", "Audio language and equalizer"),
            Triple("Player", "Z", "Cycle zoom (fit, fill, stretch, …)"),
            Triple("Player", "[  /  ]", "Slower / faster"),
            Triple("Player", "G  /  H", "Subtitles earlier / later"),
            Triple("Player", "0 – 9", "Jump to 0 % – 90 %"),
            Triple("Player", "Home", "Back to the start"),
            Triple("Player", "Shift + S", "Screenshot to Pictures/CloudStream"),
            Triple("Player", "N", "Next episode"),
        )
        val list = VBox()
        fun render(q: String) {
            val shown = all.filter { q.isBlank() || it.second.contains(q, true) || it.third.contains(q, true) || it.first.contains(q, true) }
            list.children.setAll(shown.map { (scope, keys, action) ->
                row(action, scope, label(keys, "kbd"))
            }.ifEmpty { listOf(label("No shortcut matches “$q”.", "muted")) })
        }
        render("")
        val search = TextField().apply { promptText = "Filter shortcuts (e.g. volume, subtitles, seek)"; styleClass += "search-field" }
        search.textProperty().addListener { _, _, v -> render(v.trim()) }
        return listOf(search, vbox(10.0, label("SHORTCUTS", "section-label"), list.apply { styleClass += "card"; pad(4.0, 18.0) }))
    }
}
