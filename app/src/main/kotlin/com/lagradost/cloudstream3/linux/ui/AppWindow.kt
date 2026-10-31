package com.lagradost.cloudstream3.linux.ui

import android.widget.Toast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.LiveTv
import com.lagradost.cloudstream3.linux.Profiles
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.Updates
import javafx.animation.FadeTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ProgressIndicator
import javafx.scene.effect.GaussianBlur
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

object AppInfo {
    const val NAME = "CloudStream for Linux"
    const val VERSION = "7.0.0"
    const val CREDIT = "Tennant Creek"
}

class DesktopApp : Application() {
    override fun start(stage: Stage) {
        // Inter (SIL OFL) for the same look on every distro; missing glyphs fall back to system fonts.
        listOf("Inter-Regular", "Inter-SemiBold", "Inter-Bold", "InterDisplay-ExtraBold").forEach { f ->
            DesktopApp::class.java.getResourceAsStream("/ui/fonts/$f.otf")?.use { javafx.scene.text.Font.loadFont(it, 13.0) }
        }
        com.lagradost.cloudstream3.linux.Settings.migrateToProfiles()
        val window = AppWindow(stage)
        val scene = Scene(window, 1366.0, 840.0, Color.web(Theme.background))
        scene.stylesheets += DesktopApp::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        stage.title = "CloudStream"
        // The window icon is the logo in the current theme colour.
        stage.icons.setAll(Logo.image(256.0))
        Theme.onChange { stage.icons.setAll(Logo.image(256.0)) }
        stage.minWidth = 980.0
        stage.minHeight = 640.0
        stage.scene = scene
        stage.fullScreenExitHint = ""
        stage.setOnCloseRequest {
            Playback.active?.quit()
            Platform.exit()
            exitProcess(0)
        }
        stage.show()
        val startMs = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
        System.err.println("I/app: window shown ${System.currentTimeMillis() - startMs} ms after launch")
        window.startup(repoLink)
    }

    companion object {
        private var repoLink: String? = null

        fun launch(link: String?) {
            repoLink = link
            // JavaFX warns when loaded from the classpath; harmless for an application.
            Logger.getLogger("com.sun.javafx.application.PlatformImpl").level = Level.SEVERE
            Application.launch(DesktopApp::class.java)
        }
    }
}

enum class Tab(val label: String, val icon: String, val forKids: Boolean = true) {
    HOME("Home", Icons.HOME),
    LIVE("Live TV", Icons.TV),
    SEARCH("Search", Icons.SEARCH),
    LIBRARY("My list", Icons.LIBRARY),
    EXTENSIONS("Extensions & add-ons", Icons.EXTENSION, forKids = false),
    SETTINGS("Settings", Icons.SETTINGS, forKids = false),
}

/** A page that puts its own controls in the top bar (e.g. Home's source picker). */
interface TopBarActions {
    fun topBarActions(): List<Node>

    /** True when the page has its own search box (the top bar's Search button is hidden). */
    val hasOwnSearch: Boolean get() = false
}

/**
 * Root layout, back to front: wallpaper, icon rail + pages under a floating top bar, status
 * pill, overlays (sheets, loading screen), toasts, the player surface, and the startup splash.
 */
class AppWindow(val stage: Stage) : StackPane() {
    private val wallpaper = StackPane().apply { isMouseTransparent = true }
    private val content = StackPane()
    private val topBar = TopBar(this)
    private val main = StackPane(content, topBar).apply { minWidth = 0.0 }
    private val layout = BorderPane(main)
    private val overlay = StackPane().apply { isPickOnBounds = false }
    private val playerLayer = StackPane().apply { isPickOnBounds = false }
    private val navItems = mutableMapOf<Tab, Node>()
    private val pages = mutableMapOf<Tab, Node>()
    private val backStack = ArrayDeque<Node>()
    private var splash: Node? = null
    var currentTab = Tab.HOME
        private set
    private val statusPill = hbox(8.0).apply {
        styleClass += "status-pill"
        isVisible = false
        isMouseTransparent = true
        maxWidth = Region.USE_PREF_SIZE
        maxHeight = Region.USE_PREF_SIZE
    }

    private val rail = VBox(6.0).apply { styleClass += "rail"; alignment = Pos.TOP_CENTER }
    private val railAvatar = StackPane().apply { styleClass += "rail-avatar" }

    init {
        Nav.window = this
        StackPane.setAlignment(topBar, Pos.TOP_CENTER)
        layout.left = dock()
        val toastHost = VBox(8.0).apply {
            alignment = Pos.BOTTOM_CENTER
            isPickOnBounds = false
            pad(0.0, 0.0, 28.0, 0.0)
        }
        Toasts.host = toastHost
        StackPane.setAlignment(statusPill, Pos.TOP_RIGHT)
        StackPane.setMargin(statusPill, Insets(16.0, 22.0, 0.0, 0.0))
        splash = SplashScreen()
        children.addAll(wallpaper, layout, statusPill, overlay, toastHost, playerLayer, splash)

        applyTheme()
        Theme.onChange { applyTheme() }

        addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            (playerLayer.children.firstOrNull() as? PlayerKeys)?.let { it.handle(e); return@addEventFilter }
            when {
                e.code == KeyCode.ESCAPE -> { if (closeOverlay() || back()) e.consume() }
                e.code == KeyCode.F11 -> { stage.isFullScreen = !stage.isFullScreen; e.consume() }
                e.code == KeyCode.LEFT && e.isAltDown -> { back(); e.consume() }
                (e.code == KeyCode.F && e.isControlDown) || (e.code == KeyCode.SLASH && e.target !is javafx.scene.control.TextInputControl) -> {
                    show(Tab.SEARCH); (pages[Tab.SEARCH] as? SearchPage)?.focusField(); e.consume()
                }
            }
        }
        show(Tab.HOME)
        Profiles.onChange { Platform.runLater { profileChanged() } }
    }

    /** Slim icon rail: logo, pages, then settings, fullscreen and the profile picture. */
    private fun dock(): Node {
        renderRail()
        return rail
    }

    private fun renderRail() {
        val logo = Logo(40.0).apply { styleClass += "rail-logo" }
        rail.children.setAll(StackPane(logo).apply { pad(2.0, 0.0, 18.0, 0.0); onClick { show(Tab.HOME) } })
        navItems.clear()
        val kids = Profiles.isKids
        Tab.entries.filter { (!kids || it.forKids) && (it != Tab.LIVE || LiveTv.availableToProfile) }.forEach { tab ->
            if (tab == Tab.SETTINGS) rail.children += spacer()
            val item = StackPane(icon(tab.icon, 22.0)).apply {
                styleClass += "rail-item"
                if (tab == currentTab) styleClass += "active"
                onClick { show(tab) }
                tooltip(tab.label)
            }
            navItems[tab] = item
            rail.children += item
        }
        if (kids) rail.children += spacer()
        rail.children += StackPane(icon(Icons.FULLSCREEN, 20.0)).apply {
            styleClass += "rail-item"
            onClick { stage.isFullScreen = !stage.isFullScreen }
            tooltip("Fullscreen (F11)")
        }
        railAvatar.children.setAll(Avatars.node(Profiles.current.avatar, 36.0, round = true))
        railAvatar.onClick { ProfileMenu.show(railAvatar) }
        railAvatar.tooltip(Profiles.current.name)
        rail.children += railAvatar
    }

    /** After switching profile: its own look, library and home; kids lose the grown-up pages. */
    private fun profileChanged() {
        backStack.forEach { (it as? Disposable)?.dispose() }
        backStack.clear()
        pages.values.forEach { (it as? Disposable)?.dispose() }
        pages.clear()
        Theme.changed()
        Vlc.optionsChanged(Playback.active != null)
        renderRail()
        topBar.render()
        PluginManager.bumpVersion()
        show(Tab.HOME)
    }

    /** The top bar sits over the page; it turns solid once the page scrolls under it. */
    fun setTopBarSolid(solid: Boolean) = topBar.setSolid(solid)

    private fun applyTheme() {
        style = Theme.rootStyle()
        scene?.fill = Color.web(Theme.background)
        styleClass.remove("glass")
        if (Theme.isGlass) styleClass += "glass"
        val path = Theme.wallpaper
        if (path == null) {
            wallpaper.children.setAll(listOfNotNull(
                if (Theme.isGlass) Region().apply { style = Theme.glassBackdropStyle(); isMouseTransparent = true } else null,
            ))
            return
        }
        val view = ImageView(Image(File(path).toURI().toString(), 1920.0, 1080.0, true, true, true)).apply {
            isPreserveRatio = false
            fitWidthProperty().bind(this@AppWindow.widthProperty())
            fitHeightProperty().bind(this@AppWindow.heightProperty())
            if (Theme.wallpaperBlur || Theme.isGlass) effect = GaussianBlur(if (Theme.isGlass) 40.0 else 28.0)
        }
        val dim = Region().apply { styleClass += "wallpaper-dim"; opacity = Theme.wallpaperDim }
        wallpaper.children.setAll(view, dim)
    }

    private fun page(tab: Tab): Node = pages.getOrPut(tab) {
        when (tab) {
            Tab.HOME -> HomePage()
            Tab.LIVE -> LiveTvPage()
            Tab.SEARCH -> SearchPage()
            Tab.LIBRARY -> LibraryPage()
            Tab.EXTENSIONS -> ExtensionsPage()
            Tab.SETTINGS -> SettingsPage()
        }
    }

    fun show(tab: Tab) {
        if (Profiles.isKids && !tab.forKids) return
        if (tab == Tab.LIVE && !LiveTv.availableToProfile) return
        backStack.forEach { (it as? Disposable)?.dispose() }
        backStack.clear()
        navItems[currentTab]?.styleClass?.remove("active")
        currentTab = tab
        navItems[tab]?.styleClass?.add("active")
        val node = page(tab)
        setContent(node)
        (node as? Refreshable)?.onShow()
    }

    /** Shows a page under the top bar; Home's banner runs underneath it, other pages start below. */
    private fun setContent(node: Node) {
        content.children.setAll(node)
        val fullBleed = node is HomePage || node is LiveTvPage
        content.padding = if (fullBleed) Insets.EMPTY else Insets(TopBar.HEIGHT, 0.0, 0.0, 0.0)
        topBar.setSolid(!fullBleed)
        topBar.pageChanged(node, currentTab)
    }

    /** Opens Search with [query] already running (e.g. a similar title from TMDB). */
    fun search(query: String) {
        show(Tab.SEARCH)
        (pages[Tab.SEARCH] as? SearchPage)?.run(query)
    }

    /** Opens a settings category directly. */
    fun openSettings(category: String) {
        show(Tab.SETTINGS)
        (pages[Tab.SETTINGS] as? SettingsPage)?.open(category)
    }

    /** Opens a sub-page (details, view-all) on top of the current tab. */
    fun push(node: Node) {
        backStack.addLast(node)
        setContent(node)
        node.opacity = 0.0
        animate(node.opacityProperty(), 1.0, 160.0)
    }

    fun back(): Boolean {
        val top = backStack.removeLastOrNull() ?: return false
        (top as? Disposable)?.dispose()
        val node = backStack.lastOrNull() ?: page(currentTab)
        setContent(node)
        (node as? Refreshable)?.onShow()
        return true
    }

    fun showOverlay(node: Node) {
        overlay.children.lastOrNull()?.let { (it as? Disposable)?.dispose() }
        overlay.children.setAll(node)
    }

    fun isTopOverlay(node: Node) = overlay.children.lastOrNull() === node

    /** Closes the sheet or menu on top; a [Blocking] one (startup profile picker) only with [force]. */
    fun closeOverlay(force: Boolean = false): Boolean {
        val top = overlay.children.lastOrNull() ?: return false
        if (!force && (top as? Blocking)?.blocking == true) return true
        (top as? Disposable)?.dispose()
        overlay.children.clear()
        return true
    }

    /** While the player fills the window, hide everything else so JavaFX does no extra work. */
    fun enterPlayer(surface: Node) {
        layout.isVisible = false
        overlay.isVisible = false
        wallpaper.isVisible = false
        playerLayer.children.setAll(surface)
        surface.requestFocus()
    }

    fun exitPlayer(surface: Node) {
        playerLayer.children.remove(surface)
        layout.isVisible = true
        overlay.isVisible = true
        wallpaper.isVisible = true
        val visible = backStack.lastOrNull() ?: pages[currentTab]
        (visible as? Refreshable)?.onShow()
    }

    fun startup(repoLink: String?) {
        Toast.showHandler = { msg -> Platform.runLater { Toasts.show(msg) } }
        ui.launch {
            PluginManager.progress.collectLatest { p ->
                (splash as? SplashScreen)?.status(if (p.total == 0) "Starting…" else "Loading extensions ${p.done}/${p.total}")
                statusPill.isVisible = p.running && splash == null
                statusPill.children.setAll(
                    ProgressIndicator().apply { setPrefSize(16.0, 16.0); setMaxSize(16.0, 16.0) },
                    label("Loading extensions ${p.done}/${p.total}", "small"),
                )
            }
        }
        ui.launch {
            val shownAt = System.currentTimeMillis()
            // The splash stays up while extensions load: at least briefly, at most a few seconds.
            val loading = launch {
                val failures = withContext(Engine.dispatcher) { PluginManager.loadAll() }
                if (failures.isNotEmpty()) {
                    Toasts.show("${failures.size} extension(s) failed to load", "Details" to { show(Tab.EXTENSIONS) }, 6.0)
                }
            }
            while (loading.isActive && System.currentTimeMillis() - shownAt < 6000) delay(50)
            delay((1100 - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0))
            // With a PIN on the last-used profile the picker always comes up, so a restart can't skip the PIN.
            if (Profiles.all().size > 1 && (Profiles.askOnStart || Profiles.current.pin != null) && System.getenv("CS_SCREENSHOT") == null) {
                showOverlay(ProfilePicker(startup = true))
            }
            hideSplash()
            loading.join()
            // Carry on with downloads that were running when the app closed.
            runCatching { com.lagradost.cloudstream3.linux.Downloads.init() }
            if (Settings.autoUpdateExtensions) {
                val updated = withContext(Engine.dispatcher) { runCatching { PluginManager.updateAll() }.getOrDefault(emptyList()) }
                if (updated.isNotEmpty()) Toasts.show("Updated ${updated.joinToString()}")
            }
            if (Settings.checkUpdates && System.currentTimeMillis() - Updates.lastCheck > 20 * 3600_000L &&
                System.getenv("CS_SCREENSHOT") == null) {
                Updates.lastCheck = System.currentTimeMillis()
                runCatching { Updates.check(AppInfo.VERSION) }.getOrNull()?.let { r ->
                    Toasts.show("CloudStream ${r.version} is available", "Download" to { openUrl(r.url) }, 15.0)
                }
            }
            Screenshots.runIfRequested(this@AppWindow)
        }
        repoLink?.let {
            show(Tab.EXTENSIONS)
            (pages[Tab.EXTENSIONS] as? ExtensionsPage)?.promptAddRepository(it)
        }
    }

    private fun hideSplash() {
        val node = splash ?: return
        splash = null
        statusPill.isVisible = PluginManager.progress.value.running
        FadeTransition(Duration.millis(320.0), node).apply {
            toValue = 0.0
            setOnFinished { children.remove(node) }
        }.play()
    }
}

/** Startup screen shown while extensions load. */
class SplashScreen : StackPane() {
    private val statusLabel = label("Starting…", "splash-status")

    init {
        styleClass += "splash"
        val logo = Logo(112.0).apply {
            effect = javafx.scene.effect.DropShadow(40.0, Color.web(Theme.accent, 0.45))
        }
        val center = vbox(14.0,
            logo,
            label("CloudStream", "splash-title"),
            label("FOR LINUX", "splash-sub"),
            Region().apply { minHeight = 14.0 },
            ProgressIndicator().apply { setPrefSize(30.0, 30.0); setMaxSize(30.0, 30.0) },
            statusLabel,
        ).apply { alignment = Pos.CENTER }
        val credit = hbox(6.0, label("Made by", "muted"), label(AppInfo.CREDIT, "bold")).apply {
            styleClass += "splash-credit"
            maxWidth = Region.USE_PREF_SIZE
            maxHeight = Region.USE_PREF_SIZE
        }
        children.addAll(center, credit)
        StackPane.setAlignment(credit, Pos.BOTTOM_CENTER)
        StackPane.setMargin(credit, Insets(0.0, 0.0, 34.0, 0.0))
    }

    fun status(text: String) {
        statusLabel.text = text
    }
}

/** Pages that refresh when navigated to. */
interface Refreshable {
    fun onShow()
}

/** Pages/overlays that cancel their work when closed. */
interface Disposable {
    fun dispose()
}

/** Global navigation entry points. */
object Nav {
    lateinit var window: AppWindow

    fun open(api: MainAPI, item: SearchResponse) =
        window.push(DetailsPage(api, item.url, item.name, item.posterUrl, item.posterHeaders))

    fun open(apiName: String, url: String, name: String, poster: String?) {
        val api = Engine.provider(apiName)
        if (api == null) {
            Toasts.show("The \"$apiName\" extension isn't loaded")
            return
        }
        window.push(DetailsPage(api, url, name, poster, null))
    }
}
