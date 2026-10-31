package io.github.sherazi5110.cloudstream.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.LinkEvent
import io.github.sherazi5110.cloudstream.Playable
import io.github.sherazi5110.cloudstream.Settings
import io.github.sherazi5110.cloudstream.StreamProxy
import io.github.sherazi5110.cloudstream.WatchEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/** The one libVLC instance (it is heavy to create). */
object Vlc {
    val lib: LibVLC by lazy {
        LibVLC(App.context, arrayListOf(
            "--http-reconnect",
            "--network-caching=3000",
            "--audio-time-stretch", // keeps the pitch at other speeds
            "--subsdec-encoding=UTF-8",
            "--freetype-rel-fontsize=16",
            "--freetype-color=16777215",
            "--freetype-outline-thickness=4",
        ))
    }
}

/** Zoom modes, the Linux player's names. */
enum class Zoom(val label: String, val scale: MediaPlayer.ScaleType) {
    FIT("Fit", MediaPlayer.ScaleType.SURFACE_BEST_FIT),
    FILL("Fill", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN),
    STRETCH("Stretch", MediaPlayer.ScaleType.SURFACE_FILL),
    ORIGINAL("Original", MediaPlayer.ScaleType.SURFACE_ORIGINAL),
    WIDE("16:9", MediaPlayer.ScaleType.SURFACE_16_9),
    TV("4:3", MediaPlayer.ScaleType.SURFACE_4_3),
}

/**
 * One playback: finds sources (streamed in as the extension reports them), starts the best one,
 * falls back to the next source when one fails, and records progress for Continue Watching.
 * State is Compose state, so the screen simply reads it.
 */
class PlayerController(
    val api: MainAPI,
    val response: LoadResponse,
    val playable: Playable,
    chooseSource: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    val player = MediaPlayer(Vlc.lib)

    val links = mutableStateListOf<ExtractorLink>()
    val subtitles = mutableStateListOf<SubtitleFile>()
    var searching by mutableStateOf(true)
    var searchError by mutableStateOf<String?>(null)
    var current by mutableStateOf<ExtractorLink?>(null)
    var started by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var zoom by mutableStateOf(runCatching { Zoom.valueOf(Settings.playerZoom) }.getOrDefault(Zoom.FIT))
    var selectedSubtitle by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false)

    /** Auto-play waits for this; "Choose source" turns it off. */
    var autoPlay = Settings.autoPlay && !chooseSource
        private set
    private val tried = mutableSetOf<String>()
    private var startAt: Long = 0
    private var grace: Job? = null
    private var saver: Job? = null
    val isLive = response is LiveStreamLoadResponse

    val playables = Engine.playables(response)
    val next: Playable? = playables.indexOfFirst { it.data == playable.data && it.dub == playable.dub }
        .takeIf { it >= 0 }?.let { playables.getOrNull(it + 1) }

    init {
        Library.progress(api.name, playable.data)?.let { p ->
            if (p.duration > 0 && p.position / p.duration in 0.02..0.92) startAt = (p.position * 1000).toLong()
        }
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; buffering = false; started = true; error = null }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                MediaPlayer.Event.TimeChanged -> { time = e.timeChanged; if (buffering && playing) buffering = false }
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EndReached -> { playing = false; ended = true; save(finished = true) }
                MediaPlayer.Event.EncounteredError -> main.post { failover() }
            }
        }
        scope.launch { findSources() }
        saver = scope.launch {
            while (true) { delay(10_000); if (playing) save() }
        }
    }

    private suspend fun findSources() {
        Engine.links(api, playable.data).collect { event ->
            when (event) {
                is LinkEvent.Link -> {
                    val link = event.link
                    if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT) return@collect
                    if (links.none { it.url == link.url }) links.add(link)
                    onNewLink()
                }
                // Added to the player when picked in the Subtitles menu.
                is LinkEvent.Subtitle -> if (subtitles.none { it.url == event.subtitle.url }) subtitles.add(event.subtitle)
                is LinkEvent.Done -> {
                    searching = false
                    event.error?.let { searchError = it.message ?: it.toString() }
                    if (autoPlay && current == null) ranked().firstOrNull()?.let { play(it) }
                    if (links.isEmpty()) searchError = searchError ?: "No sources found"
                }
            }
        }
    }

    /** Best first: closest to the preferred quality without going over it. */
    fun ranked(): List<ExtractorLink> = Engine.rank(links.toList())

    private fun onNewLink() {
        if (!autoPlay || current != null) return
        val preferred = Settings.preferredQuality
        // A source at the preferred quality starts at once; otherwise wait a few seconds for better ones.
        ranked().firstOrNull { preferred > 0 && it.quality >= preferred }?.let { play(it); return }
        if (grace == null) grace = scope.launch {
            delay(4_000)
            if (current == null && autoPlay) ranked().firstOrNull()?.let { play(it) }
        }
    }

    fun stopAutoPlay() { autoPlay = false; grace?.cancel() }

    /** Starts [link], keeping the current position when switching sources mid-playback. */
    fun play(link: ExtractorLink) {
        grace?.cancel()
        val keep = if (started) player.time.takeIf { it > 0 } ?: startAt else startAt
        current = link
        tried += link.url
        error = null
        ended = false
        buffering = true
        val headers = buildMap {
            putAll(link.headers)
            if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", link.referer)
        }
        val needsProxy = headers.keys.any { !it.equals("Referer", true) && !it.equals("User-Agent", true) }
        val url = if (needsProxy) StreamProxy.wrap(link.url, headers) else link.url
        val media = Media(Vlc.lib, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            if (!needsProxy) {
                addOption(":http-user-agent=" + (headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT))
                headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { addOption(":http-referrer=${it.value}") }
            }
            if (keep > 0) addOption(":start-time=${keep / 1000.0}")
        }
        player.media = media
        media.release()
        player.play()
        player.rate = speed
        player.videoScale = zoom.scale
        selectedSubtitle?.let { url -> subtitles.firstOrNull { it.url == url }?.let { addSubtitle(it) } }
    }

    /** A source failed: try the next untried one, like the Linux player. */
    private fun failover() {
        val next = ranked().firstOrNull { it.url !in tried }
        if (next != null) {
            App.toast("${current?.name ?: "Source"} failed, trying ${next.name}")
            play(next)
        } else {
            error = "This source can't be played. Pick another one in Sources."
            buffering = false
        }
    }

    fun togglePause() = if (player.isPlaying) player.pause() else player.play()

    fun seekBy(ms: Long) = seekTo((player.time + ms).coerceIn(0, length.takeIf { it > 0 } ?: Long.MAX_VALUE))

    fun seekTo(ms: Long) {
        player.setTime(ms)
        time = ms
    }

    fun setRate(rate: Float) {
        speed = rate
        player.rate = rate
    }

    fun setZoomMode(z: Zoom) {
        zoom = z
        player.videoScale = z.scale
        Settings.playerZoom = z.name
    }

    fun audioTracks(): List<MediaPlayer.TrackDescription> = player.audioTracks?.toList().orEmpty()
    fun audioTrack(): Int = player.audioTrack
    fun setAudio(id: Int) { player.setAudioTrack(id) }

    /** Embedded subtitle tracks (id -1 = off) plus the extension's subtitle files. */
    fun spuTracks(): List<MediaPlayer.TrackDescription> = player.spuTracks?.toList().orEmpty()
    fun spuTrack(): Int = player.spuTrack
    fun setSpu(id: Int) { selectedSubtitle = null; player.setSpuTrack(id) }

    fun addSubtitle(s: SubtitleFile) {
        selectedSubtitle = s.url
        val url = s.headers?.takeIf { it.isNotEmpty() }?.let { StreamProxy.wrap(s.url, it) } ?: s.url
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
    }

    fun attach(layout: VLCVideoLayout) {
        // TextureView, so pinch-to-zoom (a view transform) also scales the picture.
        player.attachViews(layout, null, true, true)
        player.videoScale = zoom.scale
    }

    fun detach() = player.detachViews()

    /** Records progress for Continue Watching and the episode list. */
    fun save(finished: Boolean = false) {
        if (isLive || !started) return
        val len = length.takeIf { it > 0 } ?: return
        val pos = if (finished) len else player.time
        Library.record(WatchEntry(
            api.name, response.url, response.name, response.posterUrl, playable.data,
            if (playables.size > 1) playable.label else null, pos / 1000.0, len / 1000.0, System.currentTimeMillis(),
        ))
    }

    fun release() {
        save()
        scope.cancel()
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
    }
}
