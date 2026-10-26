package com.lagradost.cloudstream3.linux.ui

import android.widget.Toast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.Settings
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ProgressIndicator
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

object AppInfo {
    const val NAME = "CloudStream for Linux"
    const val VERSION = "2.0.0"
}

class DesktopApp : Application() {
    override fun start(stage: Stage) {
        val window = AppWindow(stage)
        val scene = Scene(window, 1366.0, 840.0, Color.web("#09090c"))
        scene.stylesheets += DesktopApp::class.java.getResource("/ui/theme.css")!!.toExternalForm()
        stage.title = "CloudStream"
        DesktopApp::class.java.getResourceAsStream("/ui/icon.png")?.use { stage.icons += Image(it) }
        stage.minWidth = 980.0
        stage.minHeight = 640.0
        stage.scene = scene
        stage.setOnCloseRequest {
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

/** Root layout: navigation rail, page area with back stack, overlay layer and toasts. */
class AppWindow(val stage: Stage) : StackPane() {
    private val content = StackPane()
    private val overlay = StackPane().apply { isPickOnBounds = false }
    private val navButtons = mutableMapOf<Tab, Button>()
    private val pages = mutableMapOf<Tab, Node>()
    private val backStack = ArrayDeque<Node>()
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
        val rail = VBox(8.0).apply {
            styleClass += "nav-rail"
            alignment = Pos.TOP_CENTER
            val logo = StackPane(icon(Icons.PLAY, 18.0).apply { style += "-fx-background-color: white;" }).apply {
                styleClass += "nav-logo"
                style = "-fx-background-radius: 12;"
                setMinSize(40.0, 40.0); setMaxSize(40.0, 40.0)
            }
            children += logo
            children += Region().apply { minHeight = 18.0 }
            Tab.entries.forEach { tab ->
                val b = Button().apply {
                    styleClass.setAll("nav-button")
                    graphic = icon(tab.icon, 22.0)
                    tooltip(tab.label)
                    setOnAction { show(tab) }
                }
                navButtons[tab] = b
                if (tab == Tab.SETTINGS) children += spacer()
                children += b
            }
        }
        val toastHost = VBox(8.0).apply {
            alignment = Pos.BOTTOM_CENTER
            isPickOnBounds = false
            pad(0.0, 0.0, 28.0, 0.0)
        }
        Toasts.host = toastHost
        val layout = BorderPane(content).apply { left = rail }
        StackPane.setAlignment(statusPill, Pos.TOP_RIGHT)
        StackPane.setMargin(statusPill, javafx.geometry.Insets(16.0, 22.0, 0.0, 0.0))
        children.addAll(layout, statusPill, overlay, toastHost)

        addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            when {
                e.code == KeyCode.ESCAPE -> { if (closeOverlay() || back()) e.consume() }
                e.code == KeyCode.LEFT && e.isAltDown -> { back(); e.consume() }
                (e.code == KeyCode.F && e.isControlDown) || (e.code == KeyCode.SLASH && e.target !is javafx.scene.control.TextInputControl) -> {
                    show(Tab.SEARCH); (pages[Tab.SEARCH] as? SearchPage)?.focusField(); e.consume()
                }
            }
        }
        show(Tab.HOME)
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
        navButtons[currentTab]?.styleClass?.remove("active")
        currentTab = tab
        navButtons[tab]?.styleClass?.add("active")
        val node = page(tab)
        content.children.setAll(node)
        (node as? Refreshable)?.onShow()
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
        overlay.children.setAll(node)
    }

    fun closeOverlay(): Boolean {
        val top = overlay.children.lastOrNull() ?: return false
        (top as? Disposable)?.dispose()
        overlay.children.clear()
        return true
    }

    fun startup(repoLink: String?) {
        Toast.showHandler = { msg -> Platform.runLater { Toasts.show(msg) } }
        ui.launch {
            PluginManager.progress.collectLatest { p ->
                statusPill.isVisible = p.running
                statusPill.children.setAll(
                    ProgressIndicator().apply { setPrefSize(16.0, 16.0); setMaxSize(16.0, 16.0) },
                    label("Loading extensions ${p.done}/${p.total}", "small"),
                )
            }
        }
        ui.launch {
            val failures = kotlinx.coroutines.withContext(Engine.dispatcher) { PluginManager.loadAll() }
            if (failures.isNotEmpty()) {
                Toasts.show("${failures.size} extension(s) failed to load", "Details" to { show(Tab.EXTENSIONS) }, 6.0)
            }
            if (Settings.autoUpdateExtensions) {
                val updated = kotlinx.coroutines.withContext(Engine.dispatcher) { runCatching { PluginManager.updateAll() }.getOrDefault(emptyList()) }
                if (updated.isNotEmpty()) Toasts.show("Updated ${updated.joinToString()}")
            }
            Screenshots.runIfRequested(this@AppWindow)
        }
        repoLink?.let {
            show(Tab.EXTENSIONS)
            (pages[Tab.EXTENSIONS] as? ExtensionsPage)?.promptAddRepository(it)
        }
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
