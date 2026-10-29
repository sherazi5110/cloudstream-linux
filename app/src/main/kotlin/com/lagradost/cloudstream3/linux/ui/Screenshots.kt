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
        // CS_SCREENSHOT_ONLY=v6: just the profiles / sections / add-on part (quicker while developing).
        if (System.getenv("CS_SCREENSHOT_ONLY") == "glass") {
            Theme.mode = ThemeMode.GLASS
            window.show(Tab.HOME)
            delay(4000)
            capture(window, "$prefix-theme-glass.png")
            Theme.mode = ThemeMode.DARK
            delay(2500)
            capture(window, "$prefix-theme-dark.png")
            System.exit(0)
        }
        if (System.getenv("CS_SCREENSHOT_ONLY") == "kids") {
            // The only profile set to kids, before parent PINs existed: the way out.
            val me = com.lagradost.cloudstream3.linux.Profiles.current
            com.lagradost.cloudstream3.linux.Profiles.update(me.copy(kids = true, filter = me.filter.copy(extensions = setOf("Demo"))))
            delay(3000)
            (window.lookup(".topbar-profile") as? javafx.scene.Node)?.let { javafx.event.Event.fireEvent(it, click()) }
            delay(700)
            capture(window, "$prefix-kids-menu.png")
            clickPopover(window, "Exit kids profile")
            delay(2500)
            capture(window, "$prefix-kids-exited.png")
            // Making a kids profile now asks for a parent PIN.
            window.showOverlay(ProfileEditor(null))
            delay(800)
            window.lookupAll(".switch").lastOrNull()?.let { javafx.event.Event.fireEvent(it, click()) }
            delay(800)
            (window.lookup(".sheet")?.lookup(".scroll-pane") as? javafx.scene.control.ScrollPane)?.vvalue = 0.62
            delay(700)
            capture(window, "$prefix-kids-parent-pin.png")
            System.exit(0)
        }
        if (System.getenv("CS_SCREENSHOT_ONLY") == "v601") {
            v601Tour(window, prefix)
            System.exit(0)
        }
        if (System.getenv("CS_SCREENSHOT_ONLY") == "v6") {
            window.show(Tab.HOME)
            delay(3000)
            v6Tour(window, prefix)
            System.exit(0)
        }
        for (tab in Tab.entries) {
            window.show(tab)
            delay(if (tab == Tab.HOME) 3000 else 1500)
            capture(window, "$prefix-${tab.name.lowercase()}.png")
        }
        for (category in listOf("profiles", "appearance", "playback", "developer", "storage", "shortcuts", "about")) {
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
        // Home manager: choose extensions and their catalogs (from the source menu in the top bar).
        window.show(Tab.HOME)
        delay(1500)
        (window.lookup("#home-source") as? javafx.scene.control.Button)?.fire() ?: System.err.println("tour: no source button")
        delay(600)
        capture(window, "$prefix-source-menu.png")
        clickPopover(window, "Customize home")
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
        v6Tour(window, prefix)
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

    /** Sections, profiles, the kids profile and a Stremio add-on (v6). */
    private suspend fun v6Tour(window: AppWindow, prefix: String) {
        window.show(Tab.HOME)
        delay(1500)
        (window.lookup(".topbar-profile") as? javafx.scene.Node)?.let { javafx.event.Event.fireEvent(it, click()) }
        delay(600)
        capture(window, "$prefix-profile-menu.png")
        window.closeOverlay()

        val me = com.lagradost.cloudstream3.linux.Profiles.current
        val sara = com.lagradost.cloudstream3.linux.Profiles.add("Sara", "fox:pink", false)
        com.lagradost.cloudstream3.linux.Profiles.update(sara.copy(pin = "1234"))
        val kids = com.lagradost.cloudstream3.linux.Profiles.add("Kids", "frog:green", true)
        com.lagradost.cloudstream3.linux.Profiles.update(kids.copy(filter = kids.filter.copy(extensions = setOf("Demo"))))
        window.showOverlay(ProfilePicker(startup = false))
        delay(1200)
        capture(window, "$prefix-profiles.png")
        window.showOverlay(ProfilePicker(startup = false, manage = true))
        delay(800)
        capture(window, "$prefix-profiles-manage.png")
        window.showOverlay(ProfileEditor(com.lagradost.cloudstream3.linux.Profiles.all().first { it.id == kids.id }))
        delay(1200)
        capture(window, "$prefix-profile-editor.png")
        (window.lookup(".sheet")?.lookup(".scroll-pane") as? javafx.scene.control.ScrollPane)?.vvalue = 1.0
        delay(800)
        capture(window, "$prefix-profile-editor-kids.png")
        window.closeOverlay(force = true)
        window.showOverlay(PinPrompt("Enter Sara's PIN") { false })
        delay(700)
        capture(window, "$prefix-pin.png")
        window.closeOverlay(force = true)

        com.lagradost.cloudstream3.linux.Profiles.switchTo(kids.id)
        delay(4000)
        capture(window, "$prefix-kids-home.png")

        com.lagradost.cloudstream3.linux.Profiles.switchTo(me.id)
        delay(2500)

        // A Stremio add-on (the mock one): Extensions tab, then its catalogs on Home and a details page.
        runCatching { com.lagradost.cloudstream3.linux.StremioAddons.install("http://localhost:8766/addon") }
            .onFailure { System.err.println("tour: add-on install failed: $it") }
        window.show(Tab.EXTENSIONS)
        delay(1200)
        (window.lookup("#ext-kind") as? javafx.scene.Parent)?.childrenUnmodifiable?.getOrNull(1)?.let { javafx.event.Event.fireEvent(it, click()) }
        delay(1500)
        capture(window, "$prefix-addons.png")
        window.show(Tab.HOME)
        delay(1500)
        (window.lookup("#home-source") as? javafx.scene.control.Button)?.fire()
        delay(600)
        clickPopover(window, "Test Add-on")
        delay(5000)
        capture(window, "$prefix-home-addon.png")
        com.lagradost.cloudstream3.linux.Engine.provider("Test Add-on")?.let { api ->
            Nav.open(api.name, "${api.mainUrl}/meta/movie/tst%3Anight_of_the_test_pattern", "Night of the Test Pattern", null)
            delay(3000)
            capture(window, "$prefix-addon-details.png")
            window.back()
        }
        com.lagradost.cloudstream3.linux.StremioAddons.all().forEach { com.lagradost.cloudstream3.linux.StremioAddons.remove(it.url) }
        listOf(sara.id, kids.id).forEach { com.lagradost.cloudstream3.linux.Profiles.delete(it) }
        Settings.lastHomeProvider = "Demo"
        delay(1500)
    }

    /** Live TV, watched controls and the metadata hub (6.0.1). Uses the mock server on port 8766. */
    private suspend fun v601Tour(window: AppWindow, prefix: String) {
        val m = "http://localhost:8766"
        window.show(Tab.HOME)
        delay(3500)
        capture(window, "$prefix-home.png")
        // Hover a Continue Watching card and open its ⋯ menu.
        window.lookupAll(".poster-card").firstOrNull { it is WideCard }?.let { card ->
            javafx.event.Event.fireEvent(card, javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_ENTERED, 0.0, 0.0, 0.0, 0.0,
                javafx.scene.input.MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null))
            delay(400)
            (card as javafx.scene.Parent).lookupAll(".card-action").lastOrNull()?.let { javafx.event.Event.fireEvent(it, click()) }
        }
        delay(600)
        capture(window, "$prefix-continue-menu.png")
        window.closeOverlay()

        com.lagradost.cloudstream3.linux.Metadata.tmdbKey = "0123456789abcdef0123456789abcdef"
        runCatching { com.lagradost.cloudstream3.linux.LiveTv.add(com.lagradost.cloudstream3.linux.LiveTv.Kind.M3U, "$m/iptv/playlist.m3u", "Free-TV", "$m/iptv/guide.xml") }
            .onFailure { System.err.println("tour: m3u failed: $it") }
        runCatching { com.lagradost.cloudstream3.linux.StremioAddons.install("$m/addon-tv") }.onFailure { System.err.println("tour: tv add-on failed: $it") }
        window.show(Tab.LIVE)
        delay(4000)
        capture(window, "$prefix-livetv.png")
        (window.lookupAll(".scroll-pane").firstOrNull { it.parent?.parent is LiveTvPage || it.parent is LiveTvPage } as? javafx.scene.control.ScrollPane)?.vvalue = 0.5
        delay(800)
        capture(window, "$prefix-livetv-rows.png")
        (window.lookup(".topbar-field") as? javafx.scene.control.TextField)?.text = "news"
        delay(1200)
        capture(window, "$prefix-livetv-search.png")
        (window.lookup(".topbar-field") as? javafx.scene.control.TextField)?.text = ""
        window.openSettings("livetv")
        delay(1500)
        capture(window, "$prefix-settings-livetv.png")
        window.openSettings("metadata")
        delay(1200)
        capture(window, "$prefix-settings-metadata.png")
        window.openSettings("tmdb")
        delay(1200)
        capture(window, "$prefix-settings-tmdb.png")
        Nav.open("Demo", "$m/title/night_of_the_test_pattern", "Night of the Test Pattern", null)
        delay(5000)
        capture(window, "$prefix-details-tmdb.png")
        (window.lookupAll(".scroll-pane").lastOrNull() as? javafx.scene.control.ScrollPane)?.vvalue = 1.0
        delay(1200)
        capture(window, "$prefix-details-tmdb-cast.png")
        window.back()
        window.show(Tab.HOME)
        delay(4000)
        capture(window, "$prefix-home-tmdb.png")
        window.show(Tab.LIBRARY)
        delay(800)
        (window.lookup(".chip-group") as? javafx.scene.Parent)
        window.lookupAll(".chip").firstOrNull { (it as? javafx.scene.control.Label)?.text == "History" }?.let { javafx.event.Event.fireEvent(it, click()) }
        delay(800)
        capture(window, "$prefix-library-history.png")
        // Clean up the sources this tour added.
        com.lagradost.cloudstream3.linux.LiveTv.saved().forEach { com.lagradost.cloudstream3.linux.LiveTv.remove(it.id) }
        com.lagradost.cloudstream3.linux.StremioAddons.all().forEach { com.lagradost.cloudstream3.linux.StremioAddons.remove(it.url) }
        com.lagradost.cloudstream3.linux.Metadata.tmdbKey = ""
    }

    private fun click() = javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
        0.0, 0.0, 0.0, 0.0, javafx.scene.input.MouseButton.PRIMARY, 1,
        false, false, false, false, true, false, false, true, false, false, null)

    private fun clickPopover(window: AppWindow, text: String) {
        val row = window.lookupAll(".popover-row").firstOrNull { r ->
            (r as javafx.scene.Parent).lookupAll(".popover-text").any { (it as javafx.scene.control.Label).text.startsWith(text) }
        } ?: return System.err.println("tour: no popover row '$text'")
        javafx.event.Event.fireEvent(row, click())
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
