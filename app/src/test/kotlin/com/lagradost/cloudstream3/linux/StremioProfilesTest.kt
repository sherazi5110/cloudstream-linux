package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** v6: Stremio add-ons (against the mock add-on in tests/mock-archive) and profiles with the kids filter. */
class StremioProfilesTest {
    private val tests = File(System.getProperty("tests.dir"))
    private lateinit var server: Process
    private var port = 0
    private val base get() = "http://localhost:$port"

    @BeforeTest
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder("python3", "-I", tests.resolve("mock-archive/server.py").path, port.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        repeat(50) {
            if (runCatching { URI("http://127.0.0.1:$port/advancedsearch.php").toURL().readText() }.isSuccess) return@repeat
            Thread.sleep(100)
        }
        Platform.init()
        Network.init()
        CloudStreamApp.setKey("demo_base_url", base)
    }

    @AfterTest
    fun stop() {
        server.destroy()
        StremioAddons.all().forEach { StremioAddons.remove(it.url) }
        Profiles.all().filter { it.id != Profiles.DEFAULT }.forEach { Profiles.delete(it.id) }
        Profiles.switchTo(Profiles.all().first().id)
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
    }

    @Test
    fun `a Stremio add-on works like an extension`() = runBlocking {
        assertEquals("https://example.org/x/manifest.json", StremioAddons.normalize("stremio://example.org/x/manifest.json"))
        assertEquals("https://example.org/x/manifest.json", StremioAddons.normalize("example.org/x/"))

        val addon = StremioAddons.install("$base/addon")
        assertEquals("Test Add-on", addon.manifest.name)
        val api = Engine.allProviders().single { it is StremioApi }
        assertEquals("Test Add-on", api.name)
        assertEquals(setOf(TvType.Movie, TvType.TvSeries), api.supportedTypes)
        // The search-only catalog can't be a Home row.
        assertEquals(listOf("Top films · Movies", "Popular · Series"), api.mainPage.map { it.name })

        // Catalog paging with skip=, until a page comes back empty.
        val top = api.mainPage.first()
        val pages = (1..3).map { api.getMainPage(it, MainPageRequest(top.name, top.data, false))!! }
        assertEquals(listOf(6, 4, 0), pages.map { it.items.single().list.size })
        assertEquals(listOf(true, true, false), pages.map { it.hasNext })

        assertEquals(listOf("His Girl Pixel"), api.search("pixel")!!.map { it.name })

        // Details: a film with backdrop, rating and cast; a series with its episodes.
        val film = Engine.load(api, pages[0].items.single().list.first().url)!!
        assertEquals("Night of the Test Pattern", film.name)
        assertEquals("PG", film.contentRating)
        assertNotNull(film.backgroundPosterUrl)
        assertEquals(94, film.duration)
        assertEquals(2, film.actors?.size)
        val seriesItem = api.getMainPage(1, MainPageRequest("p", api.mainPage[1].data, false))!!.items.single().list.single()
        val series = Engine.load(api, seriesItem.url) as TvSeriesLoadResponse
        assertEquals(6, series.episodes.size)
        assertEquals(listOf(1, 1, 1, 2, 2, 2), series.episodes.map { it.season })

        // Streams: two HTTP streams with their headers; the torrent-only one is skipped. Plus subtitles.
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        assertTrue(api.loadLinks(Engine.playables(film).single().data, false, { subs += it }, { links += it }))
        assertEquals(2, links.size)
        assertEquals("$base/", links.first { it.url.endsWith(".mp4") }.headers["Referer"])
        assertEquals("demo", links.first { it.url.endsWith(".m3u8") }.headers["X-CS-Token"])
        assertEquals(listOf("English"), subs.map { it.lang })
        // The header-protected file plays through the proxy, as in the player.
        val mp4 = links.first { it.url.endsWith(".mp4") }
        assertEquals(206, app.get(StreamProxy.wrap(mp4.url, mp4.headers), headers = mapOf("Range" to "bytes=0-99")).code)

        // Turning it off removes it from Home and Search.
        StremioAddons.setEnabled(addon.url, false)
        assertTrue(Engine.allProviders().none { it is StremioApi })
    }

    @Test
    fun `profiles keep their own library, and a kids profile only sees what it is allowed`() = runBlocking {
        val demo = PluginManager.loadPlugin(File(System.getProperty("demo.repo"), "Demo.cs3"), PluginData("Demo", null, false, "Demo.cs3", 1))
            .getOrThrow().providers.single()
        val entry = WatchEntry(demo.name, "$base/title/pixel_city", "Pixel City", null, "x", "S1E1", 100.0, 1000.0, 1)
        Library.record(entry)
        val adult = Profiles.current

        val kids = Profiles.add("Kid", "cat:green", kids = true)
        Profiles.switchTo(kids.id)
        assertTrue(Profiles.isKids)
        assertTrue(Library.history().isEmpty(), "a new profile starts with an empty history")
        // A new kids profile has no extensions allowed.
        assertTrue(Engine.providers().isEmpty())
        Profiles.update(Profiles.current.copy(filter = Profiles.current.filter.copy(extensions = setOf(demo.name))))
        assertEquals(listOf(demo.name), Engine.providers().map { it.name })

        // Types, words and age ratings.
        val cartoon = demo.newMovieSearchResponse("Happy Bunnies", "u1", TvType.Cartoon)
        val horror = demo.newMovieSearchResponse("Horror Night", "u2", TvType.Movie)
        val nsfw = demo.newMovieSearchResponse("x", "u3", TvType.NSFW)
        val live = demo.newMovieSearchResponse("News", "u4", TvType.Live)
        assertTrue(ContentFilter.allows(cartoon))
        assertFalse(ContentFilter.allows(horror))
        assertFalse(ContentFilter.allows(nsfw))
        assertFalse(ContentFilter.allows(live))
        assertEquals(13, ContentFilter.ageOf("PG-13"))
        assertEquals(18, ContentFilter.ageOf("TV-MA"))
        assertEquals(16, ContentFilter.ageOf("FSK 16"))
        assertEquals(0, ContentFilter.ageOf("G"))
        val film = Engine.load(demo, "$base/title/night_of_the_test_pattern")!!
        assertTrue(ContentFilter.allows(film))
        film.contentRating = "R"
        assertFalse(ContentFilter.allows(film))

        Profiles.switchTo(adult.id)
        assertFalse(Profiles.isKids)
        assertEquals(listOf("Pixel City"), Library.history().map { it.name })
        assertTrue(ContentFilter.allows(horror))
        Library.removeHistory(entry.apiName, entry.url)
    }
}
