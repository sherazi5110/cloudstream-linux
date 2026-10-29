package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Settings
import javafx.scene.image.WritableImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Scripted UI tour for documentation and smoke tests (development only):
 *   CS_SCREENSHOT=/path/prefix [CS_SCREENSHOT_SEARCH=query] cloudstream-linux
 * The player part needs `xdotool` (to move the mouse and press keys); the built-in
 * player is part of the JavaFX scene, so ordinary snapshots include the video.
 */
object Screenshots {
    suspend fun runIfRequested(window: AppWindow) {
        val prefix = System.getenv("CS_SCREENSHOT") ?: return
        delay(1500)
        for (tab in Tab.entries) {
            window.show(tab)
            delay(if (tab == Tab.HOME) 3000 else 1500)
            capture(window, "$prefix-${tab.name.lowercase()}.png")
        }
        for (category in listOf("appearance", "playback", "developer", "storage", "shortcuts", "about")) {
            window.openSettings(category)
            delay(1200)
            capture(window, "$prefix-settings-$category.png")
        }
        Theme.mode = ThemeMode.GLASS
        window.openSettings("appearance")
        delay(1200)
        capture(window, "$prefix-glass-settings.png")
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.AMOLED, ThemeMode.GLASS)) {
            Theme.mode = mode
            window.show(Tab.HOME)
            delay(1500)
            capture(window, "$prefix-theme-${mode.name.lowercase()}.png")
        }
        Theme.mode = ThemeMode.DARK

        val query = System.getenv("CS_SCREENSHOT_SEARCH")
        if (query != null) {
            window.show(Tab.SEARCH)
            delay(500)
            (window.scene.root.lookupAll(".search-field").firstOrNull() as? javafx.scene.control.TextField)?.text = query
            delay(3000)
            capture(window, "$prefix-search-results.png")
            var hit: Pair<MainAPI, SearchResponse>? = null
            Engine.search(query).collect { b -> if (hit == null) b.results.firstOrNull()?.let { hit = b.api to it } }
            hit?.let { (api, item) ->
                Nav.open(api, item)
                delay(3000)
                capture(window, "$prefix-details.png")
                // Play: loading screen, then the built-in player (drawn by JavaFX, so plain snapshots work).
                window.lookup(".btn-primary")?.let { (it as javafx.scene.control.Button).fire() }
                delay(1500)
                capture(window, "$prefix-loading.png")
                delay(9000)
                // Start from the beginning, whatever resume point earlier runs left.
                xdotool("key", "Home")
                delay(1500)
                xdotool("mousemove", "700", "500"); delay(150); xdotool("mousemove", "720", "520")
                delay(800)
                capture(window, "$prefix-player.png")
                xdotool("key", "s")
                delay(800)
                capture(window, "$prefix-player-sources.png")
                clickRow(window, "TokenHost HLS")
                delay(6000)
                xdotool("mousemove", "700", "500"); delay(400)
                capture(window, "$prefix-player-switched.png")
                xdotool("key", "c")
                delay(800)
                capture(window, "$prefix-player-subtitles.png")
                clickRow(window, "Search online")
                delay(2500)
                capture(window, "$prefix-player-online.png")
                clickRow(window, "English #1")
                delay(3000)
                capture(window, "$prefix-player-online-sub.png")
                xdotool("key", "a")
                delay(600)
                capture(window, "$prefix-player-audio.png")
                xdotool("key", "Escape")
                (window.lookup("#player-zoom") as? javafx.scene.control.Button)?.fire() ?: System.err.println("tour: no zoom button")
                delay(600)
                capture(window, "$prefix-player-zoom.png")
                clickRow(window, "Stretch")
                delay(800)
                capture(window, "$prefix-player-stretch.png")
                clickRow(window, "Fit")
                xdotool("key", "Escape")
                delay(400)
                xdotool("key", "Escape")
                delay(1500)
                capture(window, "$prefix-after-player.png")
            }
        }
        // Home manager: choose extensions and their catalogs.
        window.show(Tab.HOME)
        delay(1500)
        (window.lookup("#home-customize") as? javafx.scene.control.Button)?.fire() ?: System.err.println("tour: no Customize button")
        delay(800)
        capture(window, "$prefix-home-manager.png")
        (window.lookup("#tune-Demo") as? javafx.scene.control.Button)?.fire() ?: System.err.println("tour: no tune button")
        delay(800)
        capture(window, "$prefix-home-catalogs.png")
        window.closeOverlay()
        // The result of a customised, combined feed (then restored).
        val apis = com.lagradost.cloudstream3.linux.Engine.providers().filter { it.hasMainPage }
        if (apis.size >= 2) {
            val before = apis.associateWith { com.lagradost.cloudstream3.linux.Catalogs.all(it) }
            val demo = apis.first { it.name == "Demo" }
            val edited = before.getValue(demo).reversed().map { if (it.index == 0) it.copy(enabled = false) else it }
            com.lagradost.cloudstream3.linux.Catalogs.saveAll(mapOf(demo to edited), true, apis.map { it.name })
            Settings.lastHomeProvider = HomePage.FEED
            delay(4000)
            capture(window, "$prefix-home-feed.png")
            com.lagradost.cloudstream3.linux.Catalogs.saveAll(mapOf(demo to before.getValue(demo).sortedBy { it.index }.map { it.copy(enabled = true) }), false, emptyList())
        }
        // "View all" with infinite scrolling: the Demo's Endless catalog has 4 pages of 24.
        Engine.provider("Demo")?.let { demo ->
            val section = demo.mainPage.first { it.data == "paged" }
            val first = Engine.homePage(demo, section, 1)?.items?.flatMap { it.list }.orEmpty()
            Nav.window.push(GridPage(demo, section.name, section, first))
            delay(2500)
            capture(window, "$prefix-grid-top.png")
            repeat(6) {
                (window.lookupAll(".scroll-pane").lastOrNull() as? javafx.scene.control.ScrollPane)?.vvalue = 1.0
                delay(1200)
            }
            capture(window, "$prefix-grid-end.png")
        }
        delay(500)
        System.exit(0)
    }

    /** Clicks the player-menu row whose title starts with [text]. */
    private fun clickRow(window: AppWindow, text: String) {
        val row = window.lookupAll(".player-menu-row").firstOrNull { r ->
            (r as javafx.scene.Parent).lookupAll(".player-menu-item").any { (it as javafx.scene.control.Label).text.startsWith(text) }
        } ?: return System.err.println("tour: no menu row '$text'")
        javafx.event.Event.fireEvent(row, javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
            0.0, 0.0, 0.0, 0.0, javafx.scene.input.MouseButton.PRIMARY, 1,
            false, false, false, false, true, false, false, true, false, false, null))
    }

    private suspend fun xdotool(vararg args: String) = withContext(Dispatchers.IO) {
        runCatching { ProcessBuilder(listOf("xdotool") + args).start().waitFor() }
    }

    private fun capture(window: AppWindow, path: String) {
        val image: WritableImage = window.scene.snapshot(null)
        val w = image.width.toInt()
        val h = image.height.toInt()
        val pixels = IntArray(w * h)
        image.pixelReader.getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(out, "png", File(path))
    }
}
