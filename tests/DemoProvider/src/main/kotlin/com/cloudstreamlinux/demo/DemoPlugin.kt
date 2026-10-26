package com.cloudstreamlinux.demo

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DemoPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = DemoProvider()
        CloudStreamApp.getKey<String>("demo_base_url")?.let { provider.mainUrl = it }
        registerMainAPI(provider)
    }
}
