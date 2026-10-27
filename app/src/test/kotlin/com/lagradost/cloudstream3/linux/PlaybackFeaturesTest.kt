package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v4 features: the header proxy, online subtitles, per-extension catalogs, and the
 * libVLC playback path the built-in player uses (run headless, frames into memory).
 */
class PlaybackFeaturesTest {
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
        System.setProperty("cs.subtitles.addon", "$base/stremio/subs")
    }

    @AfterTest
    fun stop() {
        server.destroy()
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
    }

    private fun demo() = PluginManager.loadPlugin(
        File(System.getProperty("demo.repo"), "Demo.cs3"),
        PluginData("Demo", null, false, "Demo.cs3", 1),
    ).getOrThrow().providers.single()

    private val token = mapOf("X-CS-Token" to "demo")

    @Test
    fun `proxy adds headers to the playlist, its variants and every segment`() = runBlocking {
        // Without the header the host refuses, like a token-protected stream host.
        assertEquals(403, app.get("$base/hls/master.m3u8").code)

        val master = app.get(StreamProxy.wrap("$base/hls/master.m3u8", token)).text
        val variantUrl = master.lines().single { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(variantUrl.startsWith("http://127.0.0.1:"), "variant not proxied: $variantUrl")

        val variant = app.get(variantUrl).text
        val segments = variant.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(12, segments.size)
        assertTrue(segments.all { it.startsWith("http://127.0.0.1:") && it.endsWith(".ts") })
        val segment = app.get(segments.first())
        assertEquals(200, segment.code)
        assertTrue(segment.body.bytes().size > 10_000)
    }

    @Test
    fun `proxy passes Range through for seeking`() = runBlocking {
        val url = StreamProxy.wrap("$base/download/night_of_the_test_pattern/test_film_640.mp4", mapOf("Referer" to "$base/"))
        val part = app.get(url, headers = mapOf("Range" to "bytes=100-199"))
        assertEquals(206, part.code)
        assertEquals(100, part.body.bytes().size)
        assertEquals("bytes 100-199/536122", part.headers["Content-Range"])
    }

    @Test
    fun `playlist rewriting resolves relative URIs and key attributes`() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nseg 1.ts\n#EXTINF:4,\n/abs/seg2.ts\n"
        val out = StreamProxy.rewrite(text, "https://cdn.example/path/index.m3u8", token).lines()
        assertTrue(out[1].startsWith("#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:"))
        assertTrue(out[3].startsWith("http://127.0.0.1:") && out[3].endsWith("/seg%201.ts"), out[3])
        assertTrue(out[5].endsWith("/seg2.ts"))
    }

    @Test
    fun `online subtitles are found by title and episode`() = runBlocking {
        val api = demo()
        val response = Engine.load(api, "$base/title/pixel_city")!!
        val query = OnlineSubtitles.query(response, 1, 1)
        assertEquals("tt9000001", OnlineSubtitles.resolveImdb(query))
        val results = OnlineSubtitles.search(query)
        assertEquals(listOf("eng", "eng", "spa", "fre", "ara"), results.map { it.language })
        assertEquals("Spanish", results[2].languageName)
        assertTrue(OnlineSubtitles.matches("eng", "en"))
        assertTrue(OnlineSubtitles.matches("spa", "Spanish"))
        val file = OnlineSubtitles.download(results[2])
        assertTrue(file.readText().contains("Subtítulo en línea"))
    }

    @Test
    fun `catalogs can be hidden and reordered per extension`() = runBlocking {
        val api = demo()
        val all = Catalogs.all(api)
        assertTrue(all.size >= 2, "demo has ${all.size} catalogs")
        try {
            // Reverse the order and turn the (originally) first catalog off.
            val edited = all.reversed().map { if (it.index == 0) it.copy(enabled = false) else it }
            Catalogs.save(api, edited)
            assertEquals(edited.map { it.section.name }, Catalogs.all(api).map { it.section.name })
            assertEquals(edited.filter { it.enabled }.map { it.index }, Catalogs.enabled(api).map { it.index })

            val rows = Engine.home(listOf(api)).toList().sortedBy { it.order }
            assertTrue(rows.none { it.section.name == all[0].section.name }, "hidden catalog was loaded")
            assertEquals(Catalogs.enabled(api).map { it.section.name }, rows.map { it.section.name }.distinct())
        } finally {
            Catalogs.reset(api)
        }
        assertEquals(all.map { it.index }, Catalogs.all(api).map { it.index })
    }

    @Test
    fun `libVLC plays a header-protected HLS stream through the proxy with subtitles`() {
        if (!NativeDiscovery().discover()) {
            println("libVLC not installed; skipping")
            return
        }
        val factory = MediaPlayerFactory("--quiet", "--no-xlib", "--aout=dummy", "--no-video-title-show")
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val frames = AtomicInteger()
        val width = AtomicInteger()
        val time = AtomicLong()
        val playing = CountDownLatch(1)
        val subtitleTracks = AtomicInteger()
        player.videoSurface().set(CallbackVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(w: Int, h: Int) = BufferFormat("BGRA", w, h, intArrayOf(w * 4), intArrayOf(h)).also { width.set(w) }
                override fun newFormatSize(bw: Int, bh: Int, dw: Int, dh: Int) = Unit
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit
            },
            object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer) = Unit
                override fun unlock(mediaPlayer: MediaPlayer) = Unit
                override fun display(mediaPlayer: MediaPlayer, b: Array<ByteBuffer>, f: BufferFormat, dw: Int, dh: Int) { frames.incrementAndGet() }
            },
            true, VideoSurfaceAdapters.getVideoSurfaceAdapter(),
        ))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = time.set(newTime)
        })
        try {
            val url = StreamProxy.wrap("$base/hls/master.m3u8", token)
            assertTrue(player.media().play(url, ":network-caching=500"))
            assertTrue(playing.await(20, TimeUnit.SECONDS), "never started playing")
            val subtitle = File.createTempFile("cs-test", ".srt").apply {
                writeText("1\n00:00:00,000 --> 00:01:00,000\nHello\n")
                deleteOnExit()
            }
            Thread.sleep(1500)
            player.media().addSlave(MediaSlaveType.SUBTITLE, subtitle.toPath().toUri().toString(), true)
            val deadline = System.currentTimeMillis() + 15_000
            while ((frames.get() < 25 || time.get() < 1500 || subtitleTracks.get() == 0) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                subtitleTracks.set(player.subpictures().trackCount())
            }
            assertTrue(frames.get() >= 25, "only ${frames.get()} frames")
            assertTrue(time.get() >= 1500, "time stuck at ${time.get()}")
            assertEquals(480, width.get())
            assertTrue(subtitleTracks.get() >= 1, "subtitle file not added")
            // Seeking (what the player's seek bar and source switching do).
            player.controls().setTime(60_000)
            Thread.sleep(2500)
            assertTrue(time.get() >= 59_000, "seek landed at ${time.get()}")
        } finally {
            player.controls().stop()
            player.release()
            factory.release()
        }
    }
}
