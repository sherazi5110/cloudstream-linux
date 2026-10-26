package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompatibilityTest {
    private val repo = File(System.getProperty("example.repo"))

    init {
        File(System.getProperty("cloudstream.home")).deleteRecursively()
        Platform.init()
    }

    @AfterTest
    fun unloadAll() {
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
    }

    private fun load(file: File) = PluginManager.loadPlugin(
        file, PluginData(file.nameWithoutExtension, null, false, file.absolutePath, 1)
    ).getOrThrow()

    @Test
    fun `cs3 really contains Dalvik bytecode`() {
        ZipFile(repo.resolve("InternetArchive.cs3")).use { zip ->
            val magic = zip.getInputStream(zip.getEntry("classes.dex")).readNBytes(4).decodeToString()
            assertEquals("dex\n", magic)
            assertTrue(zip.getEntry("manifest.json") != null)
        }
    }

    @Test
    fun `cs3 is translated and registers its provider through Plugin_load(Context)`() {
        val plugin = load(repo.resolve("InternetArchive.cs3"))
        assertEquals("com.cloudstreamlinux.archive.InternetArchivePlugin", plugin.manifest.pluginClassName)
        assertEquals(listOf("Internet Archive"), plugin.providers.map { it.name })
        assertTrue(APIHolder.allProviders.any { it.name == "Internet Archive" })
    }

    @Test
    fun `cross-platform jar loads by annotation scan and unloads cleanly`() {
        val jar = repo.resolve("InternetArchive.jar")
        val plugin = load(jar)
        assertEquals("com.cloudstreamlinux.archive.InternetArchivePlugin", plugin.manifest.pluginClassName)
        PluginManager.unloadPlugin(jar.absolutePath)
        assertTrue(APIHolder.allProviders.none { it.sourcePlugin == jar.absolutePath })
    }

    @Test
    fun `extensions read settings through the Android key store API`() {
        CloudStreamApp.setKey(INTERNET_ARCHIVE_KEY, "http://example.invalid")
        val plugin = load(repo.resolve("InternetArchive.cs3"))
        assertEquals("http://example.invalid", plugin.providers.single().mainUrl)
        CloudStreamApp.removeKey(INTERNET_ARCHIVE_KEY)
    }

    @Test
    fun `repository urls are parsed like the Android app`() = runBlocking {
        assertEquals("https://example.com/repo.json", RepositoryManager.parseRepoUrl("cloudstreamrepo://example.com/repo.json"))
        assertEquals("https://example.com/repo.json", RepositoryManager.parseRepoUrl(" https://example.com/repo.json "))
        assertEquals(null, RepositoryManager.parseRepoUrl("not a url"))
    }

    @Test
    fun `mpv receives headers, referer, subtitles and audio tracks`() = runBlocking {
        val link = newExtractorLink("src", "name", "https://cdn.example/v.m3u8", ExtractorLinkType.M3U8) {
            referer = "https://site.example/"
            headers = mapOf("X-Token" to "abc,def")
        }
        val subs = listOf(newSubtitleFile("en", "https://cdn.example/en.vtt"))
        val cmd = Player.command(link, subs, "Title", "mpv")
        assertEquals("mpv", cmd.first())
        assertTrue("--referrer=https://site.example/" in cmd)
        assertTrue("--http-header-fields-append=X-Token: abc,def" in cmd)
        assertTrue("--sub-file-append=https://cdn.example/en.vtt" in cmd)
        assertEquals("https://cdn.example/v.m3u8", cmd.last())
    }

    private companion object {
        const val INTERNET_ARCHIVE_KEY = "internet_archive_base_url"
    }
}

class AndroidApiTest {
    @Test
    fun `Base64 matches Android flag semantics`() {
        val bytes = "hello?>world".toByteArray()
        assertEquals("aGVsbG8/PndvcmxkCg==".let { android.util.Base64.decode(it, 0).decodeToString() }, "hello?>world\n")
        assertEquals("aGVsbG8_PndvcmxkCg", android.util.Base64.encodeToString("hello?>world\n".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        assertEquals("aGVsbG8/Pndvcmxk\n", android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT))
        assertEquals("hello?>world", android.util.Base64.decode("aGVsbG8_Pndvcmxk", android.util.Base64.URL_SAFE).decodeToString())
    }

    @Test
    fun `Uri picks urls apart like Android`() {
        val uri = android.net.Uri.parse("https://cdn.example.com/v/abc/master.m3u8?token=a%20b&e=1")
        assertEquals("cdn.example.com", uri.getHost())
        assertEquals("master.m3u8", uri.getLastPathSegment())
        assertEquals("a b", uri.getQueryParameter("token"))
        assertEquals(listOf("v", "abc", "master.m3u8"), uri.getPathSegments())
        assertEquals("a%20b%2Fc", android.net.Uri.encode("a b/c"))
    }
}
