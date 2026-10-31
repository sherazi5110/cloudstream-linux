package io.github.sherazi5110.cloudstream.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import io.github.sherazi5110.cloudstream.Settings
import io.github.sherazi5110.cloudstream.player.PlayerController
import io.github.sherazi5110.cloudstream.player.Zoom
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private enum class Sheet(val title: String) { SOURCES("Sources"), SUBTITLES("Subtitles"), AUDIO("Audio"), SPEED("Speed"), ZOOM("Zoom") }

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(screen: Screen.Player) {
    val c = remember(screen) { PlayerController(screen.api, screen.response, screen.playable, screen.chooseSource) }
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember(screen) { mutableStateOf(if (screen.chooseSource) Sheet.SOURCES else null) }
    var poke by remember { mutableIntStateOf(0) } // any interaction restarts the hide timer
    var pinch by remember { mutableFloatStateOf(1f) }
    val rootFocus = remember { FocusRequester() }

    DisposableEffect(c) { onDispose { c.release() } }

    // Full screen, landscape on phones, screen kept on.
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val orientation = activity?.requestedOrientation
        if (!isTv) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (!isTv) activity?.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
            window?.attributes = window?.attributes?.apply { screenBrightness = -1f }
        }
    }

    // Pause when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { if (c.player.isPlaying) c.player.pause(); c.save() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The end: next episode (if on), else back to the title page.
    LaunchedEffect(c.ended) {
        if (!c.ended) return@LaunchedEffect
        val next = c.next
        if (next != null && Settings.autoPlayNext) Nav.replace(Screen.Player(c.api, c.response, next)) else Nav.back()
    }

    LaunchedEffect(controls, poke, c.playing, sheet) {
        if (controls && c.playing && sheet == null) {
            delay(if (isTv) 5000 else 4000)
            controls = false
        }
    }
    LaunchedEffect(controls, c.started) { if ((!controls || !c.started) && sheet == null) runCatching { rootFocus.requestFocus() } }

    BackHandler {
        when {
            sheet != null -> sheet = null
            isTv && controls && c.started -> controls = false
            else -> Nav.back()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                poke++
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { c.togglePause(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { c.player.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { c.player.pause(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { c.seekBy(30_000); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { c.seekBy(-30_000); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { c.next?.let { Nav.replace(Screen.Player(c.api, c.response, it)) }; true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls && c.started && sheet == null) { c.togglePause(); controls = true; true } else false
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (!controls && c.started && sheet == null) { c.seekBy(-10_000); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls && c.started && sheet == null) { c.seekBy(10_000); true } else false
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU ->
                        if (!controls && c.started && sheet == null) { controls = true; true } else false
                    else -> false
                }
            }
    ) {
        // A new view per playback (next episode replaces the controller).
        key(c) {
            AndroidView(
                factory = { ctx -> VLCVideoLayout(ctx).also { c.attach(it) } },
                onRelease = { c.detach() },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = pinch, scaleY = pinch),
            )
        }

        if (!c.started) {
            LoadingOverlay(c, onChoose = { c.stopAutoPlay(); sheet = Sheet.SOURCES })
        } else {
            if (!isTv) Gestures(locked, onTap = { controls = !controls; poke++ }, onSeek = { c.seekBy(it); poke++ }, onPinch = { pinch = (pinch * it).coerceIn(1f, 4f) })
            if (c.buffering && c.error == null) Spinner(Modifier.fillMaxSize())
            c.error?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).padding(16.dp))
            }
            if (locked) {
                AnimatedVisibility(controls, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                    RoundIcon(Icons.Filled.Lock, "Unlock", Modifier.padding(24.dp)) { locked = false; poke++ }
                }
            } else AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
                Controls(c, onSheet = { sheet = it }, onLock = { locked = true; poke++ }, poke = { poke++ })
            }
        }

        sheet?.let { s -> SheetPanel(s, c, Modifier.align(Alignment.CenterEnd)) { sheet = null } }
    }
}

/** Swipes, taps and pinch on phones: left side brightness, right side volume, double-tap ±10 s. */
@Composable
private fun Gestures(locked: Boolean, onTap: () -> Unit, onSeek: (Long) -> Unit, onPinch: (Float) -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var indicator by remember { mutableStateOf<Pair<Boolean, Float>?>(null) } // (isBrightness, level)
    var seekFlash by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(indicator) { if (indicator != null) { delay(900); indicator = null } }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(600); seekFlash = null } }

    Box(Modifier.fillMaxSize()
        .pointerInput(locked) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { p -> if (!locked) { val ms = if (p.x < size.width / 2) -10_000L else 10_000L; onSeek(ms); seekFlash = ms } },
            )
        }
        .pointerInput(locked) {
            if (locked) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val left = down.position.x < size.width / 2
                var mode = 0 // 0 undecided, 1 swipe, 2 pinch
                var total = 0f
                var level = if (left) activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.size >= 2) {
                        mode = 2
                        onPinch(event.calculateZoom())
                        event.changes.forEach { it.consume() }
                    } else if (mode != 2) {
                        val ch = event.changes.first()
                        val dy = ch.position.y - ch.previousPosition.y
                        total += dy
                        if (mode == 0 && abs(total) > viewConfiguration.touchSlop * 2) mode = 1
                        if (mode == 1) {
                            level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                            if (left) activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) } }
                            else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                            }
                            indicator = left to level
                            ch.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        indicator?.let { (bright, level) ->
            Column(Modifier.align(if (bright) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 48.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (bright) Icons.Filled.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White)
                Box(Modifier.padding(top = 8.dp).width(4.dp).height(110.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.align(Alignment.BottomCenter).width(4.dp).fillMaxHeight(level).background(Color.White, RoundedCornerShape(2.dp)))
                }
            }
        }
        seekFlash?.let { ms ->
            Text(if (ms < 0) "−10 s" else "+10 s", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(if (ms < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 96.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(18.dp))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Int = 44, onClick: () -> Unit) {
    Box(modifier.size(size.dp).card(CircleShape, onClick = onClick).background(Color.Black.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun Controls(c: PlayerController, onSheet: (Sheet) -> Unit, onLock: () -> Unit, poke: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { playFocus.requestFocus() } }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))) {
        // Top: back, title, lock, clock.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") { Nav.back() }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(c.response.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (c.playables.size > 1) c.playable.label else c.current?.name
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            if (!isTv) RoundIcon(Icons.Filled.LockOpen, "Lock") { onLock() }
            val ends = if (c.length > 0) " · ends ${timeFormat.format(Date(now + ((c.length - c.time) / c.speed).toLong()))}" else ""
            Text(timeFormat.format(Date(now)) + ends, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
        }
        // Middle: −10 · play/pause · +10
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Filled.Replay10, "Back 10 seconds", size = 56) { c.seekBy(-10_000); poke() }
            RoundIcon(if (c.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.focusRequester(playFocus), size = 76) { c.togglePause(); poke() }
            RoundIcon(Icons.Filled.Forward10, "Forward 10 seconds", size = 56) { c.seekBy(10_000); poke() }
        }
        // Bottom: seek bar and the menus.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (!c.isLive) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(c.time / 1000.0), style = MaterialTheme.typography.titleSmall)
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: if (c.length > 0) c.time.toFloat() / c.length else 0f,
                    onValueChange = { dragging = it; poke() },
                    onValueChangeFinished = { dragging?.let { c.seekTo((it * c.length).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(clock(c.length / 1000.0), style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BarButton(Icons.AutoMirrored.Filled.List, "Sources") { onSheet(Sheet.SOURCES) }
                BarButton(Icons.Filled.ClosedCaption, "Subtitles") { onSheet(Sheet.SUBTITLES) }
                BarButton(Icons.Filled.Translate, "Audio") { onSheet(Sheet.AUDIO) }
                BarButton(Icons.Filled.Speed, "${c.speed}×") { onSheet(Sheet.SPEED) }
                BarButton(Icons.Filled.AspectRatio, c.zoom.label) { onSheet(Sheet.ZOOM) }
                c.next?.let { next -> BarButton(Icons.Filled.SkipNext, "Next episode") { Nav.replace(Screen.Player(c.api, c.response, next)) } }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.card(RoundedCornerShape(8.dp), onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Before playback: artwork, how many sources were found, and Play now / Choose source / Cancel. */
@Composable
private fun LoadingOverlay(c: PlayerController, onChoose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        NetImage(c.response.backgroundPosterUrl ?: c.response.posterUrl, Modifier.fillMaxSize(), c.response.posterHeaders)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
        Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.response.name, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (c.playables.size > 1) c.playable.label else c.api.name, style = MaterialTheme.typography.titleMedium, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
            val status = when {
                c.current != null -> "Starting ${c.current!!.name}…"
                c.searching -> "Looking for sources…"
                c.links.isEmpty() -> c.searchError ?: "No sources found"
                else -> "Pick a source"
            }
            Row(Modifier.fillMaxWidth()) {
                Text(status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${c.links.size} SOURCE${if (c.links.size == 1) "" else "S"}", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
            if (c.searching || c.current != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp), color = Palette.accent, trackColor = Color.White.copy(alpha = 0.15f))
            else Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (c.links.isNotEmpty()) PillButton("Play now", Icons.Filled.PlayArrow) { c.ranked().firstOrNull()?.let { c.play(it) } }
                if (c.links.isNotEmpty()) PillButton("Choose source", Icons.AutoMirrored.Filled.List, primary = false) { onChoose() }
                PillButton("Cancel", null, primary = false) { Nav.back() }
            }
        }
    }
}

private fun ExtractorLink.qualityText(): String = when {
    quality == Qualities.Unknown.value || quality <= 0 -> ""
    quality >= 2160 -> "4K"
    else -> "${quality}p"
}

@Composable
private fun SheetPanel(sheet: Sheet, c: PlayerController, modifier: Modifier, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    val first = remember { FocusRequester() }
    LaunchedEffect(sheet) { runCatching { first.requestFocus() } }
    Column(modifier.fillMaxHeight().width(340.dp).background(Palette.surface.copy(alpha = 0.97f)).padding(vertical = 16.dp)) {
        Text(sheet.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        data class Option(val label: String, val detail: String?, val selected: Boolean, val action: () -> Unit)
        val options: List<Option> = when (sheet) {
            Sheet.SOURCES -> c.ranked().map { l ->
                Option(l.name, listOf(l.qualityText(), l.source.takeIf { it != l.name }.orEmpty(), if (l.isM3u8) "HLS" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    l.url == c.current?.url) { c.play(l); onClose() }
            }
            Sheet.SUBTITLES -> buildList {
                val spu = c.spuTracks()
                val currentSpu = c.spuTrack()
                add(Option("Off", null, currentSpu == -1 && c.selectedSubtitle == null) { c.setSpu(-1); onClose() })
                spu.filter { it.id != -1 }.forEach { t -> add(Option(t.name, "In the video", t.id == currentSpu && c.selectedSubtitle == null) { c.setSpu(t.id); onClose() }) }
                c.subtitles.forEach { s -> add(Option(s.lang, "From ${c.api.name}", s.url == c.selectedSubtitle) { c.addSubtitle(s); onClose() }) }
            }
            Sheet.AUDIO -> c.audioTracks().filter { it.id != -1 }.map { t -> Option(t.name, null, t.id == c.audioTrack()) { c.setAudio(t.id); onClose() } }
            Sheet.SPEED -> listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { r -> Option("$r×", if (r == 1f) "Normal" else null, r == c.speed) { c.setRate(r); onClose() } }
            Sheet.ZOOM -> Zoom.entries.map { z -> Option(z.label, null, z == c.zoom) { c.setZoomMode(z); onClose() } }
        }
        if (options.isEmpty()) Text(
            when (sheet) { Sheet.SOURCES -> if (c.searching) "Looking for sources…" else "No sources found"; else -> "Nothing to choose here" },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(options.size) { i ->
                val o = options[i]
                Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .card(RoundedCornerShape(8.dp), focusScale = 1.02f, onClick = o.action)
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        o.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (o.selected) Icon(Icons.Filled.Check, null, tint = Palette.accent)
                }
            }
        }
    }
}
