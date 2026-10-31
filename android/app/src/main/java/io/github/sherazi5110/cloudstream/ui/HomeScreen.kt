package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
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
    if (isWide) WideHome(state) else {
        PhoneHome(state)
        if (state.editing) HomeEditor(state) { state.editing = false }
    }
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
            EditCatalogsButton(state)
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
    val edge = if (isWide) 40.dp else 16.dp
    Column(Modifier.padding(top = 12.dp)) {
        SectionTitle("Continue Watching", Modifier.padding(horizontal = edge))
        LazyRow(
            contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp),
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
    val edge = if (isWide) 40.dp else 16.dp
    Column(Modifier.padding(top = 12.dp)) {
        val viewAll = { Nav.open(Screen.Catalog(section.api.name, section.section.name, section.list.name)) }
        // On TV the poster-sized tile at the end of the row is the way in; touch also gets the link.
        SectionTitle(
            section.list.name, Modifier.padding(horizontal = edge),
            action = if (isTv) null else "View all",
        ) { viewAll() }
        LazyRow(
            contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isWide) 14.dp else 10.dp),
        ) {
            items(section.list.list, key = { it.url }) { item ->
                PosterCard(item, posterWidth, onFocus = onFocus?.let { { it(item) } }) { openTitle(item) }
            }
            item(key = "view-all") { ViewAllTile(section.list.name, posterWidth) { viewAll() } }
        }
    }
}

// ---------------------------------------------------------------- wide (landscape, TV)

/**
 * The Linux app's Home: a full-width banner with the title on the left (extension, logo or name, facts,
 * "#1 in …", plot, Play / More Info) and dots on the right, then Continue Watching and the catalogs.
 * The banner slides by itself and can be swiped; on TV it follows the focused card.
 */
@Composable
private fun WideHome(state: HomeState) {
    var focused by remember { mutableStateOf<SearchResponse?>(null) }
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bannerHeight = (maxHeight * 0.74f).coerceIn(340.dp, 640.dp)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "banner") { WideBanner(state, focused, bannerHeight) }
            if (state.continueWatching.isNotEmpty()) item(key = "continue") {
                ContinueRow(state.continueWatching, 280.dp)
            }
            items(state.sections, key = { "${it.api.name}|${it.order}" }) { section ->
                CatalogRow(section, 140.dp, onFocus = if (isTv) { item -> focused = item } else null)
            }
            if (state.loading && state.sections.isEmpty()) item { Spinner(Modifier.fillMaxWidth().height(200.dp)) }
        }
        // Fixed top bar; it gets a backdrop once the banner has scrolled away.
        val scrolled = list.firstVisibleItemIndex > 0
        TopBar(
            Modifier.background(if (scrolled) Palette.background.copy(alpha = 0.94f) else Color.Transparent),
        ) {
            SourcePicker(state)
            Spacer(Modifier.width(10.dp))
            EditCatalogsButton(state)
        }
    }
    if (state.editing) HomeEditor(state) { state.editing = false }
}

@Composable
private fun WideBanner(state: HomeState, focused: SearchResponse?, height: androidx.compose.ui.unit.Dp) {
    val featured = state.featured
    val pager = rememberPagerState { featured.size.coerceAtLeast(1) }
    // Slides every 9 seconds (unless a card on TV holds the banner).
    LaunchedEffect(featured.size, focused) {
        while (featured.size > 1 && focused == null) {
            delay(9_000)
            pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
        }
    }
    val rowName = state.sections.firstOrNull()?.list?.name
    Box(Modifier.fillMaxWidth().height(height)) {
        if (featured.isEmpty()) Box(Modifier.fillMaxSize().background(Palette.surface))
        else if (focused != null) BannerPage(focused, featured.indexOfFirst { it.url == focused.url }.takeIf { it >= 0 }, rowName)
        else HorizontalPager(pager, Modifier.fillMaxSize()) { page -> BannerPage(featured[page], page, rowName) }
        if (featured.size > 1 && focused == null) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(featured.size) { i ->
                val selected = pager.currentPage == i
                Box(Modifier.size(width = if (selected) 22.dp else 7.dp, height = 7.dp)
                    .background(if (selected) Palette.accent else Color.White.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

@Composable
private fun BannerPage(item: SearchResponse, rank: Int?, rowName: String?) {
    val details = rememberDetails(item.apiName, item.url)
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv && rank == 0) runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        NetImage(details?.backgroundPosterUrl ?: details?.posterUrl ?: item.posterUrl, Modifier.fillMaxSize(),
            details?.posterHeaders ?: item.posterHeaders)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Palette.background.copy(alpha = 0.95f), 0.45f to Palette.background.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.45f), 0.2f to Color.Transparent, 0.6f to Color.Transparent, 1f to Palette.background)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp).widthIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(18.dp)
                Text(item.apiName.uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
            val logo = details?.logoUrl
            if (logo != null) NetImage(logo, Modifier.padding(vertical = 12.dp).height(96.dp).widthIn(max = 460.dp).fillMaxWidth(),
                contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, placeholder = false)
            else Text(item.name, style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 10.dp))
            MetaLine(item, details)
            if (rank != null && rowName != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tv, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("#${rank + 1} in $rowName", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            details?.plot?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Play", Icons.Filled.PlayArrow, Modifier.focusRequester(playFocus), compact = true) { Playback.playFrom(scope, item) }
                PillButton("More Info", Icons.Filled.Info, primary = false, compact = true) { openTitle(item) }
            }
        }
    }
}

/** The last "poster" of every row: opens the whole catalog (easy to reach with a remote). */
@Composable
private fun ViewAllTile(title: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).card(onClick = onClick)
                .background(Palette.surfaceHigh, RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(48.dp).background(Palette.accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Palette.accent)
            }
            Text("View all", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        }
        Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp))
    }
}

/** Next to the extension picker: opens the Home screen editor. */
@Composable
private fun EditCatalogsButton(state: HomeState) {
    Row(
        Modifier.card(RoundedCornerShape(50)) { state.editing = true }
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tune, "Edit catalogs", tint = Color.White, modifier = Modifier.size(18.dp))
        if (isWide) Text("Catalogs", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Home screen editor (like the Linux app's): pick an extension, then turn its catalogs on or off and
 * reorder them. "Show on Home" also switches Home to that extension.
 */
@Composable
private fun HomeEditor(state: HomeState, onClose: () -> Unit) {
    var api by remember { mutableStateOf(state.api ?: state.providers.firstOrNull()) }
    val entries = remember(api) { androidx.compose.runtime.mutableStateListOf<io.github.sherazi5110.cloudstream.Catalogs.Entry>().apply {
        api?.let { addAll(io.github.sherazi5110.cloudstream.Catalogs.all(it)) }
    } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.9f)
                .background(Palette.surface, RoundedCornerShape(16.dp)).padding(20.dp),
        ) {
            Text("Home screen", style = MaterialTheme.typography.headlineSmall)
            Text("Pick an extension, then choose and order its catalogs.", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(state.providers, key = { it.name }) { p ->
                    val selected = p == api
                    Text(p.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier
                        .card(RoundedCornerShape(50)) { api = p }
                        .background(if (selected) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
                items(entries.size, key = { entries[it].section.name }) { i ->
                    val e = entries[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).card(RoundedCornerShape(8.dp), focusScale = 1.02f) { entries[i] = e.copy(enabled = !e.enabled) }
                            .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (e.enabled) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                                tint = if (e.enabled) Palette.accent else Palette.muted)
                            Text(e.section.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp), color = if (e.enabled) Color.White else Palette.muted)
                        }
                        IconAction(Icons.Filled.KeyboardArrowUp, "Move up") { if (i > 0) { entries.removeAt(i); entries.add(i - 1, e) } }
                        IconAction(Icons.Filled.KeyboardArrowDown, "Move down") { if (i < entries.lastIndex) { entries.removeAt(i); entries.add(i + 1, e) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Reset", null, primary = false, compact = true) {
                    api?.let { a -> io.github.sherazi5110.cloudstream.Catalogs.reset(a); entries.clear(); entries.addAll(io.github.sherazi5110.cloudstream.Catalogs.all(a)) }
                }
                PillButton("Cancel", null, primary = false, compact = true) { onClose() }
                PillButton("Save & show", Icons.Filled.Check, compact = true) {
                    api?.let { a ->
                        io.github.sherazi5110.cloudstream.Catalogs.save(a, entries.toList())
                        if (a != state.api) state.choose(a)
                    }
                    onClose()
                }
            }
        }
    }
}
