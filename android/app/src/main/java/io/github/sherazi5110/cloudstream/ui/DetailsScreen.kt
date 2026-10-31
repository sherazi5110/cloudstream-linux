package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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

/**
 * The title page, like the Linux app's: the artwork blurred behind everything, the poster next to the
 * title (or its logo), ratings, plot, genres and one row of actions; then cast, episodes and similar titles.
 */
@Composable
private fun Details(api: MainAPI, r: LoadResponse) {
    // Re-read progress, bookmarks and watched marks whenever the library changes (e.g. after playing).
    var libraryVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val off = Library.onChange { libraryVersion++ }
        onDispose { off() }
    }
    val wide = isWide
    val edge = if (wide) 40.dp else 16.dp
    val context = LocalContext.current
    val playables = remember(r) { Engine.playables(r) }
    val isSeries = r is TvSeriesLoadResponse || r is AnimeLoadResponse
    val dubs = remember(playables) { playables.map { it.dub }.distinct() }
    var dub by remember(r) { mutableStateOf(dubs.firstOrNull()) }
    val seasons = remember(playables, dub) { playables.filter { it.dub == dub }.groupBy { it.season } }
    val target = remember(libraryVersion, r) { Playback.resumeTarget(api, r) }
    var season by remember(r) { mutableStateOf(target?.season ?: seasons.keys.firstOrNull()) }
    val episodes = seasons[season].orEmpty()
    val bookmarked = remember(libraryVersion, r) { Library.isBookmarked(api.name, r.url) }
    val allWatched = remember(libraryVersion, r) { playables.isNotEmpty() && playables.all { Library.isWatched(api.name, it.data) } }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { playFocus.requestFocus() } }
    val meta = io.github.sherazi5110.cloudstream.Metadata
    var matchVersion by remember { mutableIntStateOf(0) }
    var matching by remember { mutableStateOf(false) }
    if (matching) MatchDialog(r, onClose = { matching = false }, onChanged = { matchVersion++ })
    val info = rememberMetadata(r, matchVersion)
    val art = meta.artwork
    val extras = meta.extras
    val backdrop = (if (art) info?.backdrop else null) ?: r.backgroundPosterUrl ?: r.posterUrl
    val plot = r.plot?.takeIf { it.isNotBlank() } ?: info?.overview
    val trailerUrl = r.trailers.firstOrNull()?.extractorUrl ?: info?.trailer?.takeIf { extras }

    val pills: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            info?.tmdbVote?.takeIf { it > 0 }?.let { Pill("%.1f".format(it), label = "TMDB", labelColor = Palette.tmdb) }
            info?.imdbRating?.let { Pill("%.1f".format(it), label = "IMDb", labelColor = Palette.imdb) }
            info?.anilistScore?.let { Pill("%.1f".format(it), label = "AniList", labelColor = Color(0xFF3DB4F2)) }
            if (info == null) r.score?.toString(10)?.takeIf { it != "0" }?.let { Pill(it, label = "★", labelColor = Palette.imdb) }
            Tag(r.type.name)
            r.year?.let { Tag("$it") }
            (r.contentRating?.takeIf { it.isNotBlank() } ?: info?.contentRating)?.let { Tag(it) }
            if (isSeries) Tag("${playables.size} episodes")
            else r.duration?.takeIf { it > 0 }?.let { Tag(formatMinutes(it)) }
            if (meta.hasTmdb) Text(if (info?.tmdbId != null) "Wrong match?" else "Find on TMDB", color = Palette.muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.card(RoundedCornerShape(6.dp), focusScale = 1.05f) { matching = true }.padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }
    val actions: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wide) PlayButton(Playback.resumeLabel(api, r, target), Modifier.focusRequester(playFocus)) {
                target?.let { Playback.play(api, r, it) } ?: io.github.sherazi5110.cloudstream.App.toast("Nothing to play yet")
            }
            ActionButton(Icons.AutoMirrored.Filled.List, "Sources") { target?.let { Playback.play(api, r, it, chooseSource = true) } }
            trailerUrl?.let { url ->
                ActionButton(Icons.Filled.PlayCircle, "Trailer") {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                }
            }
            playables.singleOrNull()?.takeIf { api !is io.github.sherazi5110.cloudstream.OfflineApi }?.let { only ->
                val dv = DownloadsUi.version()
                val item = remember(dv) { io.github.sherazi5110.cloudstream.Downloads.get(api.name, only.data) }
                var dlMenu by remember { mutableStateOf(false) }
                Box {
                    ActionButton(DownloadsUi.icon(item), DownloadsUi.label(item), tint = if (item?.state == io.github.sherazi5110.cloudstream.Downloads.State.DONE) Palette.watched else Color.White) {
                        if (item == null) DownloadsUi.start(context, api, r, only) else dlMenu = true
                    }
                    DownloadsUi.Menu(dlMenu, item) { dlMenu = false }
                }
            }
            ActionButton(Icons.Filled.Check, if (allWatched) "Watched" else "Mark as watched", tint = if (allWatched) Palette.watched else Color.White) {
                if (allWatched) Library.markUnwatched(api.name, r.url, playables.map { it.data })
                else Library.markWatched(api.name, r.url, r.name, r.posterUrl, playables.map { it.data to it.label })
            }
            ActionButton(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (bookmarked) "In My list" else "Add to My list") {
                Library.toggleBookmark(Bookmark(api.name, r.url, r.name, r.posterUrl, r.type.name, System.currentTimeMillis()))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The artwork, blurred and dimmed, behind the whole page.
        NetImage(backdrop, Modifier.fillMaxSize().blur(24.dp), r.posterHeaders, placeholder = false)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Palette.background.copy(alpha = 0.55f), 0.5f to Palette.background.copy(alpha = 0.82f), 1f to Palette.background.copy(alpha = 0.95f))))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (wide) ((maxWidth - edge * 2) / 270.dp).toInt().coerceIn(2, 6) else 1
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "top") {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = edge - 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier
                            .size(44.dp).card(CircleShape) { Nav.back() }.background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(10.dp))
                        Spacer(Modifier.weight(1f))
                        Text(api.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier
                            .border(1.dp, Palette.outline, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                item(key = "hero") {
                    if (wide) Row(Modifier.padding(horizontal = edge, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NetImage(r.posterUrl, Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)), r.posterHeaders)
                        Column(Modifier.padding(start = 32.dp).widthIn(max = 860.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            TitleOrLogo(r, 110.dp, info?.logo?.takeIf { art })
                            pills()
                            plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                            r.tags?.takeIf { it.isNotEmpty() }?.let { Genres(it) }
                            actions()
                        }
                    } else Column(Modifier.padding(horizontal = edge), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            NetImage(r.posterUrl, Modifier.width(118.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)), r.posterHeaders)
                            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TitleOrLogo(r, 64.dp, info?.logo?.takeIf { art })
                                pills()
                            }
                        }
                        PlayButton(Playback.resumeLabel(api, r, target), Modifier.fillMaxWidth().focusRequester(playFocus)) {
                            target?.let { Playback.play(api, r, it) } ?: io.github.sherazi5110.cloudstream.App.toast("Nothing to play yet")
                        }
                        actions()
                        plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.muted) }
                        r.tags?.takeIf { it.isNotEmpty() }?.let { Genres(it) }
                    }
                }
                val cast = r.actors?.takeIf { it.isNotEmpty() }?.map { Triple(it.actor.name, it.roleString ?: it.voiceActor?.name, it.actor.image) }
                    ?: info?.cast?.takeIf { extras && it.isNotEmpty() }?.map { Triple(it.name, it.role, it.photo) }
                cast?.let { cast ->
                    item(key = "cast") {
                        Column(Modifier.padding(top = 24.dp)) {
                            SectionTitle("Cast", Modifier.padding(horizontal = edge))
                            LazyRow(contentPadding = PaddingValues(horizontal = edge, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(cast.take(30)) { (name, role, photo) ->
                                    Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        NetImage(photo, Modifier.size(92.dp).clip(CircleShape))
                                        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                        role?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (isSeries) {
                    item(key = "episodes-head") {
                        Row(Modifier.padding(start = edge, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Episodes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 14.dp))
                        }
                        if (dubs.size > 1) Chips(dubs.map { (it ?: "Default") to null }, dubs.indexOf(dub), edge) { dub = dubs[it] }
                        val keys = seasons.keys.toList()
                        if (keys.size > 1 || keys.firstOrNull() != null) Chips(
                            keys.map { k -> (k?.let { "Season $it" } ?: "Episodes") to seasons[k]!!.size }, keys.indexOf(season), edge,
                        ) { season = keys[it] }
                    }
                    if (wide) {
                        items(episodes.chunked(columns), key = { row -> "eps|${row.first().dub}|${row.first().data}" }) { row ->
                            Row(Modifier.padding(horizontal = edge, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                row.forEach { ep -> EpisodeCard(api, r, ep, libraryVersion, Modifier.weight(1f)) }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else items(episodes, key = { "ep|${it.dub}|${it.data}" }) { ep ->
                        EpisodeRow(api, r, ep, libraryVersion)
                    }
                }
                // TMDB's similar titles when the extension has none: they open Search.
                if (r.recommendations.isNullOrEmpty() && extras) info?.similar?.takeIf { it.isNotEmpty() }?.let { similar ->
                    item(key = "similar") {
                        Column(Modifier.padding(top = 24.dp)) {
                            SectionTitle("More like this", Modifier.padding(horizontal = edge))
                            LazyRow(contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(similar) { s ->
                                    Column(Modifier.width(if (wide) 140.dp else 108.dp)) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).card { Nav.searchQuery = s.title; Nav.select(Tab.SEARCH) }) {
                                            NetImage(s.poster, Modifier.fillMaxSize())
                                            s.vote?.takeIf { it > 0 }?.let { ScoreBadge("%.1f".format(it), Modifier.align(Alignment.TopStart).padding(6.dp)) }
                                        }
                                        Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                r.recommendations?.filter(ContentFilter::allows)?.takeIf { it.isNotEmpty() }?.let { recs ->
                    item(key = "recs") {
                        Column(Modifier.padding(top = 24.dp)) {
                            SectionTitle("More like this", Modifier.padding(horizontal = edge))
                            LazyRow(contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(recs, key = { it.url }) { item -> PosterCard(item, if (wide) 140.dp else 108.dp) { openTitle(item) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleOrLogo(r: LoadResponse, logoHeight: androidx.compose.ui.unit.Dp, metaLogo: String? = null) {
    var logoFailed by remember(r, metaLogo) { mutableStateOf(false) }
    val logo = r.logoUrl ?: metaLogo
    if (logo != null && !logoFailed) coil3.compose.AsyncImage(
        model = logo, contentDescription = r.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        alignment = Alignment.CenterStart, onError = { logoFailed = true },
        modifier = Modifier.height(logoHeight).widthIn(max = 420.dp),
    ) else Text(r.name, style = if (isWide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineSmall,
        maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** "TvSeries", "2022", "TV-14"… as small outlined tags. */
@Composable
private fun Tag(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier
        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 5.dp))
}

@Composable
private fun Genres(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.take(12).forEach { t ->
            Text(t, style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** The white main button: "Play S1E1", "Resume S1 E2 · 11:08". */
@Composable
private fun PlayButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.widthIn(min = 200.dp).card(shape, focusScale = 1.04f, onClick = onClick).background(Color.White, shape)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.Black, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.card(shape, focusScale = 1.04f, onClick = onClick)
            .background(Color.White.copy(alpha = 0.07f), shape).border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

/** Season (or dub) chips; the selected one is filled, each shows its episode count. */
@Composable
private fun Chips(labels: List<Pair<String, Int?>>, selected: Int, edge: androidx.compose.ui.unit.Dp, onSelect: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = edge, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels.size) { i ->
            val shape = RoundedCornerShape(50)
            val (label, count) = labels[i]
            Row(Modifier.card(shape) { onSelect(i) }
                .background(if (i == selected) Palette.accent else Color.White.copy(alpha = 0.08f), shape)
                .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                count?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 6.dp)) }
            }
        }
    }
}

private fun Playable.code(): String = listOfNotNull(season?.let { "S$it" }, episode?.let { "EPISODE $it" }).joinToString(" · ")

/** Wide: an episode card (still, "S1 · EPISODE 1", title, two lines of plot, progress, ✓). */
@Composable
private fun EpisodeCard(api: MainAPI, r: LoadResponse, ep: Playable, libraryVersion: Int, modifier: Modifier) {
    val progress = remember(libraryVersion, ep) { Library.progress(api.name, ep.data) }
    val watched = remember(libraryVersion, ep) { Library.isWatched(api.name, ep.data) }
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.04f, onLongClick = { menu = true }) { Playback.play(api, r, ep) }
            .background(Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                val hide = io.github.sherazi5110.cloudstream.Settings.antiSpoiler && !watched
                if (hide) Box(Modifier.fillMaxSize().background(Palette.surfaceHigh)) else
                NetImage(ep.posterUrl ?: r.backgroundPosterUrl ?: r.posterUrl, Modifier.fillMaxSize(), r.posterHeaders)
                if (watched) Icon(Icons.Filled.CheckCircle, "Watched", tint = Palette.watched, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                val dv = DownloadsUi.version()
                DownloadsUi.Badge(remember(dv, ep) { io.github.sherazi5110.cloudstream.Downloads.get(api.name, ep.data) }, Modifier.align(Alignment.TopStart).padding(8.dp))
                if (progress != null && progress.duration > 0 && !watched) ProgressLine((progress.position / progress.duration).toFloat(), Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(12.dp).height(72.dp)) {
                Text(ep.code(), style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                Text(ep.name ?: "Episode ${ep.episode ?: ""}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                ep.description?.takeIf { it.isNotBlank() && !(io.github.sherazi5110.cloudstream.Settings.antiSpoiler && !watched) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
            }
        }
        EpisodeMenu(menu, api, r, ep, watched) { menu = false }
    }
}

@Composable
private fun EpisodeMenu(open: Boolean, api: MainAPI, r: LoadResponse, ep: Playable, watched: Boolean, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dv = DownloadsUi.version()
    val item = remember(dv, ep) { io.github.sherazi5110.cloudstream.Downloads.get(api.name, ep.data) }
    var dlMenu by remember { mutableStateOf(false) }
    DownloadsUi.Menu(dlMenu, item) { dlMenu = false }
    DropdownMenu(open, onDismissRequest = onDismiss) {
        if (api !is io.github.sherazi5110.cloudstream.OfflineApi) DropdownMenuItem(text = { Text(if (item == null) "Download" else DownloadsUi.label(item) + "…") },
            leadingIcon = { Icon(DownloadsUi.icon(item), null) },
            onClick = { onDismiss(); if (item == null) DownloadsUi.start(context, api, r, ep) else dlMenu = true })
        if (watched) DropdownMenuItem(text = { Text("Mark as not watched") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
            onClick = { onDismiss(); Library.markUnwatched(api.name, r.url, listOf(ep.data)) })
        else DropdownMenuItem(text = { Text("Mark as watched") }, leadingIcon = { Icon(Icons.Filled.Check, null) },
            onClick = { onDismiss(); Library.markWatched(api.name, r.url, r.name, r.posterUrl, listOf(ep.data to ep.label)) })
        DropdownMenuItem(text = { Text("Choose source") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
            onClick = { onDismiss(); Playback.play(api, r, ep, chooseSource = true) })
    }
}

/** Portrait: one episode per line (still on the left). */
@Composable
private fun EpisodeRow(api: MainAPI, r: LoadResponse, ep: Playable, libraryVersion: Int) {
    val progress = remember(libraryVersion, ep) { Library.progress(api.name, ep.data) }
    val watched = remember(libraryVersion, ep) { Library.isWatched(api.name, ep.data) }
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .card(RoundedCornerShape(10.dp), focusScale = 1.02f, onLongClick = { menu = true }) { Playback.play(api, r, ep) }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                NetImage(ep.posterUrl ?: r.backgroundPosterUrl ?: r.posterUrl, Modifier.fillMaxSize(), r.posterHeaders)
                if (progress != null && progress.duration > 0 && !watched) ProgressLine((progress.position / progress.duration).toFloat(), Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ep.code(), style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                Text(ep.name ?: "Episode ${ep.episode ?: ""}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val left = progress?.takeIf { !watched && it.duration > 0 && it.position > 30 }?.let { "${((it.duration - it.position) / 60).toInt()}m left" }
                (left ?: ep.description?.takeIf { it.isNotBlank() })?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (watched) Icon(Icons.Filled.Check, "Watched", tint = Palette.watched, modifier = Modifier.padding(end = 8.dp))
            if (api !is io.github.sherazi5110.cloudstream.OfflineApi) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val dv = DownloadsUi.version()
                val item = remember(dv, ep) { io.github.sherazi5110.cloudstream.Downloads.get(api.name, ep.data) }
                var dlMenu by remember { mutableStateOf(false) }
                Box {
                    if (item == null) Icon(DownloadsUi.icon(null), "Download", tint = Palette.muted,
                        modifier = Modifier.size(40.dp).card(androidx.compose.foundation.shape.CircleShape) { DownloadsUi.start(context, api, r, ep) }.padding(9.dp))
                    else DownloadsUi.Badge(item, Modifier.card(RoundedCornerShape(50)) { dlMenu = true })
                    DownloadsUi.Menu(dlMenu, item) { dlMenu = false }
                }
            }
        }
        EpisodeMenu(menu, api, r, ep, watched) { menu = false }
    }
}
