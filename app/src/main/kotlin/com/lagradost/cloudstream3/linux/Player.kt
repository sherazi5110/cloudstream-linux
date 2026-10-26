package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File

/**
 * Android hands an ExtractorLink to ExoPlayer with its headers, referer, subtitles
 * and extra audio tracks. On Linux the same data is passed to an external player:
 * mpv (preferred; HLS/DASH/headers/subs via ffmpeg), VLC or ffplay.
 */
object Player {
    private const val PLAYER_KEY = "linux_player"
    val supported = listOf("mpv", "vlc", "ffplay")

    fun installed(): List<String> = supported.filter { onPath(it) }

    fun onPath(binary: String): Boolean =
        System.getenv("PATH").orEmpty().split(':').any { File(it, binary).canExecute() }

    var preferred: String
        get() = Platform.context.getSharedPrefs().getString(PLAYER_KEY, null) ?: installed().firstOrNull() ?: "mpv"
        set(value) = Platform.context.getSharedPrefs().edit().putString(PLAYER_KEY, value).apply()

    fun command(
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        title: String,
        player: String = preferred,
    ): List<String> {
        val headers = link.getAllHeaders()
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: USER_AGENT
        val otherHeaders = headers.filterKeys { !it.equals("referer", true) && !it.equals("user-agent", true) }
        val urls = (link as? ExtractorLinkPlayList)?.playlist?.map { it.url } ?: listOf(link.url)

        if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) {
            check(onPath("webtorrent")) { "Torrent links need webtorrent-cli (npm i -g webtorrent-cli)" }
            return listOf("webtorrent", link.url, "--mpv")
        }

        return when (player) {
            "mpv" -> buildList {
                add("mpv")
                add("--force-media-title=$title")
                add("--user-agent=$userAgent")
                referer?.let { add("--referrer=$it") }
                otherHeaders.forEach { (k, v) -> add("--http-header-fields-append=$k: $v") }
                subtitles.forEach { add("--sub-file-append=${it.url}") }
                link.audioTracks.forEach { add("--audio-file-append=${it.url}") }
                if (urls.size > 1) add("--merge-files")
                addAll(urls)
            }
            "vlc" -> buildList {
                add("vlc")
                add("--meta-title=$title")
                add("--http-user-agent=$userAgent")
                referer?.let { add("--http-referrer=$it") }
                subtitles.firstOrNull()?.let { add("--sub-file=${it.url}") }
                addAll(urls)
            }
            "ffplay" -> buildList {
                add("ffplay")
                add("-window_title"); add(title)
                add("-user_agent"); add(userAgent)
                val headerBlock = (otherHeaders + listOfNotNull(referer?.let { "Referer" to it }))
                    .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                if (headerBlock.isNotEmpty()) { add("-headers"); add(headerBlock) }
                add(urls.first())
            }
            else -> error("Unknown player $player")
        }
    }

    fun play(link: ExtractorLink, subtitles: List<SubtitleFile>, title: String, player: String = preferred): Process {
        val cmd = command(link, subtitles, title, player)
        check(onPath(cmd.first())) { "${cmd.first()} is not installed (sudo apt install ${cmd.first()})" }
        return ProcessBuilder(cmd).inheritIO().start()
    }
}
