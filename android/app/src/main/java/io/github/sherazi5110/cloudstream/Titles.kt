package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * Loaded title pages, shared by the Home banner (backdrop, plot) and the title page, so opening
 * a title the banner already loaded is instant. Small and in memory only.
 */
object Titles {
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, LoadResponse>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadResponse>?) = size > 60
    })
    private val locks = Collections.synchronizedMap(HashMap<String, Mutex>())

    fun cached(api: MainAPI, url: String): LoadResponse? = cache["${api.name}|$url"]

    /** Loads once per title even when several screens ask at the same time. */
    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        val key = "${api.name}|$url"
        cache[key]?.let { return it }
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            cache[key] ?: Engine.load(api, url)?.also { cache[key] = it }
        }
    }
}
