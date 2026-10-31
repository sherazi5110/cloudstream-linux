package io.github.sherazi5110.cloudstream.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.PluginManager
import io.github.sherazi5110.cloudstream.Settings
import io.github.sherazi5110.cloudstream.Titles
import io.github.sherazi5110.cloudstream.WatchEntry
import kotlinx.coroutines.flow.collect

/** What Home shows: the chosen extension's catalogs (as they arrive) and Continue Watching. */
class HomeState {
    var providers by mutableStateOf<List<MainAPI>>(emptyList())
    var api by mutableStateOf<MainAPI?>(null)
    val sections = mutableStateListOf<Engine.HomeSection>()
    var loading by mutableStateOf(false)
    var continueWatching by mutableStateOf<List<WatchEntry>>(emptyList())
    /** Bumped when the user edits catalogs, so Home reloads. */
    var catalogVersion by mutableStateOf(0)
    var editing by mutableStateOf(false)

    /** Banner titles: the first few of the first row. */
    val featured: List<SearchResponse> get() = sections.firstOrNull()?.list?.list.orEmpty().take(6)

    fun choose(api: MainAPI) {
        Settings.lastHomeProvider = api.name
        this.api = api
    }
}

@Composable
fun rememberHomeState(): HomeState {
    val state = remember { HomeState() }
    val version by PluginManager.version.collectAsState()
    LaunchedEffect(version) {
        val list = Engine.providers().filter { it.hasMainPage }
        state.providers = list
        val keep = state.api?.name ?: Settings.lastHomeProvider
        state.api = list.firstOrNull { it.name == keep } ?: list.firstOrNull()
    }
    // Reload when the chosen extension changes (not on every extension that finishes loading).
    DisposableEffect(Unit) {
        val off = io.github.sherazi5110.cloudstream.Catalogs.onChange { state.catalogVersion++ }
        onDispose { off() }
    }
    LaunchedEffect(state.api, state.catalogVersion) {
        state.sections.clear()
        val api = state.api ?: return@LaunchedEffect
        state.loading = true
        Engine.home(listOf(api)).collect { section ->
            val i = state.sections.indexOfFirst { it.order > section.order }.let { if (it == -1) state.sections.size else it }
            state.sections.add(i, section)
        }
        state.loading = false
    }
    DisposableEffect(Unit) {
        state.continueWatching = Library.continueWatching()
        val off = Library.onChange { state.continueWatching = Library.continueWatching() }
        onDispose { off() }
    }
    return state
}

/** Loads a title's page in the background (for the banner's backdrop and plot); null until it arrives. */
@Composable
fun rememberDetails(apiName: String?, url: String?): LoadResponse? {
    var response by remember(apiName, url) {
        mutableStateOf(apiName?.let { Engine.provider(it) }?.let { api -> url?.let { Titles.cached(api, it) } })
    }
    LaunchedEffect(apiName, url) {
        if (response != null || apiName == null || url == null) return@LaunchedEffect
        val api = Engine.provider(apiName) ?: return@LaunchedEffect
        response = runCatching { Titles.load(api, url) }.getOrNull()
    }
    return response
}

/** Opens a title page. */
fun openTitle(item: SearchResponse) = Nav.open(Screen.Details(item.apiName, item.url, item.name, item.posterUrl))

fun openTitle(e: WatchEntry) = Nav.open(Screen.Details(e.apiName, e.url, e.name, e.posterUrl))

/** "75 min left" / "S1 E2 · 43 min left". */
fun WatchEntry.leftText(): String {
    val left = ((duration - position) / 60).toInt().coerceAtLeast(1)
    return listOfNotNull(episodeLabel?.substringBefore(" · ")?.takeIf { it.isNotBlank() && it != name }, "$left min left").joinToString(" · ")
}
