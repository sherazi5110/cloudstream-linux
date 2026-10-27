package com.lagradost.cloudstream3.linux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * mpv's JSON IPC protocol over its --input-ipc-server socket: commands, property
 * observation and events (including `client-message` from uosc menu buttons).
 */
class MpvIpc private constructor(private val channel: SocketChannel) : Closeable {
    private val writer = Channels.newOutputStream(channel).bufferedWriter()
    private val ids = AtomicInteger(100)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    val events: SharedFlow<JSONObject> = _events
    @Volatile var closed = false
        private set

    init {
        Thread({
            val reader = Channels.newInputStream(channel).bufferedReader()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val id = json.optInt("request_id", -1)
                    if (id >= 0 && json.has("error") && !json.has("event")) pending.remove(id)?.complete(json)
                    else _events.tryEmit(json)
                }
            } catch (_: Exception) {
            } finally {
                closed = true
                pending.values.forEach { it.complete(JSONObject().put("error", "closed")) }
                _events.tryEmit(JSONObject().put("event", "ipc-closed"))
            }
        }, "mpv-ipc").apply { isDaemon = true }.start()
    }

    private fun send(json: JSONObject) {
        if (closed) return
        synchronized(writer) {
            runCatching {
                writer.write(json.toString())
                writer.write("\n")
                writer.flush()
            }
        }
    }

    /** Fire-and-forget command, e.g. command("seek", 10). */
    fun command(vararg args: Any?) = send(JSONObject().put("command", JSONArray(args.toList())))

    /** Command with a reply; null on timeout or when mpv has gone. */
    suspend fun request(vararg args: Any?): JSONObject? {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        send(JSONObject().put("command", JSONArray(args.toList())).put("request_id", id))
        return withTimeoutOrNull(3000) { reply.await() }.also { pending.remove(id) }
    }

    suspend fun get(property: String): Any? = request("get_property", property)?.opt("data")

    fun set(property: String, value: Any?) = command("set_property", property, value)

    fun observe(id: Int, property: String) = command("observe_property", id, property)

    fun scriptMessageTo(target: String, vararg args: String) = command("script-message-to", target, *args)

    override fun close() {
        closed = true
        runCatching { channel.close() }
    }

    companion object {
        /** Waits for mpv to create the socket; gives up if the process dies first. */
        fun connect(path: Path, process: Process, timeoutMs: Long = 10_000): MpvIpc? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && process.isAlive) {
                if (Files.exists(path)) {
                    runCatching {
                        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                        channel.connect(UnixDomainSocketAddress.of(path))
                        return MpvIpc(channel)
                    }
                }
                Thread.sleep(50)
            }
            return null
        }
    }
}
