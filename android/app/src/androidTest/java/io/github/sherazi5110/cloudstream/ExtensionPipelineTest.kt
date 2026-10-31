package io.github.sherazi5110.cloudstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole extension path on a real device: repository -> .cs3 download -> native load ->
 * home -> search -> title page -> sources. Needs the repository's test servers reachable on the
 * device's localhost:
 *   (cd tests/DemoProvider/build/repo && python3 -m http.server 8765) &
 *   tests/mock-archive/server.py 8766 &
 *   adb reverse tcp:8765 tcp:8765 && adb reverse tcp:8766 tcp:8766
 * then: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExtensionPipelineTest {
    @Test
    fun demoExtensionLoadsAndPlays(): Unit = runBlocking {
        val repo = RepositoryManager.addRepository("http://localhost:8765/repo.json")
        val plugins = RepositoryManager.getRepoPlugins(repo)!!
        val demo = plugins.first { it.plugin.internalName == "Demo" }
        val loaded = PluginManager.install(demo).getOrThrow()
        assertEquals(listOf("Demo"), loaded.providers.map { it.name })

        val api = Engine.provider("Demo")!!
        val sections = Engine.home(listOf(api)).toList()
        assertTrue("home rows", sections.isNotEmpty())

        val results = Engine.search("pixel", listOf(api)).toList().flatMap { it.results }
        assertTrue("search results", results.isNotEmpty())

        val item = sections.first().list.list.first()
        val response = Engine.load(api, item.url)!!
        val playable = Engine.playables(response).first()
        val links = Engine.allLinks(api, playable.data)
        assertTrue("sources for ${response.name}", links.links.isNotEmpty())
        println("I/test: ${sections.size} rows, ${results.size} results, '${response.name}' has ${links.links.size} sources")
    }
}
