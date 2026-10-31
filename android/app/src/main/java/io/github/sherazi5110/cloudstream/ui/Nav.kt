package io.github.sherazi5110.cloudstream.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import io.github.sherazi5110.cloudstream.Playable

/** The bottom bar (phone) / icon rail (TV) destinations. */
enum class Tab(val label: String) { HOME("Home"), LIVE("Live TV"), SEARCH("Search"), LIBRARY("My list"), EXTENSIONS("Extensions"), MORE("Settings") }

/** Screens opened on top of a tab. */
sealed interface Screen {
    data class Details(val apiName: String, val url: String, val name: String, val posterUrl: String?) : Screen
    /** Finds sources for [playable], then plays it. [chooseSource] opens the list instead of auto-playing. */
    data class Player(
        val api: MainAPI, val response: LoadResponse, val playable: Playable, val chooseSource: Boolean = false,
        /** Live TV: the channel list being watched, for CH+ / CH−. */
        val channels: List<io.github.sherazi5110.cloudstream.LiveTv.Channel>? = null,
        val channel: io.github.sherazi5110.cloudstream.LiveTv.Channel? = null,
    ) : Screen
    /** "See all": one catalog of one extension, endlessly scrolling. */
    data class Catalog(val apiName: String, val sectionName: String, val title: String) : Screen
    /** A Settings section opened on its own (portrait). */
    data class SettingsPage(val section: String) : Screen
    data class Repository(val url: String, val name: String) : Screen
}

/** A tiny back stack: one per app, kept in memory (the activity survives rotation). */
object Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Screen>()

    /** Text typed into Search, kept when leaving the tab. */
    var searchQuery by mutableStateOf("")

    val top: Screen? get() = stack.lastOrNull()

    fun open(screen: Screen) {
        // Opening the same title twice in a row (double tap) shouldn't stack it.
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    /** Returns false when there is nothing to go back to (the system then leaves the app). */
    fun back(): Boolean {
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (tab != Tab.HOME) {
            tab = Tab.HOME
            return true
        }
        return false
    }

    fun select(tab: Tab) {
        // Kids profiles have no Settings or Extensions pages unless a parent unlocks them.
        if (tab == Tab.EXTENSIONS || tab == Tab.MORE) ProfileUi.guarded { stack.clear(); this.tab = tab }
        else { stack.clear(); this.tab = tab }
    }
}
