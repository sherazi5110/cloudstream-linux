package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Engine
import javafx.scene.image.WritableImage
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Scripted UI tour for documentation and smoke tests:
 * CS_SCREENSHOT=/path/prefix [CS_SCREENSHOT_SEARCH=query] cloudstream-linux
 */
object Screenshots {
    suspend fun runIfRequested(window: AppWindow) {
        val prefix = System.getenv("CS_SCREENSHOT") ?: return
        delay(2500)
        for (tab in Tab.entries) {
            window.show(tab)
            delay(if (tab == Tab.HOME) 3500 else 1800)
            capture(window, "$prefix-${tab.name.lowercase()}.png")
        }
        System.getenv("CS_SCREENSHOT_SEARCH")?.let { query ->
            window.show(Tab.SEARCH)
            (window.scene.root.lookupAll(".search-field").firstOrNull() as? javafx.scene.control.TextField)?.text = query
            delay(3500)
            capture(window, "$prefix-search-results.png")
            val first = Engine.search(query).let { flow -> var hit: Pair<com.lagradost.cloudstream3.MainAPI, com.lagradost.cloudstream3.SearchResponse>? = null
                flow.collect { b -> if (hit == null) b.results.firstOrNull()?.let { hit = b.api to it } }; hit }
            if (first != null) {
                Nav.open(first.first, first.second)
                delay(3500)
                capture(window, "$prefix-details.png")
                // Manual source list: watch sources stream in (fast host, then a slow one).
                clickButton(window, "Sources")
                delay(2500)
                capture(window, "$prefix-sources-early.png")
                delay(8500)
                capture(window, "$prefix-sources.png")
                window.closeOverlay()
                // Auto-play into the player, then check history-driven UI.
                window.lookup(".btn-primary")?.let { (it as javafx.scene.control.Button).fire() }
                delay(15000)
                capture(window, "$prefix-details-resume.png")
                window.show(Tab.HOME)
                delay(2500)
                capture(window, "$prefix-home-continue.png")
                window.show(Tab.LIBRARY)
                delay(1200)
                capture(window, "$prefix-library-continue.png")
            }
        }
        delay(500)
        System.exit(0)
    }

    private fun clickButton(window: AppWindow, text: String) {
        window.lookupAll(".btn").filterIsInstance<javafx.scene.control.Button>().firstOrNull { it.text == text }?.fire()
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
