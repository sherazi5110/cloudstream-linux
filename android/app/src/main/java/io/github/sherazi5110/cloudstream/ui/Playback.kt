package io.github.sherazi5110.cloudstream.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.Playable
import io.github.sherazi5110.cloudstream.Titles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Playback {
    /** Where "Play"/"Resume" starts: the episode last watched (or the next one if it was finished), else the first. */
    fun resumeTarget(api: MainAPI, response: LoadResponse): Playable? {
        val playables = Engine.playables(response)
        val last = Library.lastWatched(api.name, response.url) ?: return playables.firstOrNull()
        val i = playables.indexOfFirst { it.data == last.episodeData }
        if (i == -1) return playables.firstOrNull()
        return if (last.finished) playables.getOrNull(i + 1) ?: playables[i] else playables[i]
    }

    /** "Resume S1 E2 · 11:08", "Play S1 E3" or "Play". */
    fun resumeLabel(api: MainAPI, response: LoadResponse, target: Playable?): String {
        target ?: return "Play"
        val code = listOfNotNull(target.season?.let { "S$it" }, target.episode?.let { "E$it" }).joinToString(" ")
        val progress = Library.progress(api.name, target.data)?.takeIf { it.duration > 0 && it.position / it.duration in 0.02..0.92 }
        val many = Engine.playables(response).size > 1
        return when {
            progress != null -> listOfNotNull("Resume", code.takeIf { many && it.isNotBlank() }).joinToString(" ") + " · " + clock(progress.position)
            many && code.isNotBlank() -> "Play $code"
            else -> "Play"
        }
    }

    fun play(api: MainAPI, response: LoadResponse, playable: Playable, chooseSource: Boolean = false) =
        Nav.open(Screen.Player(api, response, playable, chooseSource))

    /** Play straight from a card or the banner: loads the title first. */
    fun playFrom(scope: CoroutineScope, item: SearchResponse) {
        val api = Engine.provider(item.apiName) ?: return
        scope.launch {
            val response = runCatching { Titles.load(api, item.url) }.getOrNull()
            withContext(Dispatchers.Main) {
                val target = response?.let { resumeTarget(api, it) }
                if (response == null || target == null) {
                    App.toast("Couldn't load ${item.name}")
                    openTitle(item)
                } else play(api, response, target)
            }
        }
    }
}

/** Seconds as "1:02:03" or "11:08". */
fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
