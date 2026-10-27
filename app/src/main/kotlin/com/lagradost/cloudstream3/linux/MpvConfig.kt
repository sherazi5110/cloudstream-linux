package com.lagradost.cloudstream3.linux

import java.io.File

/**
 * The player's private mpv config dir (uosc UI, fonts, mpv.conf, input.conf), extracted
 * from the app's resources. It is separate from ~/.config/mpv so personal mpv setups
 * don't break the embedded player, and vice versa.
 */
object MpvConfig {
    private const val REVISION = "uosc-5.13.0/cs-9"

    val dir: File get() = File(AppDirs.data, "mpv")

    fun install(): File {
        val marker = File(dir, ".installed")
        if (marker.exists() && marker.readText() == REVISION) return dir
        val index = MpvConfig::class.java.getResourceAsStream("/mpv/index.txt")?.bufferedReader()?.readLines()
            ?: error("player resources missing from the build")
        File(dir, "scripts").deleteRecursively()
        for (path in index.filter { it.isNotBlank() }) {
            val target = File(dir, path)
            target.parentFile.mkdirs()
            MpvConfig::class.java.getResourceAsStream("/mpv/$path")!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        marker.writeText(REVISION)
        return dir
    }
}
