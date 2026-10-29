package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.linux.AppDirs
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Library
import com.lagradost.cloudstream3.linux.OnlineSubtitles
import com.lagradost.cloudstream3.linux.Playable
import com.lagradost.cloudstream3.linux.Settings
import com.lagradost.cloudstream3.linux.StreamProxy
import com.lagradost.cloudstream3.linux.WatchEntry
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.Qualities
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.util.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.File
import java.net.URI
import kotlin.math.abs

/** A running player the rest of the app can stop. */
interface ActivePlayer {
    fun quit(then: (() -> Unit)? = null)
}

/** A full-window player that wants every key press, whatever node has focus. */
interface PlayerKeys {
    fun handle(e: KeyEvent)
}

/**
 * The built-in player: libVLC decoding (every format VLC plays, hardware-accelerated
 * when the system supports it) drawn inside the app window, with CloudStream's own
 * controls. Works the same on X11, Wayland/XWayland and ChromeOS, because it never
 * embeds a native window: frames are drawn by JavaFX (see [VideoView]).
 */
class BuiltinPlayer(
    private val api: MainAPI,
    private val response: LoadResponse,
    private val playable: Playable,
    private val sources: SourceCollector,
    private var current: ExtractorLink,
) : StackPane(), ActivePlayer, PlayerKeys {
    private val all = Engine.playables(response)
    private val next = all.getOrNull(all.indexOfFirst { it.data == playable.data } + 1)?.takeIf { it.dub == playable.dub }
    private val title = Playback.title(response, playable, all.size)
    private val video = VideoView()
    private val player: EmbeddedMediaPlayer = Vlc.factory!!.mediaPlayers().newEmbeddedMediaPlayer()

    private var position = 0L
    private var duration = 0L
    private var saved = -100_000L
    private var playing = false
    /** Tracked here: libVLC reports "muted" when there is no audio output yet. */
    private var muted = false
    private var leaving: (() -> Unit)? = null
    private var finished = false
    private val failed = mutableSetOf<String>()
    private var retryWhenAvailable = false
    /** Position to restore after a source switch, applied once the new source can seek. */
    private var pendingSeek: Long? = null
    private val addedSubtitles = mutableSetOf<String>()
    /** Names for subtitle files added at runtime, in order; libVLC only calls them "Track N". */
    private val slaveNames = mutableListOf<String>()
    private var subtitlesFor: String? = null
    private var onlineJob: Job? = null
    private var onlineResults: List<OnlineSubtitles.Result>? = null
    private var onlineError: String? = null

    // ----- controls -----
    private val spinner = ProgressIndicator().apply { setMaxSize(54.0, 54.0); isMouseTransparent = true }
    private val osd = label("", "player-osd").apply { isVisible = false; isMouseTransparent = true }
    private val osdTimer = PauseTransition(Duration.seconds(2.2)).apply { setOnFinished { osd.isVisible = false } }
    private val seek = SeekBar { ms -> seekTo(ms) }
    private val timeLabel = label("0:00", "player-time")
    private val durationLabel = label("0:00", "player-time")
    private val playButton = iconButton(Icons.PLAY, "Play / pause (Space)") { togglePause() }
    private val muteButton = iconButton(Icons.VOLUME_UP, "Mute (M)") { toggleMute() }
    private val volume = Slider(0.0, 200.0, Settings.playerVolume.toDouble()).apply {
        styleClass += "player-volume"
        prefWidth = 110.0; maxWidth = 110.0
        valueProperty().addListener { _, _, v -> setVolume(v.toInt(), show = false) }
    }
    private val sourceChip = label("", "player-chip")
    /** Wall clock and when the video will end, e.g. "21:34 · ends 22:51". */
    private val clock = label("", "player-clock")
    private var clockMinute = -1L
    /** Audio language is picked once per opened source. */
    private var audioPicked = false
    private val top: Node
    private val bottom: Node
    private val menu = PlayerMenu()
    private val hideTimer = PauseTransition(Duration.seconds(2.8)).apply { setOnFinished { hideControls() } }
    private var controlsVisible = true

    init {
        styleClass += "builtin-player"
        isFocusTraversable = true
        player.videoSurface().set(video.surface)
        video.zoom = runCatching { Zoom.valueOf(Settings.playerZoom) }.getOrDefault(Zoom.FIT)

        top = hbox(14.0,
            iconButton(Icons.BACK, "Back (Esc)") { back() },
            vbox(2.0, label(response.name, "player-title"), label(if (all.size > 1) playable.label else api.name, "player-subtitle")),
            spacer(),
            clock,
            sourceChip,
        ).apply { styleClass += "player-top" }

        val left = hbox(4.0,
            playButton,
            iconButton(Icons.REPLAY_10, "Back 10 s (←)") { skip(-10_000) },
            iconButton(Icons.FORWARD_10, "Forward 10 s (→)") { skip(10_000) },
            *listOfNotNull(next?.let { iconButton(Icons.SKIP_NEXT, "Next episode (N)") { playNext() } }).toTypedArray(),
            muteButton, volume,
            Region().apply { minWidth = 10.0 },
            timeLabel, label("/", "player-time-sep"), durationLabel,
        )
        val right = hbox(4.0,
            iconButton(Icons.SOURCES, "Sources (S)") { showSources() },
            iconButton(Icons.SUBTITLES, "Subtitles (C)") { showSubtitles() },
            iconButton(Icons.LANGUAGE, "Audio language (A)") { showAudio() },
            iconButton(Icons.SPEED, "Speed") { showSpeed() },
            iconButton(Icons.ASPECT, "Zoom (Z)") { showZoom() },
            iconButton(Icons.CAMERA, "Screenshot (Shift+S)") { screenshot() },
            iconButton(Icons.FULLSCREEN, "Full screen (F)") { toggleFullscreen() },
        )
        bottom = VBox(6.0, seek, hbox(8.0, left, spacer(), right)).apply { styleClass += "player-bottom" }

        val chrome = VBox(top, spacer(), bottom).apply { isPickOnBounds = false }
        children.addAll(video, spinner, osd, chrome, menu)
        StackPane.setAlignment(osd, Pos.TOP_CENTER)
        StackPane.setAlignment(menu, Pos.BOTTOM_RIGHT)
        menu.isVisible = false

        // Mouse: move shows controls; click pauses; double-click toggles full screen; wheel = volume.
        addEventFilter(MouseEvent.MOUSE_MOVED) { showControls() }
        addEventFilter(MouseEvent.MOUSE_DRAGGED) { showControls() }
        video.setOnMouseClicked { e ->
            if (e.button != MouseButton.PRIMARY) return@setOnMouseClicked
            if (menu.isVisible) { menu.close(); return@setOnMouseClicked }
            if (e.clickCount == 2) toggleFullscreen() else togglePause()
        }
        video.setOnScroll { e: ScrollEvent -> setVolume(volume.value.toInt() + if (e.deltaY > 0) 5 else -5) }
        menu.onClosed = { scheduleHide() }

        player.events().addMediaPlayerEventListener(Events())
        sources.onUpdate { Platform.runLater { onSourcesChanged() } }
    }

    // ---------- lifecycle ----------

    fun start() {
        Nav.window.enterPlayer(this)
        requestFocus()
        val resume = Library.progress(api.name, playable.data)
            ?.takeIf { it.duration > 0 && it.position / it.duration < 0.92 && it.position > 5 }?.position
        open(current, startAt = resume?.times(1000)?.toLong())
        showControls()
    }

    private fun open(link: ExtractorLink, startAt: Long?) {
        current = link
        spinner.isVisible = true
        val quality = Qualities.getStringByInt(link.quality)
        sourceChip.text = if (quality.isBlank() || link.name.contains(quality, true)) link.name else "${link.name} · $quality"
        val (url, options) = mrl(link)
        val opts = options.toMutableList()
        // Seeking with :start-time works for files and HLS alike, before the first frame.
        startAt?.takeIf { it > 1000 }?.let { opts += ":start-time=${it / 1000.0}" }
        addedSubtitles.clear()
        slaveNames.clear()
        audioPicked = false
        subtitlesFor = null
        if (!player.media().play(url, *opts.toTypedArray())) {
            onError()
        }
    }

    /** libVLC sends only User-Agent and Referer; anything else goes through the local proxy. */
    private fun mrl(link: ExtractorLink): Pair<String, List<String>> {
        val url = (link as? ExtractorLinkPlayList)?.playlist?.firstOrNull()?.url ?: link.url
        val headers = link.getAllHeaders()
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
        val ua = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: USER_AGENT
        val other = headers.filterKeys { !it.equals("referer", true) && !it.equals("user-agent", true) }
        val options = buildList {
            add(":http-user-agent=$ua")
            referer?.let { add(":http-referrer=$it") }
            add(":network-caching=${if (Settings.batterySaver) 3000 else 1500}")
            link.audioTracks.forEach { add(":input-slave=${it.url}") }
        }
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        if (isHttp && other.isNotEmpty()) {
            val all = buildMap { putAll(other); put("User-Agent", ua); referer?.let { put("Referer", it) } }
            return StreamProxy.wrap(url, all) to options
        }
        return url to options
    }

    override fun quit(then: (() -> Unit)?) {
        if (finished) return
        leaving = then
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        record()
        hideTimer.stop()
        onlineJob?.cancel()
        sources.cancel()
        video.close()
        scene?.cursor = Cursor.DEFAULT
        if (Nav.window.stage.isFullScreen) Nav.window.stage.isFullScreen = false
        Nav.window.exitPlayer(this)
        Playback.active = null
        // stop() waits for VLC's threads; never block the UI on it.
        Thread({
            runCatching { player.controls().stop() }
            runCatching { player.release() }
            Vlc.playbackEnded()
        }, "vlc-release").start()
        val then = leaving
        when {
            then != null -> then()
            duration > 0 && position.toDouble() / duration > 0.92 && next != null ->
                if (Settings.autoPlayNext) Playback.open(api, response, next)
                else Toasts.show("Up next: ${next.label}", "Play" to { Playback.open(api, response, next) }, 12.0)
        }
    }

    private fun record() {
        if (duration <= 0 || position < 5000) return
        val entry = WatchEntry(api.name, response.url, response.name, response.posterUrl, playable.data,
            if (all.size > 1) playable.label else null, position / 1000.0, duration / 1000.0, System.currentTimeMillis())
        ui.launch(Dispatchers.IO) { Library.record(entry) }
    }

    // ---------- VLC events (arrive on VLC's threads) ----------

    private inner class Events : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) = fx {
            playing = true
            spinner.isVisible = false
            playButton.graphic = icon(Icons.PAUSE, 22.0)
            setVolume(volume.value.toInt(), show = false)
            applyEqualizer(Settings.playerEqualizer)
            pickAudioLanguage()
            scheduleHide()
        }

        override fun paused(mediaPlayer: MediaPlayer) = fx {
            playing = false
            playButton.graphic = icon(Icons.PLAY, 22.0)
            showControls()
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = fx {
            spinner.isVisible = newCache < 100f && (playing || position == 0L)
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = fx {
            position = newTime
            if (!seek.dragging) seek.set(newTime, duration)
            timeLabel.text = format(newTime)
            updateClock()
            if (duration > 0 && abs(newTime - saved) >= 10_000) { saved = newTime; record() }
            if (newTime > 0) spinner.isVisible = false
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = fx {
            duration = newLength
            durationLabel.text = format(newLength)
        }

        override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) = fx {
            if (newSeekable == 1) pendingSeek?.let { pendingSeek = null; player.controls().setTime(it) }
        }

        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = fx {
            if (subtitlesFor != current.url) { subtitlesFor = current.url; addExtensionSubtitles() }
            if (type == uk.co.caprica.vlcj.media.TrackType.AUDIO) pickAudioLanguage()
        }

        override fun finished(mediaPlayer: MediaPlayer) = fx {
            position = duration
            finish()
        }

        override fun error(mediaPlayer: MediaPlayer) = fx { onError() }
    }

    private fun fx(block: () -> Unit) = Platform.runLater { if (!finished) block() }

    /** A source that fails is skipped automatically, keeping the position. */
    private fun onError() {
        failed += current.url
        val alternative = sources.ranked().firstOrNull { it.url !in failed }
        when {
            alternative != null -> {
                flash("Source failed — trying ${alternative.name}")
                switchTo(alternative)
            }
            !sources.finished -> {
                flash("Source failed — waiting for another source…")
                retryWhenAvailable = true
            }
            else -> {
                Toasts.show("This stream couldn't be played", "Try another source" to { Playback.open(api, response, playable, chooseManually = true) }, 10.0)
                finish()
            }
        }
    }

    private fun onSourcesChanged() {
        if (finished) return
        if (menu.isVisible && menu.kind == "sources") showSources()
        if (retryWhenAvailable) sources.ranked().firstOrNull { it.url !in failed }?.let {
            retryWhenAvailable = false
            switchTo(it)
        }
        if (subtitlesFor == current.url) addExtensionSubtitles()
    }

    private fun switchTo(link: ExtractorLink) {
        val at = position.takeIf { it > 1000 }
        flash("Source: ${link.name}")
        open(link, startAt = at)
    }

    // ---------- playback controls ----------

    private fun togglePause() {
        if (player.status().isPlaying) player.controls().pause() else player.controls().play()
    }

    private fun skip(ms: Long) {
        seekTo((position + ms).coerceIn(0, if (duration > 0) duration - 1000 else Long.MAX_VALUE))
        flash(if (ms > 0) "+${ms / 1000} s" else "−${-ms / 1000} s")
    }

    private fun seekTo(ms: Long) {
        if (!player.status().isSeekable) { pendingSeek = ms; return }
        player.controls().setTime(ms)
        position = ms
        seek.set(ms, duration)
        timeLabel.text = format(ms)
        showControls()
    }

    private fun setVolume(v: Int, show: Boolean = true) {
        val clamped = v.coerceIn(0, 200)
        if (volume.value.toInt() != clamped) volume.value = clamped.toDouble()
        player.audio().setVolume(clamped)
        Settings.playerVolume = clamped
        muteButton.graphic = icon(if (clamped == 0 || muted) Icons.VOLUME_OFF else Icons.VOLUME_UP, 22.0)
        if (show) flash("Volume $clamped%")
    }

    private fun toggleMute() {
        muted = !muted
        player.audio().setMute(muted)
        muteButton.graphic = icon(if (muted) Icons.VOLUME_OFF else Icons.VOLUME_UP, 22.0)
        flash(if (muted) "Muted" else "Sound on")
    }

    private fun setRate(rate: Float) {
        player.controls().setRate(rate)
        flash("Speed ${rate}×")
    }

    private fun toggleFullscreen() {
        Nav.window.stage.fullScreenExitHint = ""
        Nav.window.stage.isFullScreen = !Nav.window.stage.isFullScreen
    }

    private fun back() {
        when {
            menu.isVisible -> menu.close()
            Nav.window.stage.isFullScreen -> Nav.window.stage.isFullScreen = false
            else -> quit()
        }
    }

    private fun playNext() {
        val n = next ?: return
        quit { Playback.open(api, response, n) }
    }

    // ---------- menus ----------

    private fun showSpeed() {
        val rate = player.status().rate()
        menu.open("speed", "Speed", listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).map { r ->
            PlayerMenu.Item(if (r == 1.0f) "Normal" else "$r×", active = abs(rate - r) < 0.01f) { setRate(r); menu.close() }
        })
    }

    private fun showZoom() {
        menu.open("zoom", "Zoom", Zoom.entries.map { z ->
            PlayerMenu.Item(z.label, active = video.zoom == z) { setZoom(z); menu.close() }
        })
    }

    private fun setZoom(z: Zoom) {
        video.zoom = z
        Settings.playerZoom = z.name
        flash("Zoom: ${z.label}")
    }

    private fun showEqualizer() {
        val presets = Vlc.factory?.equalizer()?.presets().orEmpty()
        val now = Settings.playerEqualizer
        val items = listOf(PlayerMenu.Item("Back", icon = Icons.BACK, keepOpen = true) { showAudio() },
            PlayerMenu.Item("Off", active = now.isBlank()) { setEqualizer("") }) +
            presets.filter { it != "Flat" }.map { name -> PlayerMenu.Item(name, active = name == now) { setEqualizer(name) } }
        menu.open("equalizer", "Equalizer", items)
    }

    private fun setEqualizer(name: String) {
        Settings.playerEqualizer = name
        applyEqualizer(name)
        flash(if (name.isBlank()) "Equalizer off" else "Equalizer: $name")
        menu.close()
    }

    private fun applyEqualizer(name: String) {
        val eq = name.takeIf { it.isNotBlank() }?.let { runCatching { Vlc.factory?.equalizer()?.newEqualizer(it) }.getOrNull() }
        player.audio().setEqualizer(eq)
    }

    /** Selects the first audio track in a preferred language (Settings → Playback). */
    private fun pickAudioLanguage() {
        val wanted = Settings.audioLanguage
        if (audioPicked || wanted.isBlank()) return
        val tracks = player.audio().trackDescriptions().filter { it.id() >= 0 }
        if (tracks.size < 2) return
        val match = tracks.firstOrNull { t ->
            val d = t.description().lowercase()
            wanted.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { w ->
                val name = OnlineSubtitles.languageName(w).lowercase()
                d.contains("[$w]") || d.contains(name) || runCatching { d.contains(java.util.Locale(w).isO3Language) }.getOrDefault(false)
            }
        } ?: return
        audioPicked = true
        if (match.id() != player.audio().track()) {
            player.audio().setTrack(match.id())
            flash("Audio: ${cleanTrack(match.description())}")
        }
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000 + (if (duration > 0) (duration - position) / 60_000 else 0)
        if (minute == clockMinute) return
        clockMinute = minute
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val zone = java.time.ZoneId.systemDefault()
        val time = java.time.Instant.ofEpochMilli(now).atZone(zone).format(fmt)
        val rate = player.status().rate().takeIf { it > 0 } ?: 1f
        val ends = if (duration > 0) java.time.Instant.ofEpochMilli(now + ((duration - position) / rate).toLong()).atZone(zone).format(fmt) else null
        clock.text = if (ends != null) "$time · ends $ends" else time
    }

    /** Saves the current frame to ~/Pictures/CloudStream. */
    private fun screenshot() {
        val dir = java.io.File(System.getProperty("user.home"), "Pictures/CloudStream").apply { mkdirs() }
        val name = (response.name + if (all.size > 1) " ${playable.label}" else "").replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim()
        val file = java.io.File(dir, "$name ${format(position).replace(':', '-')}.png")
        Thread({
            val ok = runCatching { player.snapshots().save(file) }.getOrDefault(false)
            Platform.runLater { flash(if (ok) "Screenshot saved to Pictures/CloudStream" else "Couldn't take a screenshot") }
        }, "vlc-snapshot").start()
    }

    private fun cycleZoom() = setZoom(Zoom.entries[(video.zoom.ordinal + 1) % Zoom.entries.size])

    private fun showAudio() {
        val tracks = player.audio().trackDescriptions().filter { it.id() >= 0 }
        val selected = player.audio().track()
        val delay = player.audio().delay() / 1000
        val eq = Settings.playerEqualizer.ifBlank { "Off" }
        val items = tracks.map { t ->
            PlayerMenu.Item(cleanTrack(t.description()), active = t.id() == selected) { player.audio().setTrack(t.id()); flash("Audio: ${cleanTrack(t.description())}"); menu.close() }
        }.ifEmpty { listOf(PlayerMenu.Item("No audio tracks yet", enabled = false) {}) } + listOf(
            PlayerMenu.Item("Equalizer: $eq", icon = Icons.TUNE, keepOpen = true) { showEqualizer() },
            PlayerMenu.Item("Audio delay: $delay ms", enabled = false) {},
            PlayerMenu.Item("Delay −50 ms", keepOpen = true) { player.audio().setDelay(player.audio().delay() - 50_000); showAudio() },
            PlayerMenu.Item("Delay +50 ms", keepOpen = true) { player.audio().setDelay(player.audio().delay() + 50_000); showAudio() },
        )
        menu.open("audio", "Audio language", items)
    }

    private fun showSubtitles() {
        val tracks = player.subpictures().trackDescriptions()
        val selected = player.subpictures().track()
        val delay = player.subpictures().delay() / 1000
        val items = mutableListOf<PlayerMenu.Item>()
        items += PlayerMenu.Item("Off", active = selected < 0) { player.subpictures().setTrack(-1); flash("Subtitles off"); menu.close() }
        val real = tracks.filter { it.id() >= 0 }
        // Added files come after the stream's own tracks, in the order they were added.
        val firstAdded = real.size - slaveNames.size
        real.forEachIndexed { i, t ->
            val name = slaveNames.getOrNull(i - firstAdded)?.takeIf { i >= firstAdded && t.description().startsWith("Track") }
                ?: cleanTrack(t.description())
            items += PlayerMenu.Item(name, active = t.id() == selected) {
                player.subpictures().setTrack(t.id()); flash("Subtitles: $name"); menu.close()
            }
        }
        items += PlayerMenu.Item("Search online (OpenSubtitles)…", hint = "free, no account", icon = Icons.CLOUD_DOWNLOAD, keepOpen = true) { showOnline() }
        items += PlayerMenu.Item("Load from file…", icon = Icons.FILE) { menu.close(); loadSubtitleFile() }
        items += PlayerMenu.Item("Delay: $delay ms", enabled = false) {}
        items += PlayerMenu.Item("Show earlier (−100 ms)", hint = "G", keepOpen = true) { subDelay(-100); showSubtitles() }
        items += PlayerMenu.Item("Show later (+100 ms)", hint = "H", keepOpen = true) { subDelay(100); showSubtitles() }
        menu.open("subtitles", "Subtitles", items)
    }

    private fun subDelay(ms: Long) {
        player.subpictures().setDelay(player.subpictures().delay() + ms * 1000)
        flash("Subtitle delay ${player.subpictures().delay() / 1000} ms")
    }

    /** OpenSubtitles results for this title/episode, preferred language first. */
    private fun showOnline(languageFilter: String? = Settings.subtitleLanguage.ifBlank { "en" }) {
        val results = onlineResults
        if (results == null && onlineJob?.isActive != true) {
            onlineError = null
            val q = OnlineSubtitles.query(response, playable.season, playable.episode)
            onlineJob = ui.launch {
                try {
                    onlineResults = OnlineSubtitles.search(q)
                } catch (t: Throwable) {
                    onlineError = t.message ?: t.toString()
                    onlineResults = null
                }
                if (menu.isVisible && menu.kind == "online") showOnline(languageFilter)
            }
        }
        val items = mutableListOf<PlayerMenu.Item>()
        items += PlayerMenu.Item("Back", icon = Icons.BACK, keepOpen = true) { showSubtitles() }
        when {
            onlineError != null -> {
                items += PlayerMenu.Item("Couldn't search: $onlineError", enabled = false) {}
                items += PlayerMenu.Item("Try again", icon = Icons.REFRESH, keepOpen = true) { onlineError = null; onlineResults = null; showOnline(languageFilter) }
            }
            results == null -> items += PlayerMenu.Item("Searching OpenSubtitles…", enabled = false) {}
            results.isEmpty() -> items += PlayerMenu.Item("No subtitles found for this title", enabled = false) {}
            else -> {
                val languages = results.map { it.language }.distinct().sortedBy { OnlineSubtitles.languageName(it) }
                val shown = results.filter { languageFilter == null || OnlineSubtitles.matches(it.language, languageFilter) }
                if (languageFilter != null) items += PlayerMenu.Item("All languages (${languages.size})", icon = Icons.LANGUAGE, keepOpen = true) { showOnline(null) }
                if (shown.isEmpty()) items += PlayerMenu.Item("None in this language", enabled = false) {}
                val counters = mutableMapOf<String, Int>()
                shown.take(60).forEach { r ->
                    val n = counters.merge(r.language, 1, Int::plus)!!
                    val label = "${r.languageName} #$n"
                    items += PlayerMenu.Item(label, hint = if (r.url in addedSubtitles) "added" else null, active = r.url in addedSubtitles) { addOnline(r, label) }
                }
                if (languageFilter == null) {
                    // Quick filters for the languages on offer.
                    languages.take(12).forEach { code ->
                        items += PlayerMenu.Item("Only ${OnlineSubtitles.languageName(code)}", keepOpen = true) { showOnline(code) }
                    }
                }
            }
        }
        menu.open("online", "Online subtitles", items)
    }

    private fun addOnline(result: OnlineSubtitles.Result, label: String) {
        menu.close()
        flash("Downloading $label…")
        ui.launch {
            try {
                val file = OnlineSubtitles.download(result)
                addedSubtitles += result.url
                slaveNames += "$label (online)"
                player.media().addSlave(MediaSlaveType.SUBTITLE, file.toPath().toUri().toString(), true)
                flash("Subtitles: $label")
            } catch (t: Throwable) {
                flash("Couldn't download subtitles: ${t.message ?: t}")
            }
        }
    }

    /** Subtitles the extension found, downloaded with its headers and added as tracks. */
    private fun addExtensionSubtitles() {
        val list = sources.subtitles.filter { it.url !in addedSubtitles }.take(20)
        if (list.isEmpty()) return
        list.forEach { addedSubtitles += it.url }
        val wanted = Settings.subtitleLanguage
        ui.launch {
            for (sub in list) {
                val file = runCatching { downloadSubtitle(sub) }.getOrNull() ?: continue
                if (finished) return@launch
                val select = wanted.isNotBlank() && player.subpictures().track() < 0 && OnlineSubtitles.matches(sub.lang, wanted)
                slaveNames += sub.lang
                player.media().addSlave(MediaSlaveType.SUBTITLE, file.toPath().toUri().toString(), select)
            }
        }
    }

    private suspend fun downloadSubtitle(sub: SubtitleFile): File = withContext(Dispatchers.IO) {
        if (sub.url.startsWith("file:")) return@withContext File(URI(sub.url))
        if (sub.url.startsWith("/")) return@withContext File(sub.url)
        val dir = File(AppDirs.data, "cache/subtitles").apply { mkdirs() }
        val ext = sub.url.substringBefore('?').substringAfterLast('.', "srt").take(4).lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub") } ?: "srt"
        val file = File(dir, "x-${sub.url.hashCode().toUInt()}.$ext")
        if (!file.isFile || file.length() == 0L) {
            // Subtitles usually live on the same host as the video, which may want its Referer.
            val headers = sub.headers ?: listOfNotNull(current.referer.takeIf { it.isNotBlank() }?.let { "Referer" to it }).toMap()
            val response = app.get(sub.url, headers = headers, timeout = 20)
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} for ${sub.url}")
            file.writeBytes(response.body.bytes())
        }
        file
    }

    private fun loadSubtitleFile() {
        val file = FileChooser().apply {
            title = "Load subtitles"
            extensionFilters += FileChooser.ExtensionFilter("Subtitles", "*.srt", "*.vtt", "*.ass", "*.ssa", "*.sub", "*.txt")
        }.showOpenDialog(Nav.window.stage) ?: return
        slaveNames += file.name
        player.media().addSlave(MediaSlaveType.SUBTITLE, file.toPath().toUri().toString(), true)
        flash("Subtitles: ${file.name}")
    }

    private fun showSources() {
        val links = sources.ranked()
        val items = links.map { link ->
            val quality = Qualities.getStringByInt(link.quality).ifEmpty { "Auto" }
            val host = runCatching { URI(link.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
            PlayerMenu.Item(link.name, hint = listOf(quality, link.type.name, host).filter { it.isNotBlank() }.joinToString(" · "),
                active = link.url == current.url, enabled = true, muted = link.url in failed) {
                menu.close()
                if (link.url != current.url) switchTo(link)
            }
        }.toMutableList()
        if (!sources.finished) items += PlayerMenu.Item("Still searching for more sources…", enabled = false) {}
        menu.open("sources", "Sources (${links.size})", items)
    }

    // ---------- keys ----------

    override fun handle(e: KeyEvent) {
        if (e.eventType != KeyEvent.KEY_PRESSED) return
        showControls()
        when (e.code) {
            KeyCode.SPACE, KeyCode.K -> togglePause()
            KeyCode.LEFT, KeyCode.J -> skip(if (e.isShiftDown) -60_000 else -10_000)
            KeyCode.RIGHT, KeyCode.L -> skip(if (e.isShiftDown) 60_000 else 10_000)
            KeyCode.UP -> if (menu.isVisible) return else setVolume(volume.value.toInt() + 5)
            KeyCode.DOWN -> if (menu.isVisible) return else setVolume(volume.value.toInt() - 5)
            KeyCode.M -> toggleMute()
            KeyCode.F, KeyCode.F11 -> toggleFullscreen()
            KeyCode.ESCAPE, KeyCode.BACK_SPACE -> back()
            KeyCode.S -> if (e.isShiftDown) screenshot() else showSources()
            KeyCode.C -> showSubtitles()
            KeyCode.A -> showAudio()
            KeyCode.Z -> cycleZoom()
            KeyCode.N -> playNext()
            KeyCode.G -> subDelay(-100)
            KeyCode.H -> subDelay(100)
            KeyCode.OPEN_BRACKET -> setRate((player.status().rate() - 0.25f).coerceAtLeast(0.25f))
            KeyCode.CLOSE_BRACKET -> setRate((player.status().rate() + 0.25f).coerceAtMost(2.0f))
            KeyCode.HOME -> seekTo(0)
            in listOf(KeyCode.DIGIT0, KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5,
                KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9) ->
                if (duration > 0) seekTo(duration * (e.code.ordinal - KeyCode.DIGIT0.ordinal) / 10)
            else -> return
        }
        e.consume()
    }

    // ---------- chrome ----------

    private fun showControls() {
        if (!controlsVisible) {
            controlsVisible = true
            top.isVisible = true
            bottom.isVisible = true
            scene?.cursor = Cursor.DEFAULT
        }
        scheduleHide()
    }

    private fun scheduleHide() {
        hideTimer.playFromStart()
    }

    private fun hideControls() {
        if (!playing || menu.isVisible || seek.dragging || bottom.isHover || top.isHover) { scheduleHide(); return }
        controlsVisible = false
        top.isVisible = false
        bottom.isVisible = false
        scene?.cursor = Cursor.NONE
    }

    private fun flash(text: String) {
        osd.text = text
        osd.isVisible = true
        osdTimer.playFromStart()
    }

    private fun iconButton(path: String, tip: String, action: () -> Unit) =
        button(null, path, "player-button") { action(); showControls() }.apply {
            tooltip(tip)
            id = "player-" + tip.substringBefore(' ').lowercase()
            isFocusTraversable = false
        }

    private companion object {
        fun format(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        /** VLC names tracks "Track 1 - [English]"; show "English" (or "Track 1"). */
        fun cleanTrack(description: String): String {
            val inBrackets = Regex("\\[([^]]+)]").find(description)?.groupValues?.get(1)
            val base = description.substringBefore(" - [").trim()
            return when {
                inBrackets != null && base.startsWith("Track") -> inBrackets
                inBrackets != null -> "$base ($inBrackets)"
                else -> description
            }
        }
    }
}

/** The seek bar: click or drag anywhere, with a time preview while hovering. */
class SeekBar(private val onSeek: (Long) -> Unit) : StackPane() {
    private val track = Region().apply { styleClass += "seek-track" }
    private val fill = Region().apply { styleClass += "seek-fill" }
    private val knob = Region().apply { styleClass += "seek-knob" }
    private val preview = label("", "seek-preview").apply { isVisible = false; isManaged = false }
    private var time = 0L
    private var length = 0L
    var dragging = false
        private set

    init {
        styleClass += "seek-bar"
        minHeight = 22.0; maxHeight = 22.0
        alignment = Pos.CENTER_LEFT
        fill.isManaged = false; knob.isManaged = false
        children.addAll(track, fill, knob, preview)
        setOnMouseMoved { e -> showPreview(e.x) }
        setOnMouseExited { preview.isVisible = false }
        setOnMousePressed { e -> dragging = true; at(e.x) }
        setOnMouseDragged { e -> at(e.x); showPreview(e.x) }
        setOnMouseReleased { e ->
            dragging = false
            if (length > 0) onSeek(timeAt(e.x))
        }
    }

    fun set(time: Long, length: Long) {
        this.time = time
        this.length = length
        requestLayout()
    }

    private fun timeAt(x: Double) = if (width <= 0 || length <= 0) 0L else (x / width).coerceIn(0.0, 1.0).times(length).toLong()

    private fun at(x: Double) {
        if (length <= 0) return
        time = timeAt(x)
        requestLayout()
    }

    private fun showPreview(x: Double) {
        if (length <= 0) return
        val ms = timeAt(x)
        val s = ms / 1000
        preview.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        preview.isVisible = true
        preview.autosize()
        preview.relocate((x - preview.width / 2).coerceIn(0.0, (width - preview.width).coerceAtLeast(0.0)), -preview.height - 8)
    }

    override fun layoutChildren() {
        super.layoutChildren()
        val fraction = if (length > 0) (time.toDouble() / length).coerceIn(0.0, 1.0) else 0.0
        val h = 5.0
        val y = (height - h) / 2
        fill.resizeRelocate(0.0, y, width * fraction, h)
        knob.resizeRelocate(width * fraction - 7, (height - 14) / 2, 14.0, 14.0)
    }
}

/** Side panel for the player's menus (sources, subtitles, audio, speed, zoom). */
class PlayerMenu : VBox(4.0) {
    data class Item(
        val title: String,
        val hint: String? = null,
        val active: Boolean = false,
        val enabled: Boolean = true,
        val muted: Boolean = false,
        val keepOpen: Boolean = false,
        val icon: String? = null,
        val action: () -> Unit,
    )

    var kind: String? = null
        private set
    var onClosed: (() -> Unit)? = null
    private val heading = label("", "player-menu-title")
    private val rows = VBox(2.0)
    private val scroll = pageScroll(rows).apply { styleClass += "player-menu-scroll" }

    init {
        styleClass += "player-menu"
        maxWidth = 380.0; minWidth = 320.0
        maxHeight = Region.USE_PREF_SIZE
        children.addAll(hbox(8.0, heading, spacer(), button(null, Icons.CLOSE, "player-button", "small") { close() }), scroll)
        StackPane.setMargin(this, javafx.geometry.Insets(0.0, 18.0, 112.0, 0.0))
        setOnMouseClicked { it.consume() }
    }

    fun open(kind: String, title: String, items: List<Item>) {
        this.kind = kind
        heading.text = title
        rows.children.setAll(items.map { row(it) })
        isVisible = true
        // Size to the rows (up to a limit, then scroll) every time the list changes;
        // styles must be applied first or the rows measure too small.
        applyCss()
        scroll.prefViewportHeight = minOf(460.0, rows.prefHeight(340.0))
    }

    fun close() {
        if (!isVisible) return
        isVisible = false
        kind = null
        onClosed?.invoke()
    }

    private fun row(item: Item): Node {
        val check = icon(if (item.active) Icons.CHECK else (item.icon ?: Icons.CHECK), 18.0).apply {
            opacity = if (item.active || item.icon != null) 1.0 else 0.0
            if (item.active) styleClass += "accent-icon"
        }
        val text = vbox(1.0, label(item.title, "player-menu-item").apply { isWrapText = true; maxWidth = 280.0 })
        item.hint?.let { text.children += label(it, "player-menu-hint") }
        HBox.setHgrow(text, Priority.ALWAYS)
        return hbox(10.0, check, text).apply {
            styleClass += "player-menu-row"
            if (item.active) styleClass += "active"
            if (item.muted) styleClass += "muted"
            if (!item.enabled) styleClass += "disabled" else onClick { item.action() }
        }
    }
}
