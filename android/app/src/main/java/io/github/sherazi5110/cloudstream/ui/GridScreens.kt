package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.Catalogs
import io.github.sherazi5110.cloudstream.ContentFilter
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import kotlinx.coroutines.delay

/** A title bar with a back arrow, for pushed screens. */
@Composable
fun BackBar(title: String, modifier: Modifier = Modifier) {
    Row(modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
            modifier = Modifier.size(44.dp).card(CircleShape) { Nav.back() }.padding(10.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

private val gridCells get() = if (isTv) GridCells.Adaptive(140.dp) else GridCells.Adaptive(108.dp)

/** "See all": a catalog's pages, loading the next one near the bottom until the extension says there are no more. */
@Composable
fun CatalogScreen(screen: Screen.Catalog) {
    val api = remember(screen) { Engine.provider(screen.apiName) }
    val section = remember(api) { api?.let { Catalogs.all(it).firstOrNull { e -> e.section.name == screen.sectionName }?.section } }
    val items = remember(screen) { mutableStateListOf<SearchResponse>() }
    var page by remember(screen) { mutableStateOf(0) }
    var hasNext by remember(screen) { mutableStateOf(true) }
    var loading by remember(screen) { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= items.size - 12 } }

    LaunchedEffect(nearEnd, hasNext, loading, page) {
        if (api == null || section == null || !hasNext || loading || !nearEnd) return@LaunchedEffect
        loading = true
        val response = Engine.homePage(api, section, page + 1)
        val list = response?.items?.firstOrNull { it.name == screen.title } ?: response?.items?.firstOrNull()
        val fresh = list?.list.orEmpty().filter(ContentFilter::allows).filter { n -> items.none { it.url == n.url } }
        items.addAll(fresh)
        hasNext = response?.hasNext == true && fresh.isNotEmpty()
        page++
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(screen.title)
        LazyVerticalGrid(gridCells, state = grid, contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.url }) { item -> PosterCard(item, 200.dp, Modifier.fillMaxWidth()) { openTitle(item) } }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Spinner(Modifier.fillMaxWidth().height(80.dp)) }
        }
    }
}

/** Search across every extension; each extension's results appear as soon as it answers. */
@Composable
fun SearchScreen() {
    val results = remember { mutableStateListOf<Pair<MainAPI, List<SearchResponse>>>() }
    var searching by remember { mutableStateOf(0) }
    val query = Nav.searchQuery
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(query) {
        results.clear()
        if (query.isBlank()) return@LaunchedEffect
        delay(450) // search when typing pauses
        val apis = Engine.providers()
        searching = apis.size
        Engine.search(query.trim(), apis).collect { batch ->
            searching--
            if (batch.results.isNotEmpty()) results.add(batch.api to batch.results)
        }
        searching = 0
    }
    LaunchedEffect(Unit) { if (!isTv && query.isBlank()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { Nav.searchQuery = it },
            placeholder = { Text("Search films, series, anime…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", Modifier.card(CircleShape) { Nav.searchQuery = "" }.padding(6.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.surface, focusedContainerColor = Palette.surface,
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.accent),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
        )
        when {
            query.isBlank() -> EmptyState("Search", "Type a title; every installed extension is searched at once.", Modifier.fillMaxWidth())
            results.isEmpty() && searching > 0 -> Spinner(Modifier.fillMaxWidth().height(200.dp))
            results.isEmpty() -> EmptyState("Nothing found", "No extension had results for \"$query\".", Modifier.fillMaxWidth())
            else -> androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.first.name }) { (api, list) ->
                    Column(Modifier.padding(top = 8.dp)) {
                        SectionTitle("${api.name} · ${list.size}", Modifier.padding(horizontal = 16.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(list, key = { it.url }) { item -> PosterCard(item, if (isTv) 130.dp else 112.dp) { openTitle(item) } }
                        }
                    }
                }
                if (searching > 0) item { Text("Still searching $searching extension(s)…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

/** My list (bookmarks) and history. Phase 2 adds profiles and watched controls here. */
@Composable
fun LibraryScreen() {
    var bookmarks by remember { mutableStateOf(Library.bookmarks()) }
    var history by remember { mutableStateOf(Library.history()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val off = Library.onChange { bookmarks = Library.bookmarks(); history = Library.history() }
        onDispose { off() }
    }
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("My list", "History").forEachIndexed { i, label ->
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier
                    .card(RoundedCornerShape(50)) { tab = i }
                    .background(if (tab == i) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (tab == 0) {
            if (bookmarks.isEmpty()) EmptyState("Your list is empty", "Tap + My list on a title page to keep it here.", Modifier.fillMaxWidth())
            else LazyVerticalGrid(gridCells, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(bookmarks, key = { "${it.apiName}|${it.url}" }) { b ->
                    Column {
                        Box(Modifier.fillMaxWidth().height(if (isTv) 210.dp else 160.dp).card { Nav.open(Screen.Details(b.apiName, b.url, b.name, b.posterUrl)) }) {
                            NetImage(b.posterUrl, Modifier.fillMaxSize())
                        }
                        Text(b.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        } else {
            if (history.isEmpty()) EmptyState("No history yet", "Titles you watch appear here.", Modifier.fillMaxWidth())
            else LazyVerticalGrid(if (isTv) GridCells.Adaptive(260.dp) else GridCells.Adaptive(170.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history, key = { "${it.apiName}|${it.url}" }) { e ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        WideCard(e.name, if (e.finished) "Watched" else e.leftText(), e.posterUrl, e.progress.toFloat(), 400.dp,
                            Modifier.fillMaxWidth(), onLongClick = { menu = true }) { openTitle(e) }
                        WatchedMenu(menu, e) { menu = false }
                    }
                }
            }
        }
    }
}
