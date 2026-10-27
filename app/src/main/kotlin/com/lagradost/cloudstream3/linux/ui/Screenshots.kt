package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.linux.Engine
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
 * The player part needs ImageMagick `import` and `xdotool`, because mpv renders natively
 * and is invisible to JavaFX snapshots.
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
        for (category in listOf("appearance", "playback", "developer", "storage")) {
            window.openSettings(category)
            delay(1200)
            capture(window, "$prefix-settings-$category.png")
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.AMOLED)) {
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
                // Play: loading screen, then the built-in player.
                window.lookup(".btn-primary")?.let { (it as javafx.scene.control.Button).fire() }
                delay(1500)
                capture(window, "$prefix-loading.png")
                delay(9000)
                screen("$prefix-player.png")
                for (y in listOf(500, 600, 700, 760, 790)) { xdotool("mousemove", "700", y.toString()); delay(120) }
                delay(1200)
                screen("$prefix-player-controls.png")
                xdotool("key", "s")
                delay(1500)
                screen("$prefix-player-sources.png")
                // Switch to the other source from inside the player.
                xdotool("key", "Up")
                delay(300)
                xdotool("key", "Return")
                delay(5000)
                screen("$prefix-player-switched.png")
                xdotool("key", "c")
                delay(1200)
                screen("$prefix-player-settings.png")
                xdotool("key", "Escape")
                delay(400)
                Playback.active?.let { xdotool("mousemove", "700", "400"); delay(300) }
                xdotool("key", "Escape")
                delay(3000)
                capture(window, "$prefix-after-player.png")
            }
        }
        delay(500)
        System.exit(0)
    }

    private suspend fun xdotool(vararg args: String) = withContext(Dispatchers.IO) {
        runCatching { ProcessBuilder(listOf("xdotool") + args).start().waitFor() }
    }

    /** Whole-screen capture, including mpv's native video surface. */
    private suspend fun screen(path: String) = withContext(Dispatchers.IO) {
        runCatching { ProcessBuilder("import", "-window", "root", path).start().waitFor() }
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
