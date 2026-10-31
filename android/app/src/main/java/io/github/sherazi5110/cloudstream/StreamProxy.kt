package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.app
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback HTTP proxy for the player (Android port of the Linux app's StreamProxy).
 *
 * libVLC only sends a User-Agent and a Referer, but extensions often need Origin, cookies or
 * token headers on every request. The player opens `http://127.0.0.1:<port>/s/<id>/<name>`
 * instead; the proxy adds the extension's headers, passes Range through for seeking, and
 * rewrites HLS playlists so every variant playlist, segment and key goes through it too.
 */
object StreamProxy {
    private data class Target(val url: String, val headers: Map<String, String>)

    private val targets = ConcurrentHashMap<String, Target>()
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }

    private val skipResponse = setOf("transfer-encoding", "connection", "content-encoding", "content-length", "keep-alive")

    @Synchronized
    private fun start(): ServerSocket = server ?: ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")).also { s ->
        server = s
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { socket.use { handle(it) } }
            }
        }
    }

    /** Returns a loopback URL that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>): String {
        val s = start()
        val id = id(url, headers)
        targets[id] = Target(url, headers)
        // Keep the original file name/extension: the player uses it to guess the format.
        val name = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
            .ifBlank { "stream" }.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:${s.localPort}/s/$id/$name"
    }

    private fun id(url: String, headers: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((url + "\n" + headers.toSortedMap()).toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "GET") to (it.getOrNull(1) ?: "/") }
        var range: String? = null
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val id = path.removePrefix("/s/").substringBefore('/')
        val target = targets[id] ?: return respond(out, 404, emptyList(), null)
        val headers = target.headers.toMutableMap()
        range?.let { headers["Range"] = it }
        val request = Request.Builder().url(target.url).headers(headers.toHeaders())
            .apply { if (method == "HEAD") head() }
            .build()
        try {
            app.baseClient.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                val copied = response.headers.filter { (n, _) -> n.lowercase() !in skipResponse }
                if (method != "HEAD" && isPlaylist(target.url, type)) {
                    val text = response.body.string()
                    if (text.trimStart().startsWith("#EXTM3U")) {
                        val bytes = rewrite(text, response.request.url.toString(), target.headers).toByteArray()
                        respond(out, response.code, listOf("Content-Type" to "application/vnd.apple.mpegurl"), bytes)
                    } else respond(out, response.code, copied, text.toByteArray())
                    return
                }
                val length = response.header("Content-Length")
                val head = buildString {
                    append("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
                    copied.forEach { (n, v) -> append("$n: $v\r\n") }
                    length?.let { append("Content-Length: $it\r\n") }
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                if (method != "HEAD") response.body.byteStream().use { it.copyTo(out, 64 * 1024) }
                out.flush()
            }
        } catch (t: Throwable) {
            // The player closing a connection mid-stream (seek, stop) lands here too.
            runCatching { respond(out, 502, emptyList(), null) }
        }
    }

    private fun respond(out: OutputStream, code: Int, headers: List<Pair<String, String>>, body: ByteArray?) {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\n")
            headers.forEach { (n, v) -> append("$n: $v\r\n") }
            append("Content-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        body?.let { out.write(it) }
        out.flush()
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val type = contentType.lowercase()
        return "mpegurl" in type || runCatching { URI(url).path.lowercase().endsWith(".m3u8") }.getOrDefault(false)
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /** Points every URI in an HLS playlist (segments, variants, keys, maps) back at the proxy. */
    fun rewrite(playlist: String, baseUrl: String, headers: Map<String, String>): String {
        val base = URI(baseUrl)
        fun proxied(ref: String) = wrap(base.resolve(ref.trim().replace(" ", "%20")).toString(), headers)
        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttribute.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }
}
