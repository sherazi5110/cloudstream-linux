package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback HTTP proxy for the built-in player.
 *
 * libVLC's HTTP access and its HLS/DASH module only send a User-Agent and a Referer
 * (modules/access/http/access.c, modules/demux/adaptive/http/HTTPConnection.cpp), but
 * extensions often need Origin, cookies or token headers on every request. The player
 * opens `http://127.0.0.1:<port>/s/<id>` instead; the proxy adds the extension's headers,
 * passes Range through for seeking, and rewrites HLS playlists so every variant playlist,
 * segment and key also goes through the proxy with the same headers.
 */
object StreamProxy {
    private data class Target(val url: String, val headers: Map<String, String>)

    private val targets = ConcurrentHashMap<String, Target>()
    @Volatile private var server: HttpServer? = null

    /** Hop-by-hop or recomputed headers that must not be copied between the two sides. */
    private val skipResponse = setOf("transfer-encoding", "connection", "content-encoding", "content-length", "keep-alive")

    @Synchronized
    private fun start(): HttpServer = server ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64).also {
        it.createContext("/s/") { exchange -> handle(exchange) }
        // Players open several connections at once (playlist, segments, a seek).
        it.executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
        it.start()
        server = it
    }

    /** Returns a loopback URL that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>): String {
        val s = start()
        val id = id(url, headers)
        targets[id] = Target(url, headers)
        // Keep the original file name/extension: players use it to guess the format.
        val name = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
            .ifBlank { "stream" }.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:${s.address.port}/s/$id/$name"
    }

    /** Stable ids, so rewriting the same live playlist again doesn't grow the table. */
    private fun id(url: String, headers: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((url + "\n" + headers.toSortedMap()).toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val id = exchange.requestURI.path.removePrefix("/s/").substringBefore('/')
            val target = targets[id] ?: return exchange.sendResponseHeaders(404, -1)
            val headers = target.headers.toMutableMap()
            exchange.requestHeaders.getFirst("Range")?.let { headers["Range"] = it }
            val request = Request.Builder().url(target.url).headers(headers.toHeaders())
                .apply { if (exchange.requestMethod == "HEAD") head() }
                .build()
            try {
                app.baseClient.newCall(request).execute().use { response ->
                    val body = response.body
                    val type = response.header("Content-Type").orEmpty()
                    if (exchange.requestMethod != "HEAD" && isPlaylist(target.url, type)) {
                        val text = body.string()
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            val bytes = rewrite(text, response.request.url.toString(), target.headers).toByteArray()
                            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                            exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                            return
                        }
                        val bytes = text.toByteArray()
                        copyHeaders(response, exchange)
                        exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                        return
                    }
                    copyHeaders(response, exchange)
                    val length = response.header("Content-Length")?.toLongOrNull()
                    if (exchange.requestMethod == "HEAD") {
                        length?.let { exchange.responseHeaders.add("Content-Length", it.toString()) }
                        exchange.sendResponseHeaders(response.code, -1)
                        return
                    }
                    // 0 means chunked when the length is unknown.
                    exchange.sendResponseHeaders(response.code, length ?: 0)
                    body.byteStream().use { input -> input.copyTo(exchange.responseBody, 64 * 1024) }
                }
            } catch (t: Throwable) {
                // The player closing a connection mid-stream (seek, stop) lands here too.
                runCatching { exchange.sendResponseHeaders(502, -1) }
            }
        }
    }

    private fun copyHeaders(response: okhttp3.Response, exchange: HttpExchange) {
        response.headers.forEach { (name, value) ->
            if (name.lowercase() !in skipResponse) exchange.responseHeaders.add(name, value)
        }
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
