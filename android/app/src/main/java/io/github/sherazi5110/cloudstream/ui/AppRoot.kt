package io.github.sherazi5110.cloudstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home
    Tab.SEARCH -> Icons.Filled.Search
    Tab.LIBRARY -> Icons.Filled.VideoLibrary
    Tab.EXTENSIONS -> Icons.Filled.Extension
    Tab.MORE -> if (isTv) Icons.Filled.Settings else Icons.Filled.MoreHoriz
}

@Composable
fun AppRoot() {
    val top = Nav.top
    BackHandler(enabled = Nav.stack.isNotEmpty() || Nav.tab != Tab.HOME) { Nav.back() }
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        if (top is Screen.Player) {
            PlayerScreen(top)
            return@Box
        }
        if (isTv) Row(Modifier.fillMaxSize()) {
            Rail()
            Box(Modifier.weight(1f).fillMaxHeight()) { Content(top) }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { Content(top) }
            if (top == null) BottomBar()
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
                Tab.SEARCH -> SearchScreen()
                Tab.LIBRARY -> LibraryScreen()
                Tab.EXTENSIONS -> ExtensionsScreen()
                Tab.MORE -> MoreScreen()
            }
        }
        is Screen.Details -> holder.SaveableStateProvider("details|${top.apiName}|${top.url}") { DetailsScreen(top) }
        is Screen.Catalog -> CatalogScreen(top)
        is Screen.Repository -> RepositoryScreen(top)
        is Screen.Player -> Unit
    }
}

/** Phone: Home · Search · My list · More (Live TV joins in phase 2). */
@Composable
private fun BottomBar() {
    NavigationBar(containerColor = Palette.background.copy(alpha = 0.97f)) {
        listOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARY, Tab.MORE).forEach { tab ->
            val selected = Nav.tab == tab || (tab == Tab.MORE && Nav.tab == Tab.EXTENSIONS)
            NavigationBarItem(
                selected = selected,
                onClick = { Nav.select(tab) },
                icon = { Icon(tab.icon(), tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.accent, selectedTextColor = Palette.accent,
                    indicatorColor = Palette.accent.copy(alpha = 0.18f),
                    unselectedIconColor = Palette.muted, unselectedTextColor = Palette.muted,
                ),
            )
        }
    }
}

/** Android TV: the Linux app's icon rail. */
@Composable
private fun Rail() {
    Column(
        Modifier.fillMaxHeight().width(84.dp).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Logo(46.dp)
        Spacer(Modifier.size(18.dp))
        listOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARY, Tab.EXTENSIONS).forEach { RailItem(it) }
        Spacer(Modifier.weight(1f))
        RailItem(Tab.MORE)
    }
}

@Composable
private fun RailItem(tab: Tab) {
    val selected = Nav.tab == tab && Nav.stack.isEmpty()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(52.dp).card(shape, focusScale = 1.1f) { Nav.select(tab) }
            .background(if (selected) Palette.accent.copy(alpha = 0.22f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(tab.icon(), tab.label, tint = if (selected) Palette.accent else Palette.muted, modifier = Modifier.size(26.dp))
    }
}
