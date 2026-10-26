package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/** A running player. For mpv, [track] follows the playback position over its JSON IPC socket. */
class PlaybackSession(val process: Process, private val ipc: Path?) {
    /**
     * Reports (position, duration) every second and once more when the player
     * closes, so history survives crashes too. Returns the last known values.
     */
    suspend fun track(onProgress: (Double, Double) -> Unit): Pair<Double, Double> = withContext(Dispatchers.IO) {
        var position = 0.0
        var duration = 0.0
        val channel = ipc?.let { connect(it) }
        if (channel == null) {
            process.waitFor()
            return@withContext position to duration
        }
        channel.use {
            val writer = Channels.newOutputStream(channel).bufferedWriter()
            val reader = Channels.newInputStream(channel).bufferedReader()
            try {
                while (process.isAlive) {
                    writer.write("""{"command":["get_property","time-pos"],"request_id":1}""" + "\n")
                    writer.write("""{"command":["get_property","duration"],"request_id":2}""" + "\n")
                    writer.flush()
                    var answered = 0
                    while (answered < 2) {
                        val line = reader.readLine() ?: break
                        val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (!json.has("request_id")) continue // an event, not our reply
                        answered++
                        val value = json.optDouble("data", Double.NaN)
                        if (value.isNaN()) continue
                        when (json.getInt("request_id")) {
                            1 -> position = value
                            2 -> duration = value
                        }
                    }
                    if (duration > 0) onProgress(position, duration)
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
                // mpv closed the socket while quitting; keep the last values.
            }
        }
        process.waitFor()
        runCatching { Files.deleteIfExists(ipc) }
        if (duration > 0) onProgress(position, duration)
        position to duration
    }

    private fun connect(path: Path): SocketChannel? {
        repeat(100) {
            if (!process.isAlive) return null
            if (Files.exists(path)) {
                runCatching {
                    return SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path)) }
                }
            }
            Thread.sleep(100)
        }
        return null
    }
}

/**
 * Android hands an ExtractorLink to ExoPlayer with its headers, referer, subtitles and
 * extra audio tracks. On Linux the same data goes to mpv (preferred: hardware decoding,
 * HLS/DASH, headers), VLC or ffplay.
 */
object Player {
    val supported = listOf("mpv", "vlc", "ffplay")

    fun installed(): List<String> = supported.filter { onPath(it) }

    fun onPath(binary: String): Boolean =
        System.getenv("PATH").orEmpty().split(':').any { File(it, binary).canExecute() }

    val preferred: String get() = Settings.player

    fun command(
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        title: String,
        player: String = preferred,
        start: Double? = null,
        ipc: Path? = null,
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
                add("--force-window=immediate")
                add("--hwdec=auto-safe")
                add("--force-media-title=$title")
                add("--user-agent=$userAgent")
                referer?.let { add("--referrer=$it") }
                otherHeaders.forEach { (k, v) -> add("--http-header-fields-append=$k: $v") }
                subtitles.forEach { add("--sub-file-append=${it.url}") }
                link.audioTracks.forEach { add("--audio-file-append=${it.url}") }
                if (start != null && start > 5) add("--start=${start.toLong()}")
                ipc?.let { add("--input-ipc-server=$it") }
                if (urls.size > 1) add("--merge-files")
                addAll(Settings.mpvArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
                addAll(urls)
            }
            "vlc" -> buildList {
                add("vlc")
                add("--meta-title=$title")
                add("--http-user-agent=$userAgent")
                referer?.let { add("--http-referrer=$it") }
                subtitles.firstOrNull()?.let { add("--sub-file=${it.url}") }
                if (start != null && start > 5) add("--start-time=${start.toLong()}")
                addAll(urls)
            }
            "ffplay" -> buildList {
                add("ffplay")
                add("-window_title"); add(title)
                add("-user_agent"); add(userAgent)
                val headerBlock = (otherHeaders + listOfNotNull(referer?.let { "Referer" to it }))
                    .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                if (headerBlock.isNotEmpty()) { add("-headers"); add(headerBlock) }
                if (start != null && start > 5) { add("-ss"); add(start.toLong().toString()) }
                add(urls.first())
            }
            else -> error("Unknown player $player")
        }
    }

    fun play(
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        title: String,
        player: String = preferred,
        start: Double? = null,
    ): PlaybackSession {
        val ipc = if (player == "mpv") Files.createTempFile("cs-mpv-", ".sock").also { Files.delete(it) } else null
        val cmd = command(link, subtitles, title, player, start, ipc)
        check(onPath(cmd.first())) { "${cmd.first()} is not installed. Install it with: sudo apt install ${cmd.first()}" }
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(File(AppDirs.data, "logs/player.log").apply { parentFile.mkdirs() })
            .start()
        return PlaybackSession(process, ipc)
    }
}
