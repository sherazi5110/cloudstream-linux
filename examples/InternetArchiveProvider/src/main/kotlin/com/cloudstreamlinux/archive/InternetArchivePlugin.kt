package com.cloudstreamlinux.archive

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** Written exactly like an Android extension: it runs unchanged in CloudStream for Android. */
@CloudstreamPlugin
class InternetArchivePlugin : Plugin() {
    override fun load(context: Context) {
        val provider = InternetArchiveProvider()
        // Optional override, stored through the regular CloudStream key/value store.
        CloudStreamApp.getKey<String>(BASE_URL_KEY)?.let { provider.mainUrl = it }
        registerMainAPI(provider)
    }

    companion object {
        const val BASE_URL_KEY = "internet_archive_base_url"
    }
}
