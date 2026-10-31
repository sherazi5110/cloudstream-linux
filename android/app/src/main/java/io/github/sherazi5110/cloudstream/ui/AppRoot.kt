package io.github.sherazi5110.cloudstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Wide layout (the Linux app's: icon rail on the left, top bar) on Android TV and whenever the screen is
 * wider than tall (tablets and phones in landscape); the phone layout (bottom bar) in portrait.
 */
val LocalWide = compositionLocalOf { false }

/** Also callable outside composition-aware code paths that already read it once. */
val isWide: Boolean @Composable get() = LocalWide.current

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home
    Tab.LIVE -> Icons.Filled.LiveTv
    Tab.SEARCH -> Icons.Filled.Search
    Tab.LIBRARY -> Icons.Filled.VideoLibrary
    Tab.EXTENSIONS -> Icons.Filled.Extension
    Tab.MORE -> Icons.Filled.MoreHoriz
}

@Composable
fun AppRoot() {
    val top = Nav.top
    BackHandler(enabled = Nav.stack.isNotEmpty() || Nav.tab != Tab.HOME) { Nav.back() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background)) {
        if (ThemeState.mode == ThemeMode.GLASS) GlassBackdrop(Modifier.fillMaxSize())
        val wide = isTv || maxWidth > maxHeight
        CompositionLocalProvider(LocalWide provides wide) {
            if (top is Screen.Player) {
                PlayerScreen(top)
                return@CompositionLocalProvider
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                Rail()
                Box(Modifier.weight(1f).fillMaxHeight()) { Content(top) }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Content(top) }
                if (top == null) BottomBar()
            }
            ProfileOverlays()
        }
    }
}

@Composable
private fun Content(top: Screen?) {
    // Each tab keeps its scroll position when you come back to it.
    val holder = rememberSaveableStateHolder()
    when (top) {
        null -> holder.SaveableStateProvider(Nav.tab.name) {
            when (Nav.tab) {
                Tab.HOME -> HomeScreen()
                Tab.LIVE -> LiveTvScreen()
                Tab.SEARCH -> SearchScreen()
                Tab.LIBRARY -> LibraryScreen()
                Tab.EXTENSIONS -> ExtensionsScreen()
                Tab.MORE -> SettingsScreen()
            }
        }
        is Screen.Details -> holder.SaveableStateProvider("details|${top.apiName}|${top.url}") { DetailsScreen(top) }
        is Screen.Catalog -> CatalogScreen(top)
        is Screen.Repository -> RepositoryScreen(top)
        is Screen.SettingsPage -> SettingsPageScreen(top)
        is Screen.Player -> Unit
    }
}

/**
 * The wide layout's top bar: page controls on the left (Home's extension picker), Search on the right.
 * Drawn over the page, so Home's banner reaches the top edge.
 */
@Composable
fun TopBar(modifier: Modifier = Modifier, start: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        start()
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.card(RoundedCornerShape(50)) { Nav.select(Tab.SEARCH) }
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Search", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.width(10.dp))
        ProfileButton(30.dp, showName = true)
    }
}

/** Portrait: Home · Live TV · Search · My list · More. */
@Composable
private fun BottomBar() {
    NavigationBar(containerColor = Palette.background.copy(alpha = 0.97f)) {
        visibleTabs(listOf(Tab.HOME, Tab.LIVE, Tab.SEARCH, Tab.LIBRARY, Tab.MORE)).forEach { tab ->
            val selected = Nav.tab == tab || (tab == Tab.MORE && Nav.tab == Tab.EXTENSIONS)
            NavigationBarItem(
                selected = selected,
                onClick = { Nav.select(tab) },
                icon = { Icon(tab.icon(), tab.label) },
                label = { Text(if (tab == Tab.MORE) "More" else tab.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.accent, selectedTextColor = Palette.accent,
                    indicatorColor = Palette.accent.copy(alpha = 0.18f),
                    unselectedIconColor = Palette.muted, unselectedTextColor = Palette.muted,
                ),
            )
        }
    }
}

/** Wide: the Linux app's icon rail. */
@Composable
private fun Rail() {
    Column(
        Modifier.fillMaxHeight().width(76.dp).background(Palette.rail).systemBarsPadding().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Logo(42.dp)
        Spacer(Modifier.size(14.dp))
        visibleTabs(listOf(Tab.HOME, Tab.LIVE, Tab.SEARCH, Tab.LIBRARY, Tab.EXTENSIONS)).forEach { RailItem(it, it.icon()) }
        Spacer(Modifier.weight(1f))
        if (!ProfileUi.current.kids) RailItem(Tab.MORE, Icons.Filled.Settings)
        ProfileButton(36.dp)
    }
}

@Composable
private fun RailItem(tab: Tab, icon: ImageVector) {
    val selected = Nav.tab == tab && Nav.stack.isEmpty()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(48.dp).card(shape, focusScale = 1.1f) { Nav.select(tab) }
            .background(if (selected) Palette.accent.copy(alpha = 0.22f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tab.label, tint = if (selected) Palette.accent else Palette.muted, modifier = Modifier.size(24.dp))
    }
}

/** Tabs this profile sees: no Live TV for kids unless allowed, no Extensions for kids. */
private fun visibleTabs(tabs: List<Tab>): List<Tab> = tabs.filter { t ->
    when (t) {
        Tab.LIVE -> io.github.sherazi5110.cloudstream.LiveTv.availableToProfile
        Tab.EXTENSIONS -> !ProfileUi.current.kids
        else -> true
    }
}
