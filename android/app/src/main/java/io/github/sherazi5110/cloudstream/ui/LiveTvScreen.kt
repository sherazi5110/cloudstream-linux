package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.ChannelLogos
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.LiveApi
import io.github.sherazi5110.cloudstream.LiveTv
import io.github.sherazi5110.cloudstream.StremioAddons
import io.github.sherazi5110.cloudstream.StremioApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object LivePlayback {
    /** Opens a channel in the player; [list] lets CH+ / CH− move through the channels shown. */
    fun play(scope: CoroutineScope, c: LiveTv.Channel, list: List<LiveTv.Channel>, replace: Boolean = false) {
        LiveTv.watched(c)
        scope.launch {
            runCatching {
                val (api, r) = if (c.source.startsWith("stremio:")) {
                    val addon = StremioAddons.all().first { "stremio:" + it.manifest.id == c.source }
                    val api = StremioApi(addon)
                    api to (Engine.load(api, c.url) ?: error("The add-on returned nothing for ${c.name}"))
                } else LiveApi to LiveApi.load(LiveApi.data(c))!!
                val playable = Engine.playables(r).firstOrNull() ?: error("Nothing to play")
                val screen = Screen.Player(api, r, playable, channels = list, channel = c)
                if (replace) Nav.replace(screen) else Nav.open(screen)
            }.onFailure { App.toast("Couldn't play ${c.name}: ${it.message ?: it}") }
        }
    }

    /** CH+ / CH−: the channel [step] places away in the list, wrapping around. */
    fun neighbour(list: List<LiveTv.Channel>, current: LiveTv.Channel, step: Int): LiveTv.Channel? {
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
        return list[(i + step).mod(list.size)]
    }
}

/** The channel's logo (playlist, else iptv-org), over its initials so a missing logo still looks right. */
@Composable
fun ChannelLogo(c: LiveTv.Channel, size: androidx.compose.ui.unit.Dp) {
    val logo by produceState(c.logo, c.key) { value = runCatching { ChannelLogos.candidates(c).firstOrNull() }.getOrNull() ?: c.logo }
    val hue = (c.name.hashCode() and 0xffffff).let { Color(0xFF000000 or it.toLong()).copy(alpha = 1f) }
    Box(Modifier.size(size).background(hue.copy(red = hue.red * 0.6f, green = hue.green * 0.6f, blue = hue.blue * 0.6f), RoundedCornerShape(size * 0.2f)),
        contentAlignment = Alignment.Center) {
        Text(c.name.filter { it.isLetterOrDigit() }.take(3).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.28f).sp)
        if (logo != null) AsyncImage(logo, c.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().background(Color(0xFF1B1B22), RoundedCornerShape(size * 0.2f)).padding(size * 0.12f))
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(modifier.background(Color(0xFFE11D48), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(Color.White, RoundedCornerShape(3.dp)))
        Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun progressOf(p: LiveTv.Programme, now: Long) = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)

@Composable
fun LiveTvScreen() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = LiveTv.onChange { version++ }; onDispose { off() } }
    var channels by remember { mutableStateOf<List<LiveTv.Channel>?>(null) }
    var guideVersion by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    LaunchedEffect(version, force) {
        channels = LiveTv.channels(force = force)
        force = false
        runCatching { LiveTv.loadGuides() }
        guideVersion++
    }
    // Now/next move on by themselves.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val all = channels
    when {
        LiveTv.sources().isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Live TV", "Add an M3U playlist, an Xtream Codes login or a Stremio TV add-on in Settings → Live TV.",
                button = "Add a source") { ProfileUi.guarded { if (isWideNow) Nav.select(Tab.MORE) else Nav.open(Screen.SettingsPage(Section.LIVETV.name)) } }
        }
        all == null -> Spinner(Modifier.fillMaxSize())
        else -> LiveContent(all, now, guideVersion, scope) { force = true; version++ }
    }
}

private var isWideNow = false

@Composable
private fun LiveContent(all: List<LiveTv.Channel>, now: Long, guideVersion: Int, scope: CoroutineScope, onRefresh: () -> Unit) {
    val wide = isWide
    isWideNow = wide
    var favVersion by remember { mutableIntStateOf(0) }
    val favourites = remember(favVersion) { LiveTv.favourites() }
    val recentKeys = remember(favVersion) { LiveTv.recent() }
    val groups = remember(all) { all.groupingBy { it.group ?: "Other" }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    val shown = remember(all, filter, query, favVersion) {
        all.filter { c ->
            when (filter) { "All" -> true; "★ Favourites" -> c.key in favourites; else -> (c.group ?: "Other") == filter } &&
                (query.isBlank() || c.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val recent = remember(all, recentKeys) { recentKeys.mapNotNull { k -> all.firstOrNull { it.key == k } } }
    var selected by remember { mutableStateOf<LiveTv.Channel?>(null) }
    val banner = selected ?: recent.firstOrNull() ?: shown.firstOrNull()
    val edge = if (wide) 40.dp else 16.dp

    LazyVerticalGrid(
        if (wide) GridCells.Adaptive(220.dp) else GridCells.Adaptive(160.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = edge, end = edge, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            banner?.let { c -> LiveBanner(c, all.indexOf(c) + 1, now, guideVersion, c.key in favourites, wide,
                onWatch = { LivePlayback.play(scope, c, shown.ifEmpty { all }) },
                onFavourite = { LiveTv.toggleFavourite(c); favVersion++ }) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search channels") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.surface, focusedContainerColor = Palette.surface,
                        unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.accent),
                    modifier = Modifier.weight(1f).widthIn(max = 420.dp))
                Spacer(Modifier.weight(0.01f))
                IconAction(Icons.Filled.Refresh, "Refresh") { onRefresh() }
                IconAction(Icons.Filled.Settings, "Live TV sources") {
                    ProfileUi.guarded { if (wide) Nav.select(Tab.MORE) else Nav.open(Screen.SettingsPage(Section.LIVETV.name)) }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                val chips = listOf("All", "★ Favourites") + groups.take(40)
                items(chips) { g ->
                    val on = g == filter
                    val count = when (g) { "All" -> all.size; "★ Favourites" -> favourites.size; else -> null }
                    Text(listOfNotNull(g, count?.toString()).joinToString(" "), style = MaterialTheme.typography.labelMedium, modifier = Modifier
                        .card(RoundedCornerShape(50)) { filter = g }
                        .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        if (recent.isNotEmpty() && filter == "All" && query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Recently watched", Modifier.padding(top = 8.dp)) }
            items(recent.take(if (wide) 5 else 4), key = { "recent|" + it.key }) { c ->
                ChannelCard(c, now, guideVersion, onFocus = { selected = c }) { LivePlayback.play(scope, c, all) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(if (filter == "All") "All channels" else filter, Modifier.padding(top = 8.dp)) }
        }
        items(shown, key = { it.key }) { c ->
            ChannelCard(c, now, guideVersion, onFocus = { selected = c }) { LivePlayback.play(scope, c, shown) }
        }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState("No channels", if (query.isNotBlank()) "Nothing matches \"$query\"." else "This list is empty.", Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LiveBanner(c: LiveTv.Channel, number: Int, now: Long, guideVersion: Int, favourite: Boolean, wide: Boolean, onWatch: () -> Unit, onFavourite: () -> Unit) {
    val (cur, next) = remember(c, now, guideVersion) { LiveTv.nowNext(c, now) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 20.dp, bottom = 8.dp)
        .background(Brush.horizontalGradient(listOf(Palette.accent.copy(alpha = 0.10f), Color.Transparent)), RoundedCornerShape(16.dp)).padding(if (wide) 8.dp else 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveBadge()
            Text(listOfNotNull("Channel $number", c.group).joinToString(" · "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
        }
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c, if (wide) 92.dp else 64.dp)
            Text(c.name, style = if (wide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp))
        }
        if (cur != null) {
            val left = ((cur.stop - now) / 60_000).coerceAtLeast(0)
            Text("Now: ${cur.title}   ${time.format(Date(cur.start))} – ${time.format(Date(cur.stop))} · $left min left", style = MaterialTheme.typography.titleSmall)
            ProgressLine(progressOf(cur, now), Modifier.widthIn(max = 460.dp).padding(vertical = 8.dp))
            next?.let { Text("Next: ${it.title} · ${time.format(Date(it.start))}", style = MaterialTheme.typography.bodyMedium) }
        } else Text("No guide for this channel", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Watch live", Icons.Filled.PlayArrow, compact = !wide) { onWatch() }
            PillButton(if (favourite) "Favourite" else "Add to favourites", if (favourite) Icons.Filled.Star else Icons.Filled.StarBorder, primary = false, compact = !wide) { onFavourite() }
        }
    }
}

@Composable
private fun ChannelCard(c: LiveTv.Channel, now: Long, guideVersion: Int, onFocus: () -> Unit, onClick: () -> Unit) {
    val cur = remember(c, now, guideVersion) { LiveTv.nowNext(c, now).first }
    Column(Modifier.fillMaxWidth().card(RoundedCornerShape(12.dp), onFocus = onFocus, onClick = onClick)
        .background(Palette.surface, RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 8f).background(Brush.linearGradient(listOf(Palette.surfaceHigh, Palette.accent.copy(alpha = 0.18f)))),
            contentAlignment = Alignment.Center) {
            ChannelLogo(c, 58.dp)
            LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(10.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cur?.let { "Now: ${it.title}" } ?: (c.group ?: " "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cur != null) ProgressLine(progressOf(cur, now), Modifier.padding(top = 6.dp))
        }
    }
}
