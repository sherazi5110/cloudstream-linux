package com.lagradost.cloudstream3.linux.ui

import android.widget.Toast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginManager
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
    const val VERSION = "5.0.0"
    const val CREDIT = "Tennant Creek"
}

class DesktopApp : Application() {
    override fun start(stage: Stage) {
        val window = AppWindow(stage)
        val scene = Scene(window, 1366.0, 840.0, Color.web(Theme.background))
        scene.stylesheets += DesktopApp::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        stage.title = "CloudStream"
        DesktopApp::class.java.getResourceAsStream("/ui/icon.png")?.use { stage.icons += Image(it) }
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

enum class Tab(val label: String, val icon: String) {
    HOME("Home", Icons.HOME),
    SEARCH("Search", Icons.SEARCH),
    LIBRARY("Library", Icons.LIBRARY),
    EXTENSIONS("Extensions", Icons.EXTENSION),
    SETTINGS("Settings", Icons.SETTINGS),
}

/**
 * Root layout, back to front: wallpaper, dock + pages, status pill, overlays (sheets,
 * loading screen), toasts, the player surface, and the startup splash.
 */
class AppWindow(val stage: Stage) : StackPane() {
    private val wallpaper = StackPane().apply { isMouseTransparent = true }
    private val content = StackPane()
    private val layout = BorderPane(content)
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

    init {
        Nav.window = this
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
    }

    /** Floating dock with labelled icons; fullscreen toggle at the bottom. */
    private fun dock(): Node {
        val logo = StackPane(ImageView(Image(AppWindow::class.java.getResourceAsStream("/ui/icon.png"), 48.0, 48.0, true, true))).apply {
            styleClass += "dock-logo"
            setMinSize(48.0, 48.0); setMaxSize(48.0, 48.0)
        }
        val dock = VBox(4.0, logo, Region().apply { minHeight = 10.0 }).apply {
            styleClass += "dock"
            alignment = Pos.TOP_CENTER
        }
        Tab.entries.forEach { tab ->
            if (tab == Tab.SETTINGS) dock.children += spacer()
            val item = VBox(icon(tab.icon, 22.0), label(tab.label, "dock-label")).apply {
                styleClass += "dock-item"
                onClick { show(tab) }
            }
            navItems[tab] = item
            dock.children += item
        }
        val fullscreen = VBox(icon(Icons.FULLSCREEN, 20.0)).apply {
            styleClass += "dock-item"
            onClick { stage.isFullScreen = !stage.isFullScreen }
            tooltip("Fullscreen (F11)")
        }
        dock.children += fullscreen
        return StackPane(dock).apply { pad(14.0, 0.0, 14.0, 14.0) }
    }

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
            Tab.SEARCH -> SearchPage()
            Tab.LIBRARY -> LibraryPage()
            Tab.EXTENSIONS -> ExtensionsPage()
            Tab.SETTINGS -> SettingsPage()
        }
    }

    fun show(tab: Tab) {
        backStack.forEach { (it as? Disposable)?.dispose() }
        backStack.clear()
        navItems[currentTab]?.styleClass?.remove("active")
        currentTab = tab
        navItems[tab]?.styleClass?.add("active")
        val node = page(tab)
        content.children.setAll(node)
        (node as? Refreshable)?.onShow()
    }

    /** Opens a settings category directly. */
    fun openSettings(category: String) {
        show(Tab.SETTINGS)
        (pages[Tab.SETTINGS] as? SettingsPage)?.open(category)
    }

    /** Opens a sub-page (details, view-all) on top of the current tab. */
    fun push(node: Node) {
        backStack.addLast(node)
        content.children.setAll(node)
        node.opacity = 0.0
        animate(node.opacityProperty(), 1.0, 160.0)
    }

    fun back(): Boolean {
        val top = backStack.removeLastOrNull() ?: return false
        (top as? Disposable)?.dispose()
        val node = backStack.lastOrNull() ?: page(currentTab)
        content.children.setAll(node)
        (node as? Refreshable)?.onShow()
        return true
    }

    fun showOverlay(node: Node) {
        overlay.children.lastOrNull()?.let { (it as? Disposable)?.dispose() }
        overlay.children.setAll(node)
    }

    fun closeOverlay(): Boolean {
        val top = overlay.children.lastOrNull() ?: return false
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
            hideSplash()
            loading.join()
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
        val logo = ImageView(Image(SplashScreen::class.java.getResourceAsStream("/ui/icon.png"), 112.0, 112.0, true, true)).apply {
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
