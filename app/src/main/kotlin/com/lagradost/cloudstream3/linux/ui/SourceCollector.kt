package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.LinkEvent
import com.lagradost.cloudstream3.linux.Playable
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Collects an extension's sources and subtitles. It starts on the loading screen and
 * keeps running during playback, so the player's Sources menu fills up over time.
 */
class SourceCollector(private val api: MainAPI, private val playable: Playable) {
    val links = mutableListOf<ExtractorLink>()
    val subtitles = mutableListOf<SubtitleFile>()
    var finished = false
        private set
    var error: Throwable? = null
        private set
    var lastSource: String? = null
        private set
    private var job: Job? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun onUpdate(listener: () -> Unit) {
        listeners += listener
    }

    fun start() {
        job?.cancel()
        links.clear(); subtitles.clear(); finished = false; error = null
        job = ui.launch {
            Engine.links(api, playable.data).collect { event ->
                when (event) {
                    is LinkEvent.Link -> if (links.none { it.url == event.link.url }) {
                        links += event.link
                        lastSource = event.link.source.ifBlank { event.link.name }
                    }
                    is LinkEvent.Subtitle -> if (subtitles.none { it.url == event.subtitle.url }) subtitles += event.subtitle
                    is LinkEvent.Done -> {
                        finished = true
                        error = event.error
                    }
                }
                listeners.toList().forEach { it() }
            }
        }
    }

    fun ranked(): List<ExtractorLink> = Engine.rank(links)

    fun cancel() {
        job?.cancel()
        listeners.clear()
    }
}
