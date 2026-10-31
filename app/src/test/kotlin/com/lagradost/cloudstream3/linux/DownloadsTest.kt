package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** v7: downloads for offline viewing, a direct file and HLS, against the mock server. */
class DownloadsTest {
    private val tests = File(System.getProperty("tests.dir"))
    private lateinit var server: Process
    private var port = 0
    private val base get() = "http://localhost:$port"
    private val folder = Files.createTempDirectory("cs-downloads").toFile()
    private var savedQuality = 0
    private var savedTimeout = 0

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
        Settings.downloadFolder = folder.path
        savedQuality = Settings.preferredQuality
        savedTimeout = Settings.sourcesTimeoutSeconds
        Settings.sourcesTimeoutSeconds = 3
    }

    @AfterTest
    fun stop() {
        Downloads.all().forEach { Downloads.delete(it.id) }
        Settings.downloadFolder = ""
        Settings.preferredQuality = savedQuality
        Settings.sourcesTimeoutSeconds = savedTimeout
        server.destroy()
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
        folder.deleteRecursively()
    }

    private fun waitFor(id: String): Downloads.Item {
        repeat(300) {
            val i = Downloads.all().first { it.id == id }
            if (i.state == Downloads.State.DONE || i.state == Downloads.State.FAILED) return i
            Thread.sleep(100)
        }
        error("download didn't finish")
    }

    @Test
    fun `a movie downloads, plays offline, and HLS joins into one file`() = runBlocking {
        val demo = PluginManager.loadPlugin(File(System.getProperty("demo.repo"), "Demo.cs3"), PluginData("Demo", null, false, "Demo.cs3", 1))
            .getOrThrow().providers.single()
        val film = Engine.load(demo, "$base/title/night_of_the_test_pattern")!!
        val p = Engine.playables(film).single()

        // Direct file: the 720p MP4 (with the Referer the extension asked for), plus its subtitles.
        Settings.preferredQuality = 720
        val item = waitFor(Downloads.start(demo, film, p).id)
        assertEquals(Downloads.State.DONE, item.state, item.error)
        val mp4 = File(tests, "mock-archive/media/test_film_640.mp4")
        assertEquals(mp4.length(), File(item.file!!).length())
        assertTrue(File(item.file!!).parentFile == folder)
        assertTrue(item.subtitles.single().endsWith(".srt"), "subtitles saved beside the video")

        // The downloaded copy is offered first, and plays through OfflineApi under the same name.
        val first = Engine.links(demo, p.data).first() as LinkEvent.Link
        assertEquals(OfflineApi.SOURCE, first.link.source)
        val offline = OfflineApi(demo.name)
        val r = offline.response(item)
        assertEquals(film.name, r.name)
        assertEquals(p.data, Engine.playables(r).single().data)

        // HLS (needs a token header on every request): segments joined into one .ts.
        Downloads.delete(item.id)
        assertTrue(!File(item.file!!).exists(), "deleting removes the file")
        Settings.preferredQuality = 480
        val hls = waitFor(Downloads.start(demo, film, p).id)
        assertEquals(Downloads.State.DONE, hls.state, hls.error)
        assertTrue(hls.hls && hls.file!!.endsWith(".ts"))
        val segments = File(tests, "mock-archive/media/hls").listFiles { f -> f.name.endsWith(".ts") }!!.sumOf { it.length() }
        assertEquals(segments, File(hls.file!!).length())
        assertEquals(hls.segments, hls.segmentsDone)
        Unit
    }

    @Test
    fun `HLS playlists with keys, byte ranges and variants are read correctly`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nmid/720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
        Settings.preferredQuality = 720
        assertEquals("https://cdn.example/a/mid/720.m3u8", Downloads.pickVariant(master, "https://cdn.example/a/master.m3u8"))
        Settings.preferredQuality = 0
        assertEquals("https://cdn.example/a/high.m3u8", Downloads.pickVariant(master, "https://cdn.example/a/master.m3u8"))

        val media = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k1\",IV=0x0000000000000000000000000000000A\n#EXTINF:4,\nseg1.m4s\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k2\"\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000@0\nall.m4s\n#EXTINF:4,\n#EXT-X-BYTERANGE:500\nall.m4s\n" +
            "#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\nplain.m4s\n#EXT-X-ENDLIST\n"
        val (init, segs) = Downloads.parseMedia(media, "https://cdn.example/v/index.m3u8")
        assertEquals("https://cdn.example/v/init.mp4", init)
        assertEquals(4, segs.size)
        assertEquals("https://cdn.example/keys/k1", segs[0].keyUrl)
        assertEquals(10, segs[0].iv!!.last().toInt())
        assertEquals(7L, segs[0].sequence)
        assertEquals("https://keys.example/k2", segs[1].keyUrl)
        assertEquals(null, segs[1].iv, "no IV: the sequence number is used")
        assertEquals("bytes=0-999", segs[1].range)
        assertEquals("bytes=1000-1499", segs[2].range)
        assertNotNull(segs[3]); assertEquals(null, segs[3].keyUrl)
    }
}
