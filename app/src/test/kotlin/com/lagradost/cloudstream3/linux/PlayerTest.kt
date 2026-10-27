package com.lagradost.cloudstream3.linux

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The built-in player is mpv with the bundled uosc UI, driven over JSON IPC.
 * These checks run a real headless mpv when it is installed and are skipped otherwise.
 */
class PlayerTest {
    @Test
    fun `bundled mpv config installs uosc and the CloudStream bindings`() {
        val dir = MpvConfig.install()
        assertTrue(dir.resolve("scripts/uosc/main.lua").isFile, "uosc script missing")
        assertTrue(dir.resolve("fonts/uosc_icons.otf").isFile, "uosc icon font missing")
        val input = dir.resolve("input.conf").readText()
        for (message in listOf("cs-back", "cs-sources", "cs-load-sub", "cs-settings", "cs-next")) {
            assertTrue(message in input, "input.conf lacks $message")
        }
        assertTrue("cs-sources" in dir.resolve("script-opts/uosc.conf").readText())
    }

    @Test
    fun `uosc stays alive and opens the sources menu over IPC`() = runBlocking {
        val mpv = listOf("/usr/bin/mpv", "/usr/local/bin/mpv").firstOrNull { File(it).canExecute() }
        if (mpv == null) {
            println("mpv not installed; skipping")
            return@runBlocking
        }
        val config = MpvConfig.install()
        val socket = Files.createTempDirectory("cs-mpv-test").resolve("mpv.sock")
        val process = ProcessBuilder(
            mpv, "--config-dir=$config", "--input-ipc-server=$socket",
            "--idle=yes", "--vo=null", "--ao=null", "--force-window=no", "--msg-level=all=warn",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val ipc = assertNotNull(MpvIpc.connect(socket, process), "mpv IPC socket never appeared")
            ipc.use {
                assertEquals(true, it.get("idle-active"))
                // Wait for uosc to finish starting, then open a menu the way PlayerSession does.
                var opened: Any? = null
                repeat(30) { attempt ->
                    if (opened == "cs-sources") return@repeat
                    if (attempt % 5 == 0) {
                        it.scriptMessageTo("uosc", "open-menu",
                            """{"type":"cs-sources","title":"Sources (1)","items":[{"title":"Host 1080p","value":["script-message","cs-play-source","0"]}]}""")
                    }
                    delay(200)
                    opened = it.get("user-data/uosc/menu/type")
                }
                assertEquals("cs-sources", opened, "uosc did not open the menu")
                // A crashed script makes script-message-to fail; it must still answer later.
                delay(1500)
                val reply = it.request("script-message-to", "uosc", "update-menu",
                    """{"type":"cs-sources","title":"Sources (2)","items":[]}""")
                assertEquals("success", reply?.optString("error"), "uosc stopped responding: $reply")
            }
        } finally {
            process.destroy()
        }
    }
}
