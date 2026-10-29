package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 6.0.1: Live TV (M3U + XMLTV, Xtream, Stremio TV add-ons), watched controls and the metadata hub. */
class LiveTvMetadataTest {
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
        System.setProperty("cs.subtitles.meta", "$base/stremio/meta")
        System.setProperty("cs.tmdb.base", "$base/tmdb/3")
        System.setProperty("cs.tmdb.images", "$base/tmdbimg/")
        System.setProperty("cs.anilist", "$base/anilist")
    }

    @AfterTest
    fun stop() {
        server.destroy()
        LiveTv.saved().forEach { LiveTv.remove(it.id) }
        StremioAddons.all().forEach { StremioAddons.remove(it.url) }
        Metadata.tmdbKey = ""
        Metadata.clearCache()
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
    }

    @Test
    fun `M3U playlists with headers, a TV guide, favourites and playback data`() = runBlocking {
        val (source, count) = LiveTv.add(LiveTv.Kind.M3U, "$base/iptv/playlist.m3u", "Test IPTV", "$base/iptv/guide.xml")
        assertEquals(5, count)
        val channels = LiveTv.channels(source.id)
        assertEquals(listOf("News 24", "Sport One", "Cartoon Fun", "Movie Max", "Planet Docs"), channels.map { it.name })
        assertEquals(listOf("News", "Sports", "Kids", "Movies", "Documentary"), channels.map { it.group })
        // Kodi "url|Header=value" and #EXTVLCOPT referrer both become request headers.
        assertEquals("demo", channels[0].headers["X-CS-Token"])
        assertTrue(channels[0].url.endsWith("/hls/master.m3u8"))
        assertEquals("$base/", channels[3].headers["Referer"])
        assertEquals("GB", channels[0].country)

        LiveTv.loadGuides(force = true)
        val (now, next) = LiveTv.nowNext(channels[0])
        assertNotNull(now); assertNotNull(next)
        assertTrue(now.start <= System.currentTimeMillis() && System.currentTimeMillis() < now.stop)
        assertTrue(now.title.startsWith("News 24 show"))

        assertTrue(LiveTv.toggleFavourite(channels[2]))
        assertEquals(setOf(channels[2].key), LiveTv.favourites())
        assertFalse(LiveTv.toggleFavourite(channels[2]))
        LiveTv.watched(channels[1]); LiveTv.watched(channels[0])
        assertEquals(listOf(channels[0].key, channels[1].key), LiveTv.recent().take(2))

        // Playing: the live API turns a channel into a link with its headers; the HLS answers through the proxy.
        val response = LiveApi.load(LiveApi.data(channels[0]))!!
        assertEquals(TvType.Live, response.type)
        val links = mutableListOf<ExtractorLink>()
        LiveApi.loadLinks(Engine.playables(response).single().data, false, {}, { links += it })
        val link = links.single()
        assertEquals("demo", link.headers["X-CS-Token"])
        assertEquals(200, app.get(StreamProxy.wrap(link.url, link.headers)).code)

        // Live TV never shows up as a Home/Search source.
        assertTrue(Engine.allProviders().none { it.name == LiveApi.name })
    }

    @Test
    fun `Kodi add-on zips, playlist files, web pages and channel logos`() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("kodi").toFile()
        fun zip(name: String, files: Map<String, String>) = File(dir, name).apply {
            java.util.zip.ZipOutputStream(outputStream()).use { z -> files.forEach { (path, text) -> z.putNextEntry(java.util.zip.ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() } }
        }
        // An IPTV Simple–style add-on: the playlist and guide addresses are settings defaults.
        val withUrls = zip("pvr.test.zip", mapOf(
            "pvr.test/addon.xml" to """<addon id="pvr.test" name="Test IPTV" version="1.0"><extension point="xbmc.addon.metadata"><website>https://example.org/addons.xml</website></extension></addon>""",
            "pvr.test/resources/settings.xml" to """<settings><setting id="m3uUrl" default="$base/iptv/playlist.m3u"/><setting id="epgUrl" default="$base/iptv/guide.xml"/></settings>""",
        ))
        val r1 = KodiAddons.import(withUrls)
        assertEquals("Test IPTV", r1.name)
        assertEquals(5, r1.added.single().second)
        assertEquals("$base/iptv/guide.xml", r1.added.single().first.epgUrl)
        // A playlist inside the zip.
        val embedded = zip("plugin.embedded.zip", mapOf(
            "plugin.embedded/addon.xml" to """<addon id="plugin.embedded" name="Embedded"/>""",
            "plugin.embedded/resources/channels.m3u" to "#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\n$base/hls/master.m3u8\n#EXTINF:-1,Two\n$base/hls/master.m3u8\n",
        ))
        val r2 = KodiAddons.import(embedded)
        assertEquals(2, r2.added.single().second)
        assertTrue(r2.added.single().first.url.startsWith("file:"))
        // A Python-only add-on: a clear error.
        val python = zip("plugin.video.x.zip", mapOf("plugin.video.x/addon.xml" to """<addon id="plugin.video.x" name="Some Plugin"/>""", "plugin.video.x/main.py" to "import xbmc"))
        val err = runCatching { KodiAddons.import(python) }.exceptionOrNull()?.message.orEmpty()
        assertTrue("Some Plugin" in err && "only runs inside Kodi" in err, err)

        // Web pages aren't playlists; GitHub page links become raw file links.
        val page = runCatching { LiveTv.add(LiveTv.Kind.M3U, "$base/webpage") }.exceptionOrNull()?.message.orEmpty()
        assertTrue("web page" in page, page)
        assertEquals("https://raw.githubusercontent.com/u/r/main/list.m3u", LiveTv.rawGithub("https://github.com/u/r/blob/main/list.m3u"))

        // Logos from the iptv-org database: by tvg-id, then by name; SVGs skipped.
        System.setProperty("cs.logos.base", "$base/logos-api")
        File(AppDirs.data, "cache/livetv/iptv-org-logos.json").delete()
        val news = LiveTv.Channel("k", "s", "News 24 HD (1080p)", null, "News", "u", epgId = "News24.uk@SD")
        assertEquals(listOf("$base/media/poster.png"), ChannelLogos.candidates(news))
        val cartoon = LiveTv.Channel("k2", "s", "Cartoon Fun Kids [Geo-blocked]", "http://broken/logo.png", "Kids", "u")
        assertEquals(listOf("http://broken/logo.png", "$base/media/ep_1.jpg"), ChannelLogos.candidates(cartoon))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `Xtream logins and Stremio TV add-ons go to Live TV only`() = runBlocking {
        val bad = runCatching { LiveTv.add(LiveTv.Kind.XTREAM, "$base/xtream", null, null, "demo", "wrong") }
        assertTrue(bad.isFailure)
        val (xt, n) = LiveTv.add(LiveTv.Kind.XTREAM, "$base/xtream", "My IPTV", null, "demo", "secret")
        assertEquals(2, n)
        val xc = LiveTv.channels(xt.id)
        assertEquals(listOf("News", "Kids"), xc.map { it.group })
        assertEquals("$base/xtream/live/demo/secret/11.m3u8", xc[0].url)
        LiveTv.loadGuides(force = true)
        assertNotNull(LiveTv.nowNext(xc[0]).first, "Xtream guide comes from xmltv.php")

        // A Stremio add-on with TV catalogs, installed the usual way, appears as a Live TV source…
        StremioAddons.install("$base/addon-tv")
        val tvSource = LiveTv.sources().single { it.kind == LiveTv.Kind.STREMIO }
        val tv = LiveTv.channels(tvSource.id)
        assertEquals(5, tv.size)
        // …and not on Home or in Search.
        assertTrue(Engine.allProviders().none { it is StremioApi })
        // Its channels play through the add-on's streams.
        val addon = StremioAddons.all().single()
        val api = StremioApi(addon)
        val r = Engine.load(api, tv[0].url)!!
        assertEquals(TvType.Live, r.type)
        val links = mutableListOf<ExtractorLink>()
        api.loadLinks(Engine.playables(r).single().data, false, { _: SubtitleFile -> }, { links += it })
        assertEquals("demo", links.single().headers["X-CS-Token"])
    }

    @Test
    fun `mark as watched and the metadata hub`() = runBlocking {
        val demo = PluginManager.loadPlugin(File(System.getProperty("demo.repo"), "Demo.cs3"), PluginData("Demo", null, false, "Demo.cs3", 1))
            .getOrThrow().providers.single()
        // Watched / unwatched / remove from Continue Watching.
        val entry = WatchEntry(demo.name, "$base/title/x", "X", null, "x|1", "S1E1", 300.0, 1000.0, 1)
        Library.record(entry)
        assertEquals(1, Library.continueWatching().count { it.url == entry.url })
        Library.markWatched(entry)
        assertTrue(Library.continueWatching().none { it.url == entry.url })
        assertTrue(Library.isWatched(demo.name, "x|1"))
        assertTrue(Library.lastWatched(demo.name, entry.url)!!.finished)
        Library.markUnwatched(demo.name, entry.url, listOf("x|1"))
        assertFalse(Library.isWatched(demo.name, "x|1"))
        Library.markWatched(demo.name, "$base/title/y", "Y", null, listOf("a" to "E1", "b" to "E2"))
        assertTrue(Library.isWatched(demo.name, "a") && Library.isWatched(demo.name, "b"))
        Library.markUnwatched(demo.name, "$base/title/y", listOf("a", "b"))

        // Titles from release names.
        assertEquals("Avaraachan & Sons" to 2026, Metadata.cleanTitle("Avaraachan & Sons (2026) WEB-DL [Hindi (DD5.1) & Tamil]"))
        assertEquals("Mirzapur: The Movie" to 2026, Metadata.cleanTitle("Mirzapur: The Movie (2026) DS4K EXTENDED 1080p"))

        // TMDB key check.
        assertNotNull(Metadata.testKey("ffffffffffffffffffffffffffffffff"))
        assertNull(Metadata.testKey("0123456789abcdef0123456789abcdef"))
        assertNull(Metadata.testKey("test-read-token"), "v4 read tokens work too")

        // Without a key: IMDb rating through Cinemeta only.
        val film = Engine.load(demo, "$base/title/night_of_the_test_pattern")!!
        val noKey = Metadata.details(film)!!
        assertNull(noKey.tmdbVote)
        assertEquals(8.4, noKey.imdbRating)

        // With a key: ratings, artwork, cast, trailer, similar titles; poster ratings.
        Metadata.tmdbKey = "0123456789abcdef0123456789abcdef"
        val info = Metadata.details(film)!!
        assertEquals(7.9, info.tmdbVote)
        assertEquals("tt9000002", info.imdbId)
        assertEquals("$base/tmdbimg/w1280/backdrop_night_of_the_test_pattern.jpg", info.backdrop)
        assertEquals("$base/tmdbimg/w500/logo.png", info.logo)
        assertEquals("PG", info.contentRating)
        assertEquals(listOf("Ada Scanline", "Vic Raster"), info.cast.map { it.name })
        assertEquals("https://www.youtube.com/watch?v=abc123", info.trailer)
        assertEquals("Metropolis Bars", info.similar.single().title)
        val series = Engine.load(demo, "$base/title/pixel_city")!!
        assertEquals("TV-14", Metadata.details(series)!!.contentRating)
        assertEquals("TMDB 7.9", Metadata.posterRating(demo.newMovieSearchResponse("Night of the Test Pattern (1968) 1080p WEB-DL", "u", TvType.Movie)))
        assertNull(Metadata.posterRating(demo.newMovieSearchResponse("Something Else Entirely", "u2", TvType.Movie)))
        // AniList for anime when TMDB has nothing.
        Metadata.tmdbKey = ""
        assertEquals("AniList 8.4", Metadata.posterRating(demo.newMovieSearchResponse("Pixel Anime", "u3", TvType.Anime)))
    }
}
