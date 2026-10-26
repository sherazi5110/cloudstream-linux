package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.nicehttp.addGenericDns
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Configures the shared clients extensions use (`app`, `insecureApp`), like upstream's
 * Requests.initClient: HTTP cache, DNS-over-HTTPS and sane timeouts so one dead host
 * cannot stall a provider indefinitely.
 */
object Network {
    lateinit var client: OkHttpClient
        private set

    fun init() {
        val cache = Cache(File(Platform.context.getCacheDir(), "http_cache"), 100L * 1024 * 1024)
        val dispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 16
        }
        val builder = OkHttpClient.Builder()
            .cache(cache)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        Settings.dns.url?.let { builder.addGenericDns(it, Settings.dns.ips) }
        client = builder.build()
        app.baseClient = client
        insecureApp.baseClient = client.newBuilder().ignoreAllSSLErrors().build()
    }
}
