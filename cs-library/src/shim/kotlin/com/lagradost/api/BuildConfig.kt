// Replaces the buildkonfig-generated object. Keys are read from the environment.
package com.lagradost.api

object BuildConfig {
    val MDL_API_KEY: String = System.getenv("MDL_API_KEY") ?: "null"
    val TRAKT_CLIENT_ID: String = System.getenv("TRAKT_CLIENT_ID") ?: "null"
}
