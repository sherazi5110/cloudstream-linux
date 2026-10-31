package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.WatchEntry

@Composable
fun HomeScreen() {
    val state = rememberHomeState()
    if (state.providers.isEmpty()) {
        NoExtensions()
        return
    }
    if (isTv) TvHome(state) else PhoneHome(state)
}

@Composable
private fun NoExtensions() {
    val progress by io.github.sherazi5110.cloudstream.PluginManager.progress.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (progress.running) Spinner()
        else EmptyState(
            "Welcome to CloudStream",
            "Add a repository and install an extension to fill Home. Extensions are the same ones the official app uses.",
            button = "Add extensions",
        ) { Nav.select(Tab.EXTENSIONS) }
    }
}

// ---------------------------------------------------------------- phone

@Composable
private fun PhoneHome(state: HomeState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") { PhoneHero(state) }
        if (state.continueWatching.isNotEmpty()) item(key = "continue") {
            ContinueRow(state.continueWatching, cardWidth = 200.dp)
        }
        items(state.sections, key = { "${it.api.name}|${it.order}" }) { section ->
            CatalogRow(section, posterWidth = 112.dp)
        }
        if (state.loading && state.sections.isEmpty()) item { Spinner(Modifier.fillMaxWidth().height(200.dp)) }
    }
}

@Composable
private fun PhoneHero(state: HomeState) {
    val featured = state.featured
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { featured.size.coerceAtLeast(1) }
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        if (featured.isEmpty()) Box(Modifier.fillMaxSize().background(Palette.surface))
        else HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val item = featured[page]
            val details = rememberDetails(item.apiName, item.url)
            Box(Modifier.fillMaxSize()) {
                NetImage(details?.backgroundPosterUrl ?: details?.posterUrl ?: item.posterUrl, Modifier.fillMaxSize(),
                    details?.posterHeaders ?: item.posterHeaders)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Palette.background)))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
                    state.sections.firstOrNull()?.let {
                        Text("#${page + 1} in ${it.list.name}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(item.name, style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 6.dp))
                    MetaLine(item, details)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Play", Icons.Filled.PlayArrow, Modifier.weight(1f)) { Playback.playFrom(scope, item) }
                        PillButton("More Info", Icons.Filled.Info, Modifier.weight(1f), primary = false) { openTitle(item) }
                    }
                }
            }
        }
        // Top bar over the banner: logo, extension picker, search.
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Logo(34.dp)
            SourcePicker(state)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Search, "Search", tint = Color.White,
                modifier = Modifier.size(40.dp).card(CircleShape) { Nav.select(Tab.SEARCH) }.padding(8.dp))
        }
        if (featured.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(featured.size) { i ->
                val selected = pager.currentPage == i
                Box(Modifier.size(width = if (selected) 18.dp else 6.dp, height = 6.dp)
                    .background(if (selected) Palette.accent else Color.White.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

/** "2026 · 2h 4m · PG-13 · ★ 7.6" */
@Composable
fun MetaLine(item: SearchResponse?, details: LoadResponse?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        details?.year?.let { Text("$it", style = MaterialTheme.typography.titleSmall) }
        details?.duration?.takeIf { it > 0 }?.let { Text(formatMinutes(it), style = MaterialTheme.typography.titleSmall) }
        details?.contentRating?.takeIf { it.isNotBlank() }?.let { Pill(it, outlined = true) }
        (details?.score?.toString(10) ?: item?.scoreText())?.takeIf { it != "0" }?.let {
            Text("★ $it", style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** The extension Home shows, as a dropdown chip. */
@Composable
fun SourcePicker(state: HomeState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.card(RoundedCornerShape(50)) { open = true }
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.api?.name ?: "Source", style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            state.providers.forEach { api ->
                DropdownMenuItem(
                    text = { Text(api.name) },
                    leadingIcon = { if (api == state.api) Icon(Icons.Filled.Check, null, tint = Palette.accent) },
                    onClick = { open = false; state.choose(api) },
                )
            }
        }
    }
}

@Composable
fun ContinueRow(entries: List<WatchEntry>, cardWidth: androidx.compose.ui.unit.Dp, onFocus: ((WatchEntry) -> Unit)? = null) {
    Column(Modifier.padding(top = 12.dp)) {
        SectionTitle("Continue Watching", Modifier.padding(horizontal = if (isTv) 0.dp else 16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = if (isTv) 4.dp else 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { "${it.apiName}|${it.url}" }) { e ->
                var menu by remember { mutableStateOf(false) }
                // The remote's Menu key opens the same menu as a long press.
                Box(Modifier.onPreviewKeyEvent {
                    if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && it.type == KeyEventType.KeyUp) { menu = true; true } else false
                }) {
                    WideCard(
                        e.name, e.leftText(), e.posterUrl, e.progress.toFloat(), cardWidth,
                        onFocus = onFocus?.let { { it(e) } },
                        onLongClick = { menu = true },
                        onClick = { openTitle(e) },
                    )
                    WatchedMenu(menu, e) { menu = false }
                }
            }
        }
    }
}

/** Long-press / Menu key on a Continue Watching card: ✓ watched or ✕ remove. */
@Composable
fun WatchedMenu(open: Boolean, e: WatchEntry, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Mark as watched") }, leadingIcon = { Icon(Icons.Filled.Check, null) },
            onClick = { onDismiss(); Library.markWatched(e) })
        DropdownMenuItem(text = { Text("Remove from Continue Watching") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
            onClick = { onDismiss(); Library.removeHistory(e.apiName, e.url) })
    }
}

@Composable
fun CatalogRow(
    section: io.github.sherazi5110.cloudstream.Engine.HomeSection,
    posterWidth: androidx.compose.ui.unit.Dp,
    onFocus: ((SearchResponse) -> Unit)? = null,
) {
    Column(Modifier.padding(top = 12.dp)) {
        SectionTitle(
            section.list.name, Modifier.padding(horizontal = if (isTv) 0.dp else 16.dp),
            action = "See all",
        ) { Nav.open(Screen.Catalog(section.api.name, section.section.name, section.list.name)) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = if (isTv) 4.dp else 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
        ) {
            items(section.list.list, key = { it.url }) { item ->
                PosterCard(item, posterWidth, onFocus = onFocus?.let { { it(item) } }) { openTitle(item) }
            }
        }
    }
}

// ---------------------------------------------------------------- TV

@Composable
private fun TvHome(state: HomeState) {
    var focused by remember { mutableStateOf<SearchResponse?>(null) }
    var focusedEntry by remember { mutableStateOf<WatchEntry?>(null) }
    val shown = focused ?: state.featured.firstOrNull()
    val details = rememberDetails(focusedEntry?.apiName ?: shown?.apiName, focusedEntry?.url ?: shown?.url)
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val hasFeatured = shown != null
    LaunchedEffect(hasFeatured) { if (hasFeatured) runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        // The banner follows the focused title.
        Box(Modifier.fillMaxWidth().height(460.dp)) {
            NetImage(details?.backgroundPosterUrl ?: details?.posterUrl ?: focusedEntry?.posterUrl ?: shown?.posterUrl,
                Modifier.fillMaxSize(), details?.posterHeaders ?: shown?.posterHeaders)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.background, 0.55f to Palette.background.copy(alpha = 0.3f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Palette.background)))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 48.dp, end = 32.dp, bottom = 32.dp)) {
            item(key = "banner") {
                Column(Modifier.padding(top = 28.dp).height(330.dp).fillMaxWidth(0.6f)) {
                    SourcePicker(state)
                    Spacer(Modifier.height(18.dp))
                    Text(focusedEntry?.name ?: shown?.name.orEmpty(), style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MetaLine(shown, details, Modifier.padding(vertical = 8.dp))
                    Text(details?.plot.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val entry = focusedEntry
                    if (shown != null || entry != null) Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillButton("Play", Icons.Filled.PlayArrow, Modifier.focusRequester(playFocus)) { if (entry != null) openTitle(entry) else Playback.playFrom(scope, shown!!) }
                        PillButton("More Info", Icons.Filled.Info, primary = false) { if (entry != null) openTitle(entry) else openTitle(shown!!) }
                    }
                }
            }
            if (state.continueWatching.isNotEmpty()) item(key = "continue") {
                ContinueRow(state.continueWatching, 260.dp) { focusedEntry = it; focused = null }
            }
            items(state.sections, key = { "${it.api.name}|${it.order}" }) { section ->
                CatalogRow(section, 130.dp) { focused = it; focusedEntry = null }
            }
            if (state.loading && state.sections.isEmpty()) item { Spinner(Modifier.fillMaxWidth().height(200.dp)) }
        }
    }
}
