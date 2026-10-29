package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for "stuck on looking for sources": the demo extension has an instant
 * host, a slow host (8 s) and a dead host that never answers, served by tests/mock-archive.
 */
class StreamingTest {
    private val tests = File(System.getProperty("tests.dir"))
    private lateinit var server: Process
    private var port = 0

    @BeforeTest
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder("python3", "-I", tests.resolve("mock-archive/server.py").path, port.toString())
            .redirectErrorStream(true).start()
        repeat(50) {
            if (runCatching { URI("http://127.0.0.1:$port/advancedsearch.php").toURL().readText() }.isSuccess) return@repeat
            Thread.sleep(100)
        }
        Platform.init()
        Network.init()
        CloudStreamApp.setKey("demo_base_url", "http://localhost:$port")
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

    @Test
    fun `sources stream in and a dead host cannot hang the search`() = runBlocking {
        val api = demo()
        val started = System.nanoTime()
        val timeline = mutableListOf<Pair<Long, LinkEvent>>()
        Engine.links(api, "pixel_city|1|1").collect { timeline += (System.nanoTime() - started) / 1_000_000 to it }

        val links = timeline.filter { it.second is LinkEvent.Link }
        val firstLinkMs = links.first().first
        val doneMs = timeline.last().first
        assertTrue(firstLinkMs < 3_000, "first source took ${firstLinkMs}ms")
        assertEquals(listOf("FastHost", "TokenHost", "SlowHost"), links.map { (it.second as LinkEvent.Link).link.source })
        assertTrue(timeline.last().second is LinkEvent.Done)
        assertTrue(doneMs < 65_000, "search ended after ${doneMs}ms")
    }

    @Test
    fun `home sections and search stream per provider`() = runBlocking {
        val api = demo()
        val sections = Engine.home(listOf(api)).toList()
        assertEquals(3, sections.size)
        val batches = Engine.search("pixel", listOf(api)).toList()
        assertEquals(listOf("Pixel City", "His Girl Pixel"), batches.single().results.map { it.name })
    }
}
