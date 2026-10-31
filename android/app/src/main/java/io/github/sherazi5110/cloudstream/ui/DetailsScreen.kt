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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import io.github.sherazi5110.cloudstream.ContentFilter
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.Playable
import io.github.sherazi5110.cloudstream.Titles
import io.github.sherazi5110.cloudstream.Bookmark

@Composable
fun DetailsScreen(screen: Screen.Details) {
    val api = remember(screen) { Engine.provider(screen.apiName) }
    var response by remember(screen) { mutableStateOf(api?.let { Titles.cached(it, screen.url) }) }
    var error by remember(screen) { mutableStateOf<String?>(null) }
    var attempt by remember(screen) { mutableIntStateOf(0) }

    LaunchedEffect(screen, attempt) {
        if (response != null) return@LaunchedEffect
        if (api == null) { error = "The extension ${screen.apiName} isn't installed."; return@LaunchedEffect }
        error = null
        runCatching { Titles.load(api, screen.url) }
            .onSuccess { r -> if (r == null) error = "The extension returned nothing for this title." else response = r }
            .onFailure { error = it.message ?: it.toString() }
    }

    val r = response
    when {
        api != null && r != null && !ContentFilter.allows(r) -> Column { BackBar(""); EmptyState("Hidden", "Adult content is turned off.", Modifier.fillMaxWidth()) }
        api != null && r != null -> Details(api, r)
        error != null -> Column {
            BackBar(screen.name)
            EmptyState("Couldn't load this title", error!!, Modifier.fillMaxWidth(), button = "Try again") { attempt++ }
        }
        else -> Box(Modifier.fillMaxSize()) {
            NetImage(screen.posterUrl, Modifier.fillMaxWidth().height(320.dp))
            Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Palette.background)))
            BackBar("")
            Spinner(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Details(api: MainAPI, r: LoadResponse) {
    // Re-read progress, bookmarks and watched marks whenever the library changes (e.g. after playing).
    var libraryVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val off = Library.onChange { libraryVersion++ }
        onDispose { off() }
    }
    val context = LocalContext.current
    val playables = remember(r) { Engine.playables(r) }
    val isSeries = r is TvSeriesLoadResponse || r is AnimeLoadResponse
    val dubs = remember(playables) { playables.map { it.dub }.distinct() }
    var dub by remember(r) { mutableStateOf(dubs.firstOrNull()) }
    val seasons = remember(playables, dub) { playables.filter { it.dub == dub }.map { it.season }.distinct() }
    val target = remember(libraryVersion, r) { Playback.resumeTarget(api, r) }
    var season by remember(r) { mutableStateOf(target?.season ?: seasons.firstOrNull()) }
    val episodes = playables.filter { it.dub == dub && it.season == season }
    val bookmarked = remember(libraryVersion, r) { Library.isBookmarked(api.name, r.url) }
    val allWatched = remember(libraryVersion, r) { playables.isNotEmpty() && playables.all { Library.isWatched(api.name, it.data) } }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { playFocus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().height(if (isTv) 300.dp else 300.dp)) {
                NetImage(r.backgroundPosterUrl ?: r.posterUrl, Modifier.fillMaxSize(), r.posterHeaders)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.4f), 0.35f to Color.Transparent, 1f to Palette.background)))
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.statusBarsPadding().padding(12.dp)
                    .size(44.dp).card(CircleShape) { Nav.back() }.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(10.dp))
            }
        }
        item(key = "info") {
            Column(Modifier.padding(horizontal = if (isTv) 48.dp else 20.dp).widthIn(max = 900.dp)) {
                if (r.logoUrl != null && isTv) NetImage(r.logoUrl, Modifier.height(90.dp).width(320.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                else Text(r.name, style = MaterialTheme.typography.displayMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    r.score?.toString(10)?.takeIf { it != "0" }?.let { Pill(it, label = "★", labelColor = Palette.imdb) }
                    r.contentRating?.takeIf { it.isNotBlank() }?.let { Pill(it, outlined = true) }
                    val facts = listOfNotNull(
                        r.year?.toString(),
                        if (isSeries) seasons.filterNotNull().size.takeIf { it > 1 }?.let { "$it Seasons" } ?: "${playables.size} episodes"
                        else r.duration?.takeIf { it > 0 }?.let(::formatMinutes),
                        api.name,
                    )
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                }
                PillButton(
                    Playback.resumeLabel(api, r, target), Icons.Filled.PlayArrow,
                    Modifier.fillMaxWidth().focusRequester(playFocus),
                ) { target?.let { Playback.play(api, r, it) } ?: io.github.sherazi5110.cloudstream.App.toast("Nothing to play yet") }
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CircleAction(if (bookmarked) Icons.Filled.Check else Icons.Filled.Add, "My list") {
                        Library.toggleBookmark(Bookmark(api.name, r.url, r.name, r.posterUrl, r.type.name, System.currentTimeMillis()))
                    }
                    CircleAction(if (allWatched) Icons.Outlined.CheckCircle else Icons.Filled.Check, if (allWatched) "Watched" else "Mark watched",
                        tint = if (allWatched) Palette.watched else Color.White) {
                        if (allWatched) Library.markUnwatched(api.name, r.url, playables.map { it.data })
                        else Library.markWatched(api.name, r.url, r.name, r.posterUrl, playables.map { it.data to it.label })
                    }
                    r.trailers.firstOrNull()?.let { trailer ->
                        CircleAction(Icons.Outlined.Movie, "Trailer") {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(trailer.extractorUrl))) }
                        }
                    }
                    CircleAction(Icons.AutoMirrored.Filled.List, "Sources") { target?.let { Playback.play(api, r, it, chooseSource = true) } }
                }
                r.plot?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.muted) }
                r.tags?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.take(6).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (isSeries) {
            if (dubs.size > 1) item(key = "dubs") { Chips(dubs.map { it ?: "Default" }, dubs.indexOf(dub)) { dub = dubs[it] } }
            if (seasons.size > 1) item(key = "seasons") {
                Chips(seasons.map { s -> s?.let { "Season $it" } ?: "Episodes" }, seasons.indexOf(season)) { season = seasons[it] }
            }
            items(episodes, key = { "ep|${it.dub}|${it.data}" }) { ep ->
                EpisodeRow(api, r, ep, libraryVersion)
            }
        }
        r.recommendations?.filter(ContentFilter::allows)?.takeIf { it.isNotEmpty() }?.let { recs ->
            item(key = "recs") {
                Column(Modifier.padding(top = 20.dp)) {
                    SectionTitle("More like this", Modifier.padding(horizontal = if (isTv) 48.dp else 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recs, key = { it.url }) { item -> PosterCard(item, if (isTv) 130.dp else 108.dp) { openTitle(item) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels.size) { i ->
            val shape = RoundedCornerShape(50)
            Text(labels[i], style = MaterialTheme.typography.labelMedium, modifier = Modifier
                .card(shape) { onSelect(i) }
                .background(if (i == selected) Palette.accent else Color.Transparent, shape)
                .then(if (i == selected) Modifier else Modifier.background(Palette.surfaceHigh, shape))
                .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun EpisodeRow(api: MainAPI, r: LoadResponse, ep: Playable, libraryVersion: Int) {
    val progress = remember(libraryVersion, ep) { Library.progress(api.name, ep.data) }
    val watched = remember(libraryVersion, ep) { Library.isWatched(api.name, ep.data) }
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (isTv) 44.dp else 16.dp, vertical = 4.dp)
                .card(RoundedCornerShape(10.dp), focusScale = 1.02f, onLongClick = { menu = true }) { Playback.play(api, r, ep) }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(if (isTv) 180.dp else 128.dp).aspectRatio(16f / 9f).background(Palette.surfaceHigh, RoundedCornerShape(8.dp))) {
                NetImage(ep.posterUrl ?: r.backgroundPosterUrl ?: r.posterUrl, Modifier.fillMaxSize(), r.posterHeaders)
                if (progress != null && progress.duration > 0) ProgressLine((progress.position / progress.duration).toFloat(), Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                val number = ep.episode?.let { "$it. " }.orEmpty()
                Text(number + (ep.name ?: "Episode ${ep.episode ?: ""}"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val left = progress?.takeIf { !watched && it.duration > 0 && it.position > 30 }?.let { "${((it.duration - it.position) / 60).toInt()}m left" }
                listOfNotNull(left).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                ep.description?.takeIf { it.isNotBlank() && isTv }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (watched) Icon(Icons.Filled.Check, "Watched", tint = Palette.watched, modifier = Modifier.padding(end = 8.dp))
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (watched) DropdownMenuItem(text = { Text("Mark as not watched") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = { menu = false; Library.markUnwatched(api.name, r.url, listOf(ep.data)) })
            else DropdownMenuItem(text = { Text("Mark as watched") }, leadingIcon = { Icon(Icons.Filled.Check, null) },
                onClick = { menu = false; Library.markWatched(api.name, r.url, r.name, r.posterUrl, listOf(ep.data to ep.label)) })
            DropdownMenuItem(text = { Text("Choose source") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                onClick = { menu = false; Playback.play(api, r, ep, chooseSource = true) })
        }
    }
}
